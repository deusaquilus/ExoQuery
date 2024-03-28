package io.exoquery.plugin.transform

import io.decomat.Is
import io.decomat.case
import io.decomat.match
import io.decomat.on
import io.exoquery.annotation.ChangeReciever
import io.exoquery.structError
import io.exoquery.parseError
import io.exoquery.plugin.*
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.plugin.logging.Messages
import io.exoquery.plugin.trees.*
import io.exoquery.xr.XR
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

class TransformJoinOn(override val ctx: BuilderContext, val superTransformer: VisitTransformExpressions): Transformer() {
  context(BuilderContext, CompileLogger)
  override fun matchesBase(expression: IrCall): Boolean =
    ExtractorsDomain.Call.`join-on(expr)`.matchesMethod(expression)

  context(ParserContext, BuilderContext, CompileLogger)
  override fun transformBase(expression: IrCall): IrExpression {
    // For join(addresses).on { id == person.id } :
    //    funExpression would be `id == person.id`. Actually it includes the "hidden" reciver so it would be:
    //    `$this$on.id == person.id`
    val (caller, funExpression, params, blockBody) =
      expression.match(
        case(ExtractorsDomain.Call.`join-on(expr)`[Is()]).then { queryCallData -> queryCallData }
      ) ?: parseError("Illegal block on function:\n${Messages.PrintingMessage(expression)}")

     //There actually IS a reciver to this function and it should be named $this$on
    val reciverParam = funExpression.function.extensionReceiverParameter ?: structError("Extension Reciever for on-clause was null")
    val reciverSymbol = reciverParam.symbol.safeName
    val paramIdent = run {
      val tpe = TypeParser.of(reciverParam)
      XR.Ident(reciverSymbol, tpe, reciverParam.location().toLocationXR())
    }

    // TODO Recursively transform the block body?

    // parse the `on` clause of the join.on(...)
    val (onLambdaBody, bindsAccum) =
      with(makeParserContext(expression).copy(internalVars + ScopeSymbols(listOf(reciverParam.symbol)))) {
        Parser.parseFunctionBlockBody(blockBody)
      }

    val lifter = makeLifter()
    val paramIdentExpr = lifter.liftIdent(paramIdent)
    val onLambdaBodyExpr = lifter.liftXR(onLambdaBody)
    val loc = lifter.liftLocation(expression.locationXR())

    // To transform the TableQuery etc... in the join(<Heree>).on clause before the `on`
    // No scope symbols into caller since it comes Before the on-clause i.e. before any symbols could be created
    val newCaller = caller.transform(superTransformer, internalVars)

    val bindsList = bindsAccum.makeDynamicBindsIr()

    return newCaller.callMethod(ReplacementMethodToCall("onExpr", ChangeReciever.ToExtension)).invoke(paramIdentExpr, onLambdaBodyExpr, bindsList, loc)
  }
}

