package io.exoquery.plugin.transform

import io.decomat.Is
import io.decomat.case
import io.decomat.on
import io.exoquery.parseError
import io.exoquery.xr.XR
import io.exoquery.plugin.trees.*
import io.exoquery.plugin.printing.dumpSimple
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName


class TransformCapturedExpression(val superTransformer: VisitTransformExpressions): Transformer<IrCall>() {
  private val fqn: String = "io.exoquery.captureValue"

  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  override fun matches(expression: IrCall): Boolean =
    expression.symbol.owner.kotlinFqName.asString().let { it == fqn }

  // parent symbols are collected in the parent context
  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  override fun transform(expression: IrCall): IrExpression {
    val (xrExpr, dynamics) = parseSqlExpression(expression, superTransformer)
    val paramsExprModel = dynamics.makeParams()
    //val make = makeClassFromString("io.exoquery.SqlExpression", listOf(strExpr, paramsListExpr))
    //val makeCasted = builder.irImplicitCast(make, expression.type)
    val newSqlExpression =
      if (dynamics.noRuntimes()) {
        SqlExpressionExpr.Uprootable.plantNewUprootable(xrExpr, paramsExprModel)
      } else {
        SqlExpressionExpr.Uprootable.plantNewPluckable(xrExpr, dynamics.makeRuntimes(), paramsExprModel)
      }

    //logger.warn("=============== Modified value to: ${capturedAnnot.valueArguments[0]?.dumpKotlinLike()}\n======= Whole Type is now:\n${makeCasted.type.dumpKotlinLike()}")
    //logger.error("========== Output: ==========\n${newSqlExpression.dumpKotlinLike()}")
    return newSqlExpression
  }

  companion object {
    context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
    fun parseSqlExpression(expression: IrCall, superTransformer: VisitTransformExpressions)  = run {
      val bodyRaw =
        on(expression).match(
          // printExpr(.. { stuff }: IrFunctionExpression  ..): FunctionCall
          case(Ir.Call.FunctionUntethered1.Arg[Ir.FunctionExpression.withBlock[Is(), Is()]]).then { (_, body) ->
            body
          }
        )
          ?: parseError("Parsing Failed\n================== The expresson was not a Global Function (with one argument-block): ==================\n" + expression.dumpKotlinLike() + "\n--------------------------\n" + expression.dumpSimple())

      // Transform the contents of `capture { ... }` this is important for several reasons,
      // most notable any kind of variables used inside that need to be inlined e.g:
      // val x = capture { 123 }
      // val y = capture { x.use + 1 } // <- this is what we are transforming
      // Then the `val y` needs to first be transformed into:
      // val y = capture { SqlExpression(XR.Int(123), ...).use + 1 } which will be done by TransformProjectCapture
      // which is called by the superTransformer.visitBlockBody
      val body = superTransformer.visitBlockBody(bodyRaw) as IrBlockBody
      val (xr, dynamics) = Parser.parseFunctionBlockBody(body)

      val xrExpr = xr as? XR.Expression ?: parseError("Could not parse to expression:\n${xr}")
      xrExpr to dynamics
    }
  }
}
