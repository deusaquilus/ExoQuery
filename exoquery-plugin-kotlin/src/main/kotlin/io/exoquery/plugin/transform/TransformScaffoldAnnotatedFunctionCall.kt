package io.exoquery.plugin.transform

import io.exoquery.annotation.CapturedFunction
import io.exoquery.parseError
import io.exoquery.plugin.hasAnnotation
import io.exoquery.plugin.trees.PT.io_exoquery_util_scaffoldCapFunctionQuery
import io.exoquery.plugin.trees.simpleValueArgs
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable


context(CX.Scope, CX.Builder)
fun IrCall.zeroisedArgs(): IrCall {
  val call = this
  return with(builder) {
    val newCall = irCall(call.symbol)

    // The dispatch-reciever to a annotated function remains, the extension reciever gets dropped and used like a variable
    call.dispatchReceiver?.let { newCall.dispatchReceiver = it }

    newCall.typeArguments.withIndex().forEach { (i, tpe) -> putTypeArgument(i, tpe) }
    // no value arguments, should not have any since they are added to the scaffolding
    newCall
  }
}

context(CX.Scope, CX.Builder)
fun buildScaffolding(zeroisedCall: IrExpression, scaffoldType: IrType, originalArgs: List<IrExpression?>): IrExpression {
  var argsAsVararg = builder.irVararg(pluginCtx.symbols.any.defaultType.makeNullable(), originalArgs.map { it ?: builder.irNull() })
  val args = listOf(zeroisedCall) + argsAsVararg
  return callWithParamsAndOutput(io_exoquery_util_scaffoldCapFunctionQuery, listOf(scaffoldType), scaffoldType).invoke(*args.toTypedArray())
}


class TransformScaffoldAnnotatedFunctionCall(val superTransformer: VisitTransformExpressions) : Transformer<IrCall>() {
  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  override fun matches(call: IrCall): Boolean =
    call.symbol.owner.hasAnnotation<CapturedFunction>()


  context(CX.Scope, CX.Builder, CX.Symbology, CX.QueryAccum)
  override fun transform(call: IrCall): IrExpression {
    val originalArgs = call.simpleValueArgs
    val zeroizedCallRaw = call.zeroisedArgs()

    // Need to project the call to make it uprootable in the paresr in later stages.
    // For example
    // @CapturedFunction fun joes(people: SqlQuery<Person>) = capture { people.filter { p -> p.name == "Joe" } }
    // IN the TransformAnnotatedFunction turns into:
    //     fun joes() = SqlQuery(xr = XR.Function(args=[people], body=(people.filter { p -> p.name == "Joe" })))
    //   Or more simply put:
    //     fun joes() = SqlQuery(xr=(people)=>people.filter { p -> p.name == "Joe" })
    //   Or more simply put
    //     fun joes() = SqlQuery((people)=>people.filterJoe)
    // Then when the function is called like so:
    //      val drivingJoes = captured { joes(Table<Person>().filter { p -> p.age > 18 }) }
    //    Or more simply put:
    //      val drivingJoes = captured { joes(People.filterAge) }     // a.k.a. SqlQuery(xr=joes(People.filterAge))
    // The call `joes(People.filterAge)` first needs to be "zeroed out" into just `joes()`
    // Then, if `joes` it is uprootable it needs to be projected into:
    //     SqlQuery((people)=>people.filterJoe <- i.e. `joes`)      // #Projected Stage
    //   (Note that if joes() had any parameters this would be:)
    //     SqlQuery((people)=>people.filterJoe, params=joes().params)
    // Then when actually applied (later in the parser when processing drivingJoes) it will should like this:
    //     val drivingJoes = SqlQuery(Apply((people)=>people.filterJoe, listOf(People.filterAge)))   // with a possible parameter: params=joes().params
    // The only problem is, how do we still know at that point that the argument is People.filterAge?
    // we see above in #ProjectedStage that this information was dropped when we dropped the arguments of `joes`.
    // That means we need to introduce some "scaffolding to keep this information i.e. return the following:
    //   val drivingJoes = scaffoldCapFunctionQuery(SqlQuery((people)=>people.filterJoe <- i.e. `joes`), args=[People.filterAge])
    // Then finally in the parser when we process `drivingJoes` we can see that the argument was `People.filterAge` and create the expression:
    //   val drivingJoes = SqlQuery(Apply((people)=>people.filterJoe, listOf(People.filterAge)))

    // Also note that if joes() is not uprootable then it will be pluckable and the call will look like this:
    //   val drivingJoes = scaffoldCapFunctionQuery(joes, args=[People.filterAge])
    // And the parser will know that `joes` is a pluckable function and create the following:
    //   val drivingJoes = SqlQuery(Apply(Tag(123), listOf(People.filterAge)), runtimes={Tag(123)->joes})

    val zeroizedCall =
      TransformProjectCapture(superTransformer).transform(zeroizedCallRaw) ?: parseError("Could not capture-project the call", zeroizedCallRaw)


    // Note that the one case that we haven't considered aboive is where the argument to the function call i.e. People.filterAge
    // itself is a uprootable variable for example:
    //   val drivingPeople = captured { Table<Person>().filter { p -> p.age > 18 } }    // a.k.a. SqlQuery(xr=People.filterAge)
    //   @CapturedFunction fun joes(people: SqlQuery<Person>) = capture { people.filter { p -> p.name == "Joe" } }
    //   val drivingJoes = captured { joes(drivingPeople) }
    // IN that situation the scaffolding would look like so:
    //   val drivingJoes = scaffoldCapFunctionQuery(SqlQuery((people)=>people.filterJoe <- i.e. `joes`), args=[drivingPeople] <- NOTE Here!)
    // That means that the argument drivingPeople itself needs to be projected into a uprootable form.
    // So again, we call TransformProjectCapture on the arguments to the function call so that the scaffolding becomes:
    //   val drivingJoes = scaffoldCapFunctionQuery(SqlQuery((people)=>people.filterJoe <- i.e. `joes`), args=[SqlQuery(xr=People.filterAge)])
    //   (and if there are any parameters it it the argument becomes:
    //    args=[SqlQuery(xr=People.filterAge), params=drivingPeople.params])
    val projectedArgs = originalArgs.map { arg -> arg?.let { superTransformer.recurse(it) ?: it } }

    //val zeroizedCall = zeroizedCallRaw as IrCall

    val scaffoldedCall = buildScaffolding(zeroizedCall, call.type, projectedArgs)
    //throw IllegalStateException("------------------- Scaffolding ------------------\n${scaffoldedCall.dumpKotlinLike()}")

    //error("""
    //  |--------------------------- Scaffolded call: ---------------------------
    //  |${scaffoldedCall.dumpKotlinLike()}
    //""".trimMargin())

    return scaffoldedCall
  }

}
