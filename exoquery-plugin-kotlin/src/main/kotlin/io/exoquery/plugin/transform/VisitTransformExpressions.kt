package io.exoquery.plugin.transform

import com.tylerthrailkill.helpers.prettyprint.pp
import io.exoquery.plugin.trees.ExtractorsDomain
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import java.nio.file.Path
import org.jetbrains.kotlin.ir.util.*


class VisitTransformExpressions(
  private val context: IrPluginContext,
  private val config: CompilerConfiguration,
  private val projectDir: Path
) : IrElementTransformerWithContext<ScopeSymbols>() {

  // currentFile is not initialized here yet or something???
  //val sourceFinder: FindSource = FindSource(this.currentFile, projectDir)

  private fun typeIsFqn(type: IrType, fqn: String): Boolean {
    if (type !is IrSimpleType) return false

    return when (val owner = type.classifier.owner) {
      is IrClass -> owner.kotlinFqName.asString() == fqn
      else -> false
    }
  }

//  override fun visitExpression(expression: IrExpression): IrExpression {
//    val compileLogger = CompileLogger(config)
//    compileLogger.warn("---------- Expression Checking:\n" + expression.dumpKotlinLike())
//    return super.visitExpression(expression)
//  }

  override fun visitCall(expression: IrCall, data: ScopeSymbols): IrElement {

    val scopeOwner = currentScope!!.scope.scopeOwnerSymbol

    val stack = RuntimeException()
    //compileLogger.warn(stack.stackTrace.map { it.toString() }.joinToString("\n"))


    val transformerCtx = TransformerOrigin(context, config, this.currentFile, data)
    val builderContext = transformerCtx.makeBuilderContext(expression, scopeOwner)

    val transformPrint = TransformPrintSource(builderContext)
    val queryLambdaMethodTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.LambdaMethodProducingXR(), this)
    val queryMethodTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.MethodProducingXR(), this)
    val interpolationTransformer = TransformInterepolatorInvoke(builderContext, this)

    // val queryMapTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryMap, "mapExpr", this)
    // val queryFilterTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryFilter, "filterExpr", this)
    // val queryFlatMapTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryFlatMap, "flatMapExpr", this)
    // val queryTakeTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryTake, "takeExpr", this)
    // val queryTakeSimpleTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryTakeSimple, "takeSimpleExpr", this)
    // val queryDropTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryDrop , "dropExpr", this)
    // val queryDropSimpleTransformer = TransformQueryMethod(builderContext, ExtractorsDomain.Call.QueryDropSimple , "dropSimpleExpr", this)

    val selectTransformer = TransformSelect(builderContext, this)
    val makeTableTransformer = TransformTableQuery(builderContext)
    val joinOnTransformer = TransformJoinOn(builderContext, this)
    val queryClauseUnitBindMethod = TransformSelectClauseUnitMethod(builderContext, ExtractorsDomain.Call.QueryClauseUnitBindMethod, this)

    // TODO Catch parser errors here, make a warning via the compileLogger (in the BuilderContext) & don't transform the expresison

    // TODO Create a 'transformer' for SelectClause that will just collect
    //      the variables declared there and propagate them to clauses defined inside of that

    // TODO Get the variables collected from queryMapTransformer or the transformer that activates
    //      and pass them in when the transformers recurse inside, currently only TransformQueryMap
    //      does this.


    //compileLogger.warn("---------- Call Checking:\n" + expression.dumpKotlinLike())

    val out = when {
      // 1st that that runs here because printed stuff should not be transformed
      // (and this does not recursively transform stuff inside)
      transformPrint.matches(expression) -> transformPrint.transform(expression)
      // Want to run interpolator invoke before other things because the result of it is an SqlExpression that will
      // the be re-parsed in the parser if it is inside of a context(EnclosedContext) e.g. Query.map
      interpolationTransformer.matches(expression) -> interpolationTransformer.transform(expression)
      joinOnTransformer.matches(expression) -> joinOnTransformer.transform(expression)
      queryClauseUnitBindMethod.matches(expression) -> queryClauseUnitBindMethod.transform(expression)
      makeTableTransformer.matches(expression) -> makeTableTransformer.transform(expression)
      selectTransformer.matches(expression) -> selectTransformer.transform(expression)
      queryMethodTransformer.matches(expression) -> queryMethodTransformer.transform(expression)
      queryLambdaMethodTransformer.matches(expression) -> queryLambdaMethodTransformer.transform(expression)
      else ->
        // No additional data (i.e. Scope-Symbols) to add since none of the transformers was activated
        super.visitCall(expression, data)
    }
    return out
  }
}




/** Finds the line and column of [irElement] within this file. */
fun IrFile.locationOf(irElement: IrElement?): CompilerMessageSourceLocation {
  val sourceRangeInfo = fileEntry.getSourceRangeInfo(
    beginOffset = irElement?.startOffset ?: 0,
    endOffset = irElement?.endOffset ?: 0
  )
  return CompilerMessageLocationWithRange.create(
    path = sourceRangeInfo.filePath,
    lineStart = sourceRangeInfo.startLineNumber + 1,
    columnStart = sourceRangeInfo.startColumnNumber + 1,
    lineEnd = sourceRangeInfo.endLineNumber + 1,
    columnEnd = sourceRangeInfo.endColumnNumber + 1,
    lineContent = null
  )!!
}

fun <T> T.pprint(
  indent: Int = 2,
  wrappedLineWidth: Int = 80
): String {
  val buff = StringBuffer()
  pp(this, indent, buff, wrappedLineWidth)
  return buff.toString()
}