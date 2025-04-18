package io.exoquery.plugin.trees

import io.decomat.*
import io.exoquery.CapturedBlock
import io.exoquery.xr.SelectClause
import io.exoquery.SqlQuery
import io.exoquery.annotation.DslFunctionCall
import io.exoquery.annotation.DslNestingIgnore
import io.exoquery.parseError
import io.exoquery.plugin.*
import io.exoquery.plugin.transform.CX
import io.exoquery.xr.*
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName

object Parser {
  context (CX.Scope, CX.Symbology)
  fun <X> scoped(parse: context(CX.Scope, CX.Symbology, CX.Parsing) () -> X): X =
    parse(this@Scope, this@Symbology, CX.Parsing())

  context (CX.Scope, CX.Symbology)
  fun <X> scoped(parsingScope: CX.Parsing, parse: context(CX.Scope, CX.Symbology, CX.Parsing) () -> X): X =
    parse(this@Scope, this@Symbology, parsingScope)

  // The action-parser requires a builder context to build the entity from setParams
  context(CX.Scope, CX.Symbology, CX.Parsing, CX.Builder)
  fun parseAction(expr: IrExpression): Pair<XR.Action, DynamicsAccum> =
    ParseAction.parse(expr) to binds

  context(CX.Scope, CX.Symbology, CX.Parsing) fun parseFunctionBlockBody(blockBody: IrBlockBody): Pair<XR.Expression, DynamicsAccum> =
    ParseExpression.parseFunctionBlockBody(blockBody) to binds

  context(CX.Scope, CX.Symbology, CX.Parsing) fun parseQuery(expr: IrExpression): Pair<XR.Query, DynamicsAccum> =
    ParseQuery.parse(expr) to binds

  context(CX.Scope, CX.Symbology, CX.Parsing) fun parseQueryFromBlock(expr: IrBlockBody): Pair<XR.Query, DynamicsAccum> =
    run {
      val parsedQuery =
        on(expr).match<XR.Query> (
          case(Ir.BlockBody.ReturnOnly[Is()]).then { ParseQuery.parse(it) }
        ) ?: run {
          ParseExpression.parseFunctionBlockBody(expr).asQuery()
        }

      parsedQuery to binds
    }

  context(CX.Scope, CX.Symbology, CX.Parsing) fun parseSelectClauseLambda(expr: IrExpression): Pair<SelectClause, DynamicsAccum> =
    ParseSelectClause.parseSelectLambda(expr) to binds

  context(CX.Scope, CX.Symbology, CX.Parsing) fun parseExpression(expr: IrExpression): Pair<XR.Expression, DynamicsAccum> =
    ParseExpression.parse(expr) to binds

  context(CX.Scope, CX.Symbology, CX.Parsing) fun parseValueParamter(expr: IrValueParameter): XR.Ident =
    expr.makeIdent()

  context(CX.Scope, CX.Parsing, CX.Symbology)
  internal fun parseArg(arg: IrExpression) =
    when {
      arg.type.isClass<SqlQuery<*>>() -> ParseQuery.parse(arg)
      else -> ParseExpression.parse(arg)
    }
}

context(CX.Scope, CX.Parsing, CX.Symbology)
fun IrValueParameter.makeIdent() =
  XR.Ident(this.unadulteratedName.sanitizeIdentName(), TypeParser.of(this), this.locationXR())



fun IrFunctionExpression.firstParam() =
  this.function.simpleValueParams[0]

fun IrCall.extensionOrDispatch() =
  this.extensionReceiver ?: this.dispatchReceiver

// Parser for GlobalCall and MethodCall
object CallParser {
  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun parse(expr: IrCall): XR.U.QueryOrExpression {

    fun IrSimpleFunctionSymbol.toFqNameXR() = this.owner.kotlinFqName.toXR()
    val reciever = expr.extensionReceiver ?: expr.dispatchReceiver

    return when {
      // for things like string.sql.left(...) ignore the "sql" part
      reciever != null && reciever is IrCall && reciever.ownerHasAnnotation<DslNestingIgnore>() && reciever.extensionOrDispatch() is IrCall -> {
        parseCall(reciever.extensionOrDispatch(), expr)
      }
      else -> parseCall(reciever, expr)
    }
  }

  context(CX.Scope, CX.Parsing, CX.Symbology)
  private fun parseCall(reciever: IrExpression?, expr: IrCall): XR.U.QueryOrExpression =
    when {
      reciever == null || reciever.type.isClass<CapturedBlock>() ->
        XR.GlobalCall(
          name = expr.symbol.owner.kotlinFqName.toXR(),
          args = expr.simpleValueArgs.map { arg -> arg?.let { Parser.parseArg(it) } ?: XR.Const.Null() },
          callType = expr.extractCallType(),
          type = TypeParser.of(expr),
          loc = expr.loc
        )
      else ->
        XR.MethodCall(
          head = Parser.parseArg(reciever!!),
          name = expr.symbol.safeName,
          args = expr.simpleValueArgs.map { arg -> arg?.let { Parser.parseArg(it) } ?: XR.Const.Null() },
          originalHostType = expr.type.classId()?.toXR() ?: XR.ClassId.Empty,
          type = TypeParser.of(expr),
          loc = expr.loc,
          callType = expr.extractCallType()
        )
    }

  context(CX.Scope, CX.Parsing, CX.Symbology)
  private fun IrCall.extractCallType(): XR.CallType {
    val arg =
      (this.symbol.owner.getAnnotationArgs<DslFunctionCall>().firstOrNull() ?: parseError("Could not find DslFunctionCall annotation", this))
        as? IrClassReference ?: parseError("DslFunctionCall annotation must have a single argument that is a class-reference (e.g. PureFunction::class)", this)
    val argXR =
      arg.classType.classFqName?.shortName()?.asString()?.let { XR.CallType.fromClassString(it) }
        ?: parseError("Could not parse CallType from: ${arg.dumpKotlinLike()}", arg)
    return argXR
  }



}
