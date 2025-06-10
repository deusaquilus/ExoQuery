package io.exoquery.plugin.transform

import com.github.vertical_blank.sqlformatter.SqlFormatter
import io.decomat.*
import io.exoquery.*
import io.exoquery.annotation.ExoBuildFunctionLabel
import io.exoquery.plugin.*
import io.exoquery.plugin.trees.ExtractorsDomain.SqlBuildFunction
import io.exoquery.plugin.trees.Lifter
import io.exoquery.plugin.trees.SqlActionExpr
import io.exoquery.plugin.trees.SqlBatchActionExpr
import io.exoquery.plugin.trees.SqlQueryExpr
import io.exoquery.sql.PostgresDialect
import io.exoquery.sql.Renderer
import io.exoquery.sql.SqlIdiom
import io.exoquery.sql.Token
import io.exoquery.util.TraceConfig
import io.exoquery.xr.XR
import io.exoquery.xr.encode
import io.exoquery.xr.toActionKind
import org.jetbrains.kotlin.ir.builders.createTmpVariable
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName
import kotlin.reflect.full.isSuperclassOf
import kotlin.time.measureTimedValue


class TransformCompileQuery(val superTransformer: VisitTransformExpressions) : Transformer<IrCall>() {

  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  override fun matches(expr: IrCall): Boolean =
    SqlBuildFunction.matches(expr)

  private data class BuildFunctionArgs(val queryLabel: String?)

  // Making args extraction somewhat modular so can add more args to the build function in the future
  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  private fun extractArgsFromCall(expr: IrCall): BuildFunctionArgs = run {
    val partsWithParams = expr.zipArgsWithParamsOrFail()
    val queryLabel =
      partsWithParams
        .find { (param, value) -> param.hasAnnotation<ExoBuildFunctionLabel>() }?.second
        // If the of the parameter is null then it must not be present and we don't care. If it does exist make sure it's an IrConst
        ?.let { it as? IrConst ?: parseError("A query-label must be a constant compile-time string but found: ${it.dumpKotlinLike()}", it) }
        ?.let { it.value.toString() }

    BuildFunctionArgs(queryLabel)
  }

