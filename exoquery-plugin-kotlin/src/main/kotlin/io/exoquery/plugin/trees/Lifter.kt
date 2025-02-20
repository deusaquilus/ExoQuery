package io.exoquery.plugin.trees

import io.exoquery.*
import io.exoquery.plugin.transform.BuilderContext
import io.exoquery.plugin.transform.call
import io.exoquery.plugin.transform.callDispatch
import io.exoquery.sql.ParamMultiToken
import io.exoquery.sql.ParamMultiTokenRealized
import io.exoquery.sql.ParamSingleToken
import io.exoquery.sql.ParamSingleTokenRealized
import io.exoquery.sql.SetContainsToken
import io.exoquery.sql.Statement
import io.exoquery.sql.StringToken
import io.exoquery.sql.Token
import io.exoquery.util.TraceConfig
import io.exoquery.util.TraceType
//import io.exoquery.xr.XR.Query

import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.reflect.typeOf

class Lifter(val builderCtx: BuilderContext) {
  val irBuilder = builderCtx.builder
  val context = builderCtx.pluginCtx


  inline fun <reified T> make(vararg args: IrExpression): IrConstructorCall = with(builderCtx) { io.exoquery.plugin.trees.make<T>(*args) }
  inline fun <reified T> makeObject(): IrGetObjectValue = with(builderCtx) { io.exoquery.plugin.trees.makeObject<T>() }
  fun makeClassFromId(id: ClassId, args: List<IrExpression>, types: List<IrType> = listOf()) =
    with(builderCtx) { io.exoquery.plugin.trees.makeClassFromId(id, args, types) }
  fun makeObjectFromId(id: ClassId): IrGetObjectValue =
    with(builderCtx) { io.exoquery.plugin.trees.makeObjectFromId(id) }
  inline fun <reified T> makeWithTypes(types: List<IrType>, args: List<IrExpression>): IrConstructorCall =
    with(builderCtx) { io.exoquery.plugin.trees.makeWithTypes<T>(types, args) }

  fun Boolean.lift(): IrExpression = irBuilder.irBoolean(this)
  fun Byte.lift(): IrExpression = IrConstImpl.byte(irBuilder.startOffset, irBuilder.endOffset, context.irBuiltIns.byteType, this)
  fun Char.lift(): IrExpression = irBuilder.irChar(this)
  fun Double.lift(): IrExpression = IrConstImpl.double(irBuilder.startOffset, irBuilder.endOffset, context.irBuiltIns.doubleType, this)
  fun Float.lift(): IrExpression = IrConstImpl.float(irBuilder.startOffset, irBuilder.endOffset, context.irBuiltIns.floatType, this)
  fun Int.lift(): IrExpression = irBuilder.irInt(this)
  fun Long.lift(): IrExpression = irBuilder.irLong(this)
  fun Short.lift(): IrExpression = IrConstImpl.short(irBuilder.startOffset, irBuilder.endOffset, context.irBuiltIns.shortType, this)
  fun String.lift(): IrExpression = irBuilder.irString(this)

  val listOfRef =
    context.referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf") ))
      // Get the 1st variadic instance of listOf (note that some variations have zero args so need to do firstOrNull)
      .first { it.owner.valueParameters.firstOrNull()?.isVararg ?: false }

  inline fun <reified T> List<T>.lift(elementLifter: (T) -> IrExpression): IrExpression {
    val elementType = typeOf<T>()
    if (elementType == typeOf<IrExpression>()) {
      // the input arg is already an expression. At this point it's more of a mapper than a lifter
      // (because the user could still call a map function and modify the expression in some way)
      // so at that point just do the map and call it
      val exprs = this.map(elementLifter)
      return exprs.liftExpr<Any>()
    } else {
      // We go throught the bruhaha of finding the constructor for the element type because we need to know it in order to create the varags list i.e. the `...` argument to `listOf(...)`
      // otherwise we could just call the list constructor and ignore the element type and rely on the compiler's type inference
      val classId = with(builderCtx) { elementType.fullPathOfBasic() }
      val classSymbol = builderCtx.pluginCtx.referenceClass(classId) ?: throw IllegalStateException("Cannot find a class for: ${classId} for the element type: ${elementType}")
      val varargType = classSymbol.owner.defaultType

      // Another way to get the IrType from the FqName is to get the constructor of the class. However it's not possible to do this if the type here is an interface or abstract class so not using this method
      //val expressionType = context.referenceConstructors(classId).firstOrNull()?.owner?.returnType ?: throw IllegalStateException("Cannot find a constructor for: ${classId} for the element type: ${elementType}")

      val expressions = this.map { elementLifter(it) }
      val variadics = irBuilder.irVararg(varargType, expressions)
      val listOfCall = irBuilder.irCall(listOfRef).apply { putValueArgument(0, variadics) }
      return listOfCall
    }
  }

