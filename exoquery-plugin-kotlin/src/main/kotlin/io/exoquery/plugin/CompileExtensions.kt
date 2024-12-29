package io.exoquery.plugin

import io.decomat.*
import io.decomat.fail.fail
import io.exoquery.annotation.*
import io.exoquery.plugin.transform.BuilderContext
import io.exoquery.plugin.transform.Caller
import io.exoquery.plugin.trees.Ir
import io.exoquery.plugin.trees.ParserContext
import io.exoquery.xr.XR
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.reflect.KClass

val KClass<*>.qualifiedNameForce get(): String =
  if (this.qualifiedName == null) fail("Qualified name of the class ${this} was null")
  else this.qualifiedName!!

val KClass<*>.fqNameForce get() =
  FqName(this.qualifiedNameForce)

sealed interface MethodType {
  data class Getter(val sym: IrSimpleFunctionSymbol): MethodType
  data class Method(val sym: IrSimpleFunctionSymbol): MethodType
}

fun IrType.findMethodOrFail(methodName: String): MethodType = run {
  val cls =
    (this.classOrNull ?: error("Cannot locate the method ${methodName} from the type: ${this.dumpKotlinLike()} type is not a class."))


  // This causes asserition failures? Not sure why
  //cls.getPropertyGetter(methodName)?.let { MethodType.Getter(it) }

  cls.functions.find { it.safeName == methodName }?.let { MethodType.Method(it) }
    ?: error(
     """|
        |Cannot locate the method ${methodName} from the type: ${this.dumpKotlinLike()} because the method does not exist.
        |-------------- Available methods --------------
        |${cls.functions.joinToString("\n") { it.safeName }}
        |""".trimMargin())


  // getPropertyGetter could potentially cause kotlin Assertion errors. Not sure why
  //|-------------- Available properties --------------
  //|${cls.dataClassProperties().map { cls.getPropertyGetter(it.first)?.safeName }.joinToString("\n")}
}

// WARNING assuming (for now) that the extension methods are in the same package as the Class they're being called from.
// can relax this assumption later by adding an optional package-field to ReplacementMethodToCall and propagating it here
// TODO Need to filter by reciever type i.e. what if there are multiple extension functions named the same thing
context(BuilderContext) fun IrType.findExtensionMethodOrFail(methodName: String) = run {
  (this
    .classOrNull ?: error("Cannot locate the method ${methodName} from the type: ${this.dumpKotlinLike()} type is not a class."))
    .let { classSym ->
      pluginCtx.referenceFunctions(CallableId(FqName(classSym.owner.packageFqName.toString()), Name.identifier(methodName))).firstOrNull()?.let { MethodType.Method(it) }
        ?: error("Cannot locate the extension method ${classSym.owner.packageFqName.toString()}.${methodName} from the type: ${this.dumpKotlinLike()} because the method does not exist.")
    }
}

fun IrClassSymbol.isDataClass() = this.owner.isData

fun IrClassSymbol.dataClassProperties() =
  if (this.isDataClass()) {
    // NOTE: Does not support data-classes with multiple constructors.
    // Constructor params are in the right order. The properties of the class are not.
    val constructorParams = this.constructors.firstOrNull()?.owner?.valueParameters ?: setOf()
    //this.owner.properties
    //  .filter { constructorParams.contains(it.name) && it.getter != null }
    //  .map { it.name.toString() to it.getter!!.returnType }
    constructorParams.map { param -> param.name.asString() to param.type }
  }
  else listOf()

val IrSymbol.safeName   get() =
  (if (owner is IrFunction && (owner as IrFunction).isPropertyAccessor) {
    (owner as IrFunction).name.asStringStripSpecialMarkers().removePrefix("get-")
  } else if (isBound) {
    (owner as? IrDeclarationWithName)?.name?.asString() ?: "<???>"
  } else {
    "<???>"
  }).replace("$", "")

fun IrElement.location(fileEntry: IrFileEntry): CompilerMessageSourceLocation {
  val irElement = this
  val sourceRangeInfo = fileEntry.getSourceRangeInfo(
    beginOffset = irElement.startOffset ?: UNDEFINED_OFFSET,
    endOffset = irElement.endOffset ?: UNDEFINED_OFFSET
  )
  val messageWithRange = CompilerMessageLocationWithRange.create(
    path = sourceRangeInfo.filePath,
    lineStart = sourceRangeInfo.startLineNumber + 1,
    columnStart = sourceRangeInfo.startColumnNumber + 1,
    lineEnd = sourceRangeInfo.endLineNumber + 1,
    columnEnd = sourceRangeInfo.endColumnNumber + 1,
    lineContent = null
  )!!
  return messageWithRange
}

fun CompilerMessageSourceLocation.show() =
  "${path}:${line}:${column}"

context(ParserContext) fun IrElement.location(): CompilerMessageSourceLocation =
  this.location(currentFile.fileEntry)

context(ParserContext) fun IrElement.locationXR(): XR.Location =
  this.location(currentFile.fileEntry).toLocationXR()

context(BuilderContext) fun IrElement.buildLocation(): CompilerMessageSourceLocation =
  this.location(currentFile.fileEntry)

context(BuilderContext) fun IrElement.buildLocationXR(): XR.Location =
  this.location(currentFile.fileEntry).toLocationXR()

fun CompilerMessageSourceLocation.toLocationXR(): XR.Location =
  XR.Location.File(path, line, column)


inline fun <reified T> IrExpression.isClass(): Boolean {
  val className = T::class.qualifiedNameForce
  return className == this.type.classFqName.toString() || type.superTypes().any { it.classFqName.toString() == className }
}

inline fun <reified T> classIdOf(): ClassId {
  val className = T::class.qualifiedNameForce
  return ClassId.topLevel(FqName(className))
}

inline fun <reified T> IrType.isClass(): Boolean {
  val className = T::class.qualifiedNameForce
  return className == this.classFqName.toString() || this.superTypes().any { it.classFqName.toString() == className }
}

inline fun <reified T> IrCall.reciverIs() =
  this.dispatchReceiver?.isClass<T>() ?: false

inline fun <reified T> IrCall.reciverIs(methodName: String) =
  this.dispatchReceiver?.isClass<T>() ?: false && this.symbol.safeName == methodName

data class ReplacementMethodToCall(val methodToCall: String, val callerType: ChangeReciever = ChangeReciever.DoNothing) {
  companion object {
    fun from(call: IrConstructorCall) =
      call.getValueArgument(0)?.let { firstArg ->
        if (firstArg is IrConst<*> && firstArg.kind == IrConstKind.String) {
          val secondArg: ChangeReciever =
            call.getValueArgument(1)?.let { secondArg ->
              secondArg.match(
                case(Ir.GetEnumValue[Is()]).then { it.safeName }
              )
            }?.let { secondArgValue ->
              ChangeReciever.valueOf(secondArgValue)
            }
            ?: ChangeReciever.DoNothing

          ReplacementMethodToCall(firstArg.value as String, secondArg)
        } else
          null
      }
  }
}


fun IrCall.caller() =
  this.extensionReceiver?.let {
    Caller.Extension(it)
  } ?:
  this.dispatchReceiver?.let {
    Caller.Dispatch(it)
  }