  sealed interface ProcessResult<out T> {
    data class Success<T>(val value: T): ProcessResult<T>
    data class Failure(val error: Throwable): ProcessResult<Nothing>
  }

  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  override fun transform(expr: IrCall): IrExpression {

    fun extractDialectConstructor(dialectType: IrType) = run {
      // get the empty-args constructor from the dialect-type, if it doesn''t we need to know about it. If it does exist we'll use it if we need to use a runtime query
      val dialectCls = (dialectType.classOrNull ?: parseError("The dialect type must be a class but instead it was: ${dialectType.dumpKotlinLike()}", expr))
      val construct = dialectCls.constructors.find { it.owner.valueParameters.size == 1 } ?: parseError("The dialect type must have 1-arg constructor that takes a trace-types argument but it did not", expr)

      // see if we can get the dialect instance at compile-time if we can't then create an error
      // TODO if the user has marked the file with a special annotation then allow the runtime `construct` to create the dialect if the class.forName fails otherwise immediately throw an error here
      val clsPackageName = dialectCls.owner.kotlinFqName.asString() ?: parseError("The dialect class ${dialectType.dumpKotlinLike()} must have a package name but it did not", expr)
      construct to clsPackageName
    }

    val transfomerScope = symbolSet


    // recurse down into the expression in order to make it into an Uprootable if needed
    return expr.match(
      case(SqlBuildFunction[Is()]).thenThis { (sqlQueryExprRaw, dialectType, isPretty) ->
        val parsedArgs = extractArgsFromCall(expr)

        val compileLocation = expr.location(currentFile.fileEntry)
        val fileLabel = (parsedArgs.queryLabel?.let { it + " - " } ?: "") + "file:${compileLocation.show()}"

        val (traceConfig, writeSource) = ComputeEngineTracing.invoke(fileLabel, dialectType)
        val (construct, clsPackageName) = extractDialectConstructor(dialectType)

        // If sqlQueryExprRaw is an Uprootable then we need to do superTransformer.visitCall on that which typically will happen if you call .build on a capture directly
        // e.g. in `capture { Table<Person>() }.build<PostgresDialect>()` sqlQueryExprRaw will be the IrCall `capture { Table<Person>() }` on which we need to call the superTransformer.visitCall.
        // In the case that sqlQueryExprRaw is some kind of expression e.g.
        // val cap = capture { Table<Person>() }; cap.build<PostgresDialect>() then the uprootable has already been created by the variable declaration
        // and is something like `val cap = SqlQuery(xr=...)`. That means that we need to do a TransformProjectCapture on the `cap` varaible that is being called
        // which is invoked by the `superTransformer.visitExpression`.
        // In both of these cases superTransformer.recurse delegates-out to the correct transformer based on the type-match of the expression.
        val sqlExpr = superTransformer.recurse(sqlQueryExprRaw)
        val containerType = sqlQueryExprRaw.type ?: parseError("Invalid container type ${expr.dispatchReceiver?.type?.dumpKotlinLike()}. (Reciver ${expr.dispatchReceiver?.dumpKotlinLike()})", expr)
        val dialect = ConstructCompiletimeDialect.of(clsPackageName, traceConfig)

        fun Token.renderQueryString(pretty: Boolean, xr: XR) = run {
          val queryStringRaw =
            try {
              this.renderWith(Renderer(false, false))
            } catch (e: Exception) {
              parseError("The query could not be rendered: ${e.message}\n------------------\n${xr.showRaw()}", expr)
            }
          if (isPretty)
            SqlFormatter.format(queryStringRaw)
          else
            queryStringRaw
        }

        when (ContainerType.determine(containerType)) {
          is ContainerType.Query -> {
            sqlExpr.match(
              case(SqlQueryExpr.Uprootable[Is()]).then { uprootable ->
                val xr = uprootable.unpackOrErrorXR().successOrParseError(sqlExpr)

                val (queryAndToken, compileTime) = measureTimedValue {
                  try {
                    ProcessResult.Success(dialect.processQuery(xr))
                  } catch(e: Throwable) {
                    ProcessResult.Failure(e)
                  } finally {
                    // close file writing if it was happening
                    writeSource?.close()
                  }
                }
                when (queryAndToken) {
                  is ProcessResult.Success -> {
                    val (queryTokenized, query) = queryAndToken.value

                    // Can include the sql-formatting library here since the compiler is always on the JVM!
                    val queryString = queryTokenized.renderQueryString(isPretty, xr)
                    accum.addQuery(PrintableQuery(queryString, compileLocation, parsedArgs.queryLabel))

                    val msgAdd = parsedArgs.queryLabel?.let { " ($it)" } ?: ""

                    if (options?.queryPrintingEnabled ?: false)
                      this@Scope.logger.report(outputStringMaker.make(compileTime.inWholeMilliseconds, queryString, "query"), expr)
                    SqlCompiledQueryExpr(sqlExpr, queryString, queryTokenized, false, parsedArgs.queryLabel, Phase.CompileTime, uprootable.packedXR, query.encode()).plant()

                  }
                  is ProcessResult.Failure -> {
                    logger.warn("The query could not be transformed at compile-time but encountered an error. Falling back to runtime transformation.\n------------------- Error -------------------\n${queryAndToken.error.stackTraceToString()}", expr.location())
                    callBuildRuntime(construct, traceConfig, parsedArgs.queryLabel, isPretty, sqlExpr)
                  }
                }
              }
            ) ?: run {
              logger.warn("The query could not be transformed at compile-time", expr.location())
              callBuildRuntime(construct, traceConfig, parsedArgs.queryLabel, isPretty, sqlExpr)
            }
          }
          is ContainerType.Action -> {
            sqlExpr.match(
              case(SqlActionExpr.Uprootable[Is()]).then { uprootable ->
                val xr = uprootable.unpackOrErrorXR().successOrParseError(sqlExpr)
                val (queryTokenized, compileTime) = measureTimedValue {
                  try {
                    dialect.processAction(xr)
                  } finally {
                    // close file writing if it was happening
                    writeSource?.close()
                  }
                }

                // Can include the sql-formatting library here since the compiler is always on the JVM!
                val queryString = queryTokenized.renderQueryString(isPretty, xr)
                val actionKind = xr.toActionKind()
                val actionReturningKind = ActionReturningKind.fromActionXR(xr)
                accum.addQuery(PrintableQuery(queryString, compileLocation, parsedArgs.queryLabel))

                val msgAdd = parsedArgs.queryLabel?.let { " ($it)" } ?: ""
                if (options?.queryPrintingEnabled ?: false)
                  logger.report(outputStringMaker.make(compileTime.inWholeMilliseconds, queryString, "action"), expr)

                val sqlActionTmpVar = builder.scope.createTmpVariable(sqlExpr)
                val output = SqlCompiledActionExpr(builder.irGet(sqlActionTmpVar), queryString, queryTokenized, actionKind, actionReturningKind, parsedArgs.queryLabel, Phase.CompileTime, uprootable.packedXR).plant()
                // IMPORTANT notes inside of makeRunFunction as to why this is used here
                makeRunFunction(listOf(sqlActionTmpVar), output)
              }
            ) ?: run {
              //logger.warn("The action could not be transformed at compile-time", expr.location())
              logger.warn("The action could not be transformed at compile-time", expr)
              callBuildRuntime(construct, traceConfig, parsedArgs.queryLabel, isPretty, sqlExpr)
            }
          }
          is ContainerType.BatchAction -> {
            //logger.error("---------------- HERE:\n${sqlExpr.dumpKotlinLike()}")
            sqlExpr.match(
              case(SqlBatchActionExpr.Uprootable[Is()]).then { uprootable ->
                val xr = uprootable.unpackOrErrorXR().successOrParseError(sqlExpr)
                val (queryTokenized, compileTime) = measureTimedValue {
                  try {
                    dialect.processAction(xr.action)
                  } finally {
                    // close file writing if it was happening
                    writeSource?.close()
                  }
                }

                // Can include the sql-formatting library here since the compiler is always on the JVM!
                val queryString = queryTokenized.renderQueryString(isPretty, xr)
                val actionKind = xr.action.toActionKind()
                val actionReturningKind = ActionReturningKind.fromActionXR(xr.action)
                accum.addQuery(PrintableQuery(queryString, compileLocation, parsedArgs.queryLabel))

                val msgAdd = parsedArgs.queryLabel?.let { " ($it)" } ?: ""

                if (options?.queryPrintingEnabled ?: false)
                  logger.report(outputStringMaker.make(compileTime.inWholeMilliseconds, queryString, "batch-action"), expr)

                SqlCompiledBatchActionExpr(sqlExpr, queryString, queryTokenized, actionKind, actionReturningKind, parsedArgs.queryLabel, Phase.CompileTime, uprootable.packedXR).plant()
              }
            ) ?: run {
              logger.warn("The batch-action could not be transformed at compile-time", expr.location())
              with(Lifter(this@Builder)) {
                val labelExpr = if (parsedArgs.queryLabel != null) parsedArgs.queryLabel.lift() else irBuilder.irNull()
                // we still know it's x.build or x.buildPretty so just use that (for now ignore formatting if it is at runtime)
                val dialect = buildRuntimeDialect(construct, traceConfig)
                sqlExpr.callDispatch("buildRuntime")(dialect, labelExpr, isPretty.lift())
              }
            }
          }
        }
      }
    ) ?: run {
      expr
    }
  }