  inline fun <reified T> List<IrExpression>.liftExpr(): IrExpression {
    val elementType = typeOf<T>()
    val classId = with(builderCtx) { elementType.fullPathOfBasic() }
    val expressionType = context.referenceConstructors(classId).firstOrNull()?.owner?.returnType ?: throw IllegalStateException("Cannot find a constructor for: ${classId} for the element type: ${elementType}")
    val variadics = irBuilder.irVararg(expressionType, this)
    //builderCtx.logger.error("--------------- Expression Type -------------: ${expressionType.dumpKotlinLike()}")
    val listOfCall = irBuilder.irCall(listOfRef, context.symbols.list.typeWith(expressionType)).apply {
      putTypeArgument(0, expressionType)
      putValueArgument(0, variadics)
    }
    //builderCtx.logger.error("--------------- List Expression Type -------------: ${listOfCall.type.dumpKotlinLike()}\n=== ${builderCtx.currentFile.path} ===")
    return listOfCall
  }

  fun List<IrExpression>.liftExprTyped(elementType: IrType): IrExpression {
    val variadics = irBuilder.irVararg(elementType, this)
    val listOfCall = irBuilder.irCall(listOfRef, context.symbols.list.typeWith(elementType)).apply {
      putTypeArgument(0, elementType)
      putValueArgument(0, variadics)
    }
    return listOfCall
  }

  fun TraceType.lift(): IrExpression =
    when (this) {
      TraceType.Warning -> makeObject<TraceType.Warning>()
      TraceType.ApplyMap -> makeObject<TraceType.ApplyMap>()
      TraceType.AvoidAliasConflict -> makeObject<TraceType.AvoidAliasConflict>()
      TraceType.DynamicExecution -> makeObject<TraceType.DynamicExecution>()
      TraceType.Elaboration -> makeObject<TraceType.Elaboration>()
      TraceType.Execution -> makeObject<TraceType.Execution>()
      TraceType.ExpandDistinct -> makeObject<TraceType.ExpandDistinct>()
      TraceType.ExprModel -> makeObject<TraceType.ExprModel>()
      TraceType.FlattenOptionOperation -> makeObject<TraceType.FlattenOptionOperation>()
      TraceType.Meta -> makeObject<TraceType.Meta>()
      TraceType.NestedQueryExpansion -> makeObject<TraceType.NestedQueryExpansion>()
      TraceType.Normalizations -> makeObject<TraceType.Normalizations>()
      TraceType.Particularization -> makeObject<TraceType.Particularization>()
      TraceType.PatMatch -> makeObject<TraceType.PatMatch>()
      TraceType.Quotation -> makeObject<TraceType.Quotation>()
      TraceType.ReifyLiftings -> makeObject<TraceType.ReifyLiftings>()
      TraceType.RenameProperties -> makeObject<TraceType.RenameProperties>()
      TraceType.RepropagateTypes -> makeObject<TraceType.RepropagateTypes>()
      TraceType.ShealthLeaf -> makeObject<TraceType.ShealthLeaf>()
      TraceType.SqlNormalizations -> makeObject<TraceType.SqlNormalizations>()
      TraceType.SqlQueryConstruct -> makeObject<TraceType.SqlQueryConstruct>()
      TraceType.Standard -> makeObject<TraceType.Standard>()
    }

  fun TraceConfig.lift(fileSinkOutputPath: String): IrExpression {
    val liftOutputSink =
      with (builderCtx) {
        call("io.exoquery.util.defaultTraceOutputSink")(fileSinkOutputPath.lift())
      }
    return make<TraceConfig>(this.enabledTraces.lift { it.lift() }, liftOutputSink)
  }

  fun <A, B> Pair<A, B>.lift(aLifter: (A) -> IrExpression, bLifter: (B) -> IrExpression): IrExpression =
    make<Pair<A, B>>(aLifter(this.first), bLifter(this.second))

  fun BID.lift(): IrExpression =
    make<BID>(irBuilder.irString(this.component1()))

  fun Token.lift(paramSetExpr: IrExpression): IrExpression =
    when (this) {
      is ParamSingleToken -> {
        with (builderCtx) {
          make<ParamSingleToken>(this@lift.bid.lift()).callDispatch("realize")(paramSetExpr)
        }
      }
      is ParamMultiToken -> {
        with (builderCtx) {
          make<ParamMultiToken>(this@lift.bid.lift()).callDispatch("realize")(paramSetExpr)
        }
      }
      is ParamSingleTokenRealized -> xrError("Attempting to lift an already realized ParamSingle. This should be impossible: ${this}")
      is ParamMultiTokenRealized -> xrError("Attempting to lift an already realized ParamMulti. This should be impossible: ${this}")
      is SetContainsToken -> make<SetContainsToken>(this.a.lift(paramSetExpr), this.op.lift(paramSetExpr), this.b.lift(paramSetExpr))
      is Statement -> make<Statement>(this.tokens.lift { it.lift(paramSetExpr) })
      is StringToken -> make<Token>(string.lift())
    }
}

// Some top-level lift functions to use outside of the lifter
