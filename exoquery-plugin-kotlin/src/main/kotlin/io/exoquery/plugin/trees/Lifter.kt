package io.exoquery.plugin.trees

import io.exoquery.EntityExpression
import io.exoquery.Expression
import io.exoquery.Lambda1Expression
import io.exoquery.SqlVariable
import io.exoquery.annotation.ExoInternal
import io.exoquery.xr.XR.*
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.xr.*
import io.exoquery.xr.*
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder

import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class Lifter(val irBuilder: DeclarationIrBuilder, val context: IrPluginContext, val logger: CompileLogger) {
  val pack = "io.exoquery.xr"

  fun KType.fullPathOfBasic() =
    when(val cls = this.classifier) {
      is KClass<*> -> cls.qualifiedName ?: throw RuntimeException("Qualified name of class $cls was null")
      else -> throw RuntimeException("Invalid list class: $cls")
    }

  // TODO this can probably make both objects and types if we check the attributes of the reified type T
  //      should look into that
  private inline fun <reified T> make(vararg args: IrExpression): IrConstructorCall {
    val fullPath = typeOf<T>().fullPathOfBasic()
    return makeClassFromString(fullPath, args.toList())
  }

  private inline fun <reified T> makeWithTypes(types: List<IrType>, args: List<IrExpression>): IrConstructorCall {
    val fullPath = typeOf<T>().fullPathOfBasic()
    return makeClassFromString(fullPath, args.toList(), types.toList())
  }

  inline fun <reified T> makeObject(): IrGetObjectValue {
    val fullPath = typeOf<T>().fullPathOfBasic()
    return makeObjectFromString(fullPath)
  }


  private fun makeClassFromString(fullPath: String, args: List<IrExpression>, types: List<IrType> = listOf()) =
    context.referenceConstructors(ClassId.topLevel(FqName("$fullPath"))).first()
      .let { ctor -> irBuilder.irCall(ctor) }
      .also { ctorCall ->
        args.withIndex().map { (i, arg) ->
          ctorCall.putValueArgument(i, arg)
        }
        types.withIndex().map { (i, arg) ->
          ctorCall.putTypeArgument(i, arg)
        }
      }

  fun makeObjectFromString(fullPath: String): IrGetObjectValue {
    val cls = ClassId.topLevel(FqName("$fullPath"))
    val clsSym = context.referenceClass(cls) ?: throw RuntimeException("Could not find the reference for the class $cls in the context")
    val tpe = clsSym.owner.defaultType
    return irBuilder.irGetObjectValue(tpe, clsSym)
  }
    //irBuilder.irGetObjectValue()
    //irBuilder.irGetObjectValue(IrType)

//  fun blah() {
//    context.referenceConstructors()
//  }


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
    val fullPath = typeOf<T>().fullPathOfBasic()
    val classId = ClassId.topLevel(FqName(fullPath))
    val expressionType = context.referenceConstructors(classId).first().owner.returnType
    val expressions = this.map { elementLifter(it) }
    val variadics = irBuilder.irVararg(expressionType, expressions)
    val listOfCall = irBuilder.irCall(listOfRef).apply { putValueArgument(0, variadics) }
    return listOfCall
  }

  fun Const.lift(): IrExpression =
    when (this) {
      is Const.Boolean -> make<Const.Boolean>(this.component1().lift())
      is Const.Byte -> make<Const.Byte>(this.component1().lift())
      is Const.Char -> make<Const.Char>(this.component1().lift())
      is Const.Double -> make<Const.Double>(this.component1().lift())
      is Const.Float -> make<Const.Float>(this.component1().lift())
      is Const.Int -> make<Const.Int>(this.component1().lift())
      is Const.Long -> make<Const.Long>(this.component1().lift())
      is Const.Short -> make<Const.Short>(this.component1().lift())
      is Const.String -> make<Const.String>(this.component1().lift())
      Const.Null -> makeObject<Const.Null>() // Does this actually work?
    }

  fun XR.lift(): IrExpression =
    when (this) {
      is BinaryOp -> make<BinaryOp>(this.component1().lift(), this.component2().lift(), this.component3().lift())
      is Ident -> make<Ident>(this.component1().lift(), this.component2().lift())
      is Const -> this.lift() // points to the Const.lift() function above
      is Property -> make<Property>(this.component1().lift(), this.component2().lift())
      is Block -> make<Block>(this.component1().lift { it.lift() }, this.component2().lift())
      is When -> make<When>(this.component1().lift { it.lift() }, this.component2().lift())
      is Branch -> make<Branch>(this.component1().lift(), this.component2().lift())
      is Variable -> make<Variable>(this.component1().lift(), this.component2().lift())
      is Function1 -> make<Function1>(this.component1().lift(), this.component2().lift())
      is Entity -> make<Entity>(this.component1().lift(), this.component2().lift())
      is FlatJoin -> make<FlatJoin>(this.component1().lift(), this.component2().lift(), this.component3().lift(), this.component4().lift())
      is FlatMap -> make<FlatMap>(this.component1().lift(), this.component2().lift(), this.component3().lift())
      is XR.Map -> make<XR.Map>(this.component1().lift(), this.component2().lift(), this.component3().lift())
      is UnaryOp -> make<UnaryOp>(this.component1().lift(), this.component2().lift())
      is FunctionN -> make<FunctionN>(this.component1().lift { it.lift() }, this.component2().lift())
      is Filter -> make<Filter>(this.component1().lift(), this.component2().lift(), this.component3().lift())
      is Marker -> make<Marker>(this.component1().lift())
      is Nested -> make<Nested>(this.component1().lift())
    }

  fun XR.JoinType.lift(): IrExpression =
    when (this) {
      is XR.JoinType.Inner -> makeObject<XR.JoinType.Inner>()
      is XR.JoinType.Left -> makeObject<XR.JoinType.Left>()
    }

  fun <A, B> Pair<A, B>.lift(aLifter: (A) -> IrExpression, bLifter: (B) -> IrExpression): IrExpression =
    make<Pair<A, B>>(aLifter(this.first), bLifter(this.second))

  fun XRType.lift(): IrExpression =
    when (this) {
      is XRType.Product -> make<XRType.Product>(this.component1().lift(), this.component2().lift { it.lift({it.lift()}, {it.lift()}) })
      XRType.BooleanExpression -> makeObject<XRType.BooleanExpression>()
      XRType.BooleanValue -> makeObject<XRType.BooleanValue>()
      XRType.Value -> makeObject<XRType.Value>()
      XRType.Unknown -> makeObject<XRType.Unknown>()
      XRType.Generic -> makeObject<XRType.Generic>()
    }



  fun liftXR(xr: XR) = xr.lift()
  fun liftXRType(xrt: XRType) = xrt.lift()
  fun liftExpression(expr: Expression) = expr.lift()

  // TODO use from the flatMap lifter
  @OptIn(ExoInternal::class)
  fun liftSqlVariableWithType(variable: SqlVariable<*>, typeParam: IrType) =
    makeWithTypes<SqlVariable<*>>(listOf(typeParam), listOf(variable.getVariableName().lift()))

  fun Operator.lift(): IrExpression =
    when (this) {
      EqualityOperator.`==` -> makeObject<EqualityOperator.`==`>()
      EqualityOperator.`!=` -> makeObject<EqualityOperator.`!=`>()
      AggregationOperator.avg -> makeObject<AggregationOperator.avg>()
      AggregationOperator.max -> makeObject<AggregationOperator.max>()
      AggregationOperator.min -> makeObject<AggregationOperator.min>()
      AggregationOperator.size -> makeObject<AggregationOperator.size>()
      AggregationOperator.sum -> makeObject<AggregationOperator.sum>()
      StringOperator.`+` -> makeObject<StringOperator.`+`>()
      BooleanOperator.and -> makeObject<BooleanOperator.and>()
      SetOperator.contains -> makeObject<SetOperator.contains>()
      NumericOperator.div -> makeObject<NumericOperator.div>()
      NumericOperator.gt -> makeObject<NumericOperator.gt>()
      NumericOperator.gte -> makeObject<NumericOperator.gte>()
      NumericOperator.lt -> makeObject<NumericOperator.lt>()
      NumericOperator.lte -> makeObject<NumericOperator.lte>()
      NumericOperator.minus -> makeObject<NumericOperator.minus>()
      NumericOperator.mod -> makeObject<NumericOperator.mod>()
      NumericOperator.mult -> makeObject<NumericOperator.mult>()
      BooleanOperator.or -> makeObject<BooleanOperator.or>()
      NumericOperator.plus -> makeObject<NumericOperator.plus>()
      StringOperator.split -> makeObject<StringOperator.split>()
      StringOperator.startsWith -> makeObject<StringOperator.startsWith>()
      SetOperator.isEmpty -> makeObject<SetOperator.isEmpty>()
      SetOperator.nonEmpty -> makeObject<SetOperator.nonEmpty>()
      StringOperator.toInt -> makeObject<StringOperator.toInt>()
      StringOperator.toLong -> makeObject<StringOperator.toLong>()
      StringOperator.toLowerCase -> makeObject<StringOperator.toLowerCase>()
      StringOperator.toUpperCase -> makeObject<StringOperator.toUpperCase>()
      BooleanOperator.not -> makeObject<BooleanOperator.not>()
    }

  fun Expression.lift(): IrExpression =
    when (this) {
      is Lambda1Expression -> make<Lambda1Expression>(this.component1().lift())
      is EntityExpression -> make<EntityExpression>(this.component1().lift())
      else -> error("Error parsing expression: ${this}")
    }
}
