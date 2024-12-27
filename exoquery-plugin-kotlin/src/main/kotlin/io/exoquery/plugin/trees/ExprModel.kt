package io.exoquery.plugin.trees

import io.decomat.*
import io.exoquery.BID
import io.exoquery.Runtimes
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.plugin.transform.BuilderContext
import io.exoquery.xr.XR
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

class RuntimesExpr(val runtimes: List<Pair<BID, IrExpression>>) {
  context(BuilderContext) fun lift(): IrExpression {
    return with (makeLifter()) {
      val bindsList = runtimes.map { pair ->
        pair.lift(
          {bid -> bid.lift()},
          { it })
      }
      make<Runtimes>(bindsList.liftExpr<Pair<BID, IrExpression>>())
    }
  }
}

object SqlExpressionExpr {
  data class Uprootable(val xr: XR) {
    companion object {
      context (CompileLogger) operator fun <AP: Pattern<SqlExpressionExpr.Uprootable>> get(x: AP) =
        customPattern1(x) { it: IrCall ->
          it.match(
            // SqlExpression(unpackExpr(str))
            case(ExtractorsDomain.CaseClassConstructorCall1[Is("io.exoquery.SqlExpression"), Ir.Call.FunctionUntethered1[Is()]]).then { _, (ir) ->
              val irConst = ir as? IrConst<String> ?: throw IllegalArgumentException("value passed to unpackExpr was not a constant-string in:\n${it.dumpKotlinLike()}")
              val astString = irConst.value
              val ast = ProtoBuf.decodeFromHexString<XR.Expression>(astString)
              Components1(Uprootable(ast))
            }
          )
        }
    }
  } // TODO need add lifts
}

// create an IrExpression DynamicBind(listOf(Pair(BID, RuntimeBindValue), etc...))
