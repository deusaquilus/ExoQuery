package io.exoquery.plugin.transform

import io.decomat.Is
import io.decomat.case
import io.decomat.on
import io.exoquery.Interpolator
import io.exoquery.parseError
import io.exoquery.plugin.findMethodOrFail
import io.exoquery.plugin.printing.dumpSimple
import io.exoquery.plugin.safeName
import io.exoquery.plugin.trees.*
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.superTypes

class TransformInterepolatorInvoke(val ctx: BuilderContext) {
  private val compileLogger = ctx.logger

  fun matches(expression: IrCall): Boolean =
    expression.dispatchReceiver?.type?.isClass<Interpolator<*, *>>() ?: false &&
      expression.symbol.safeName == "invoke"

  fun transform(expression: IrCall): IrExpression {
    val (caller, comps) =
      with(compileLogger) {
        on(expression).match(
          // interpolatorSubclass.invoke(.. { stuff } ...)
          case(ExtractorsDomain.Call.InterpolateInvoke[Is(), Is()]).then { caller, comps ->
            caller to comps
          }
        )
      } ?: run {
        val bar = "\${bar}"
        parseError(
          """|======= Parsing Error =======
           |The contents of Interpolator.invoke(...) must be a single String concatenation statement e.g:
           |myInterpolator.invoke("foo $bar baz")
           |
           |==== However, the following was found: ====
           |${expression.dumpKotlinLike()}
           |======= IR: =======
           |${expression.dumpSimple()}"
        """.trimMargin()
        )
      }

    val parentCaller =
      caller.type.superTypes()
        .find { it.isClass<Interpolator<*, *>>() }
        ?: parseError("Could not isolate the parent type Interpolator<T, R>. This shuold be impossible.")

    // TODO need to catch parseError externally & not transform the expressions

    // Interpolator type T
    val interpolateType = parentCaller.simpleTypeArgs.get(0)
    val interpolateTypeClass = interpolateType.classOrNull ?: parseError("The interpolator T parameter type `${interpolateType.dumpKotlinLike()}` was not a class.")
    // Interpolator type R
    val interpolateReturn = parentCaller.simpleTypeArgs.get(1)

    val concatStringExprs =
      { a: IrExpression, b: IrExpression ->
        with (ctx) {
          ctx.builder.irString("").callMethod("plus")(a).callMethod("plus")(b)
        }
      }

    val (parts, params) =
      InterlacePartsParams<IrExpression>({ it.isClass<String>() }, concatStringExprs, { ctx.builder.irString("") })
        .invoke(comps)

    for ((i, comp) in params.withIndex()) {
      if (!comp.type.isSubtypeOfClass(interpolateTypeClass))
        error(
          """|"The #${i} interpolated block had a type of `${comp.type.dumpKotlinLike()}` but a type `${interpolateType.dumpKotlinLike()}` was expected by the ${caller.type} interpolator.
             |========= The faulty expression was: =========
             |${comp.dumpKotlinLike()}
          """.trimMargin()
        )
    }

    return with(ctx) {
      val lifter = makeLifter()
      val partsLifted =
        with (lifter) { parts.liftExpr<String>() }
      val paramsLifted =
        with (lifter) { params.liftExprTyped(interpolateType) }

      val runCall = caller.type.findMethodOrFail("interpolate")
      val partsLiftedFun = createLambda0(partsLifted, runCall.owner)
      val paramsLiftedFun = createLambda0(paramsLifted, runCall.owner)
      caller.callMethodWithType("interpolate", interpolateReturn)(
        partsLiftedFun,
        paramsLiftedFun
      )
    }
  }
}



fun main() {
  val initial = listOf("_", "_", "A", "B", "C", "_", "D")
  println("Initial: $initial")

  val comps =
    InterlacePartsParams<String>({t -> t == "_"}, { a, b -> a + b }, { "_" }).invoke(initial)

  println(comps)
  val (parts, params) = comps

  val a = parts.iterator()
  val b = params.iterator()
  val out = StringBuffer()
  while (a.hasNext() || b.hasNext()) {
    if (a.hasNext()) {
      out.append(a.next())
      if (b.hasNext()) {
        out.append(b.next())
      }
    }
  }
  println()
  println(out.toString())
}