  sealed interface ContainerType {
    data object Query : ContainerType
    data object Action : ContainerType
    data object BatchAction : ContainerType
    companion object {
      context(CX.Scope)
      fun determine(type: IrType) =
        when {
          type.isClass<SqlQuery<*>>() -> Query
          type.isClass<SqlAction<*, *>>() -> Action
          type.isClass<SqlBatchAction<*, *, *>>() -> BatchAction
          else -> parseError("The type ${type.dumpKotlinLike()} must be a SqlQuery or SqlAction but it was not")
        }
    }
  }

  context(CX.Scope, CX.Builder)
  fun callBuildRuntime(construct: IrConstructorSymbol, traceConfig: TraceConfig, queryLabel: String?, isPretty: Boolean, sqlQueryExpr: IrExpression): IrExpression = run {
    with(Lifter(this@Builder)) {
      val labelExpr = if (queryLabel != null) queryLabel.lift() else irBuilder.irNull()
      // we still know it's x.build or x.buildPretty so just use that (for now ignore formatting if it is at runtime)
      val dialect = buildRuntimeDialect(construct, traceConfig)
      sqlQueryExpr.callDispatch("buildRuntime")(dialect, labelExpr, isPretty.lift())
    }
  }

  context(CX.Scope, CX.Builder)
  fun buildRuntimeDialect(construct: IrConstructorSymbol, traceConfig: TraceConfig) =
    builder.irCall(construct).apply {
      with(makeLifter()) {
        putValueArgument(0, traceConfig.lift(options?.projectDir))
      }
    }
}

object ConstructCompiletimeDialect {
  context(CX.Scope, CX.Builder)
  fun of(fullPath: String, traceConfig: TraceConfig) =
    when {
      "io.exoquery.PostgresDialect" in fullPath -> PostgresDialect(traceConfig)
      else ->
        (Class.forName(fullPath) ?: parseError("Could not find the dialect class: ${fullPath}"))
          .constructors.find {
            it.parameters.size == 1 && it.parameters.first().type.kotlin.isSuperclassOf(TraceConfig::class)
          }?.let { it.newInstance(traceConfig) } as SqlIdiom
          ?: parseError("The dialect class ${fullPath} must have a 1-arg constructor that takes a trace-config but it did not")
    }
}
