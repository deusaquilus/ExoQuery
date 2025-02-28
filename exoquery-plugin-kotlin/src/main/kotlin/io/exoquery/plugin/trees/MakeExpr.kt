package io.exoquery.plugin.trees

import io.exoquery.liftingError
import io.exoquery.parseError
import io.exoquery.plugin.classId
import io.exoquery.plugin.transform.CX
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

context(CX.Scope)
fun KType.fullPathOfBasic(): ClassId =
  when(val cls = this.classifier) {
    is KClass<*> -> cls.classId() ?: parseError("Could not find the class id for the class: $cls")
    else -> parseError("Invalid list class: $cls")
  }

// TODO this can probably make both objects and types if we check the attributes of the reified type T
//      should look into that
context(CX.Scope, CX.Builder) inline fun <reified T> make(vararg args: IrExpression): IrConstructorCall {
  val fullPath = typeOf<T>().fullPathOfBasic()
  return makeClassFromId(fullPath, args.toList())
}

context(CX.Scope, CX.Builder) inline fun <reified T> makeWithTypes(types: List<IrType>, args: List<IrExpression>): IrConstructorCall {
  val fullPath = typeOf<T>().fullPathOfBasic()
  return makeClassFromId(fullPath, args.toList(), types.toList())
}

context(CX.Scope, CX.Builder) inline fun <reified T> makeObject(): IrGetObjectValue {
  val fullPath = typeOf<T>().fullPathOfBasic()
  return makeObjectFromId(fullPath)
}


context(CX.Scope, CX.Builder) fun makeClassFromString(fullPath: String, args: List<IrExpression>, types: List<IrType> = listOf(), overrideType: IrType? = null) =
  makeClassFromId(ClassId.topLevel(FqName(fullPath)), args, types, overrideType)

context(CX.Scope, CX.Builder) fun makeClassFromId(fullPath: ClassId, args: List<IrExpression>, types: List<IrType> = listOf(), overrideType: IrType? = null) =
  (pluginCtx.referenceConstructors(fullPath).firstOrNull() ?: liftingError("Could not find a constructor for a class in the context: $fullPath"))
    .let { ctor ->
      overrideType?.let { builder.irCall(ctor, it) } ?: builder.irCall(ctor)
    }
    .also { ctorCall ->
      args.withIndex().map { (i, arg) ->
        ctorCall.putValueArgument(i, arg)
      }
      types.withIndex().map { (i, arg) ->
        ctorCall.putTypeArgument(i, arg)
      }
    }

context(CX.Scope, CX.Builder) fun makeObjectFromId(id: ClassId): IrGetObjectValue {
  val clsSym = pluginCtx.referenceClass(id) ?: throw RuntimeException("Could not find the reference for a class in the context: $id")
  val tpe = clsSym.owner.defaultType
  return builder.irGetObjectValue(tpe, clsSym)
}
