package io.exoquery.plugin.printing

import io.exoquery.fansi.Color.Green
import io.exoquery.fansi.Color.Red
import io.exoquery.plugin.fullName
import io.exoquery.plugin.safeName
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue


fun IrElement.render() =
  accept(RenderIrElementVisitorSimple(), null)

class RenderIrElementVisitorSimple(normalizeNames: Boolean = false, private val verboseErrorTypes: Boolean = true) :
  IrElementVisitor<String, Nothing?> {

  private val variableNameData = VariableNameData(normalizeNames)

  fun renderType(type: IrType) = type.renderTypeWithRenderer(this@RenderIrElementVisitorSimple, verboseErrorTypes)

  fun renderSymbolReference(symbol: IrSymbol) = symbol.renderReference()

  fun renderAsAnnotation(irAnnotation: IrConstructorCall): String =
    StringBuilder().also { it.renderAsAnnotation(irAnnotation, this, verboseErrorTypes) }.toString()

  private fun IrType.render(): String =
    this.renderTypeWithRenderer(this@RenderIrElementVisitorSimple, verboseErrorTypes)

  private fun IrSymbol.renderReference() =
    if (isBound)
      owner.accept(BoundSymbolReferenceRenderer(variableNameData, verboseErrorTypes), null)
    else
      "UNBOUND(${javaClass.simpleName})"

  private class BoundSymbolReferenceRenderer(
    private val variableNameData: VariableNameData,
    private val verboseErrorTypes: Boolean,
  ) : IrElementVisitor<String, Nothing?> {

    override fun visitElement(element: IrElement, data: Nothing?) = buildTrimEnd {
      append('{')
      append(element.javaClass.simpleName)
      append('}')
      if (element is IrDeclaration) {
        if (element is IrDeclarationWithName) {
          append(element.name)
          append(' ')
        }
        //renderDeclaredIn(element)
      }
    }

    override fun visitTypeParameter(declaration: IrTypeParameter, data: Nothing?): String =
      renderTypeParameter(declaration, null, verboseErrorTypes)

    override fun visitClass(declaration: IrClass, data: Nothing?) =
      renderClassWithRenderer(declaration, null, verboseErrorTypes)

    override fun visitEnumEntry(declaration: IrEnumEntry, data: Nothing?) =
      renderEnumEntry(declaration)

    override fun visitField(declaration: IrField, data: Nothing?) =
      renderField(declaration, null, verboseErrorTypes)

    override fun visitVariable(declaration: IrVariable, data: Nothing?) =
      buildTrimEnd {
        append("Var(${declaration.normalizedName(variableNameData)})", "")
      }

    override fun visitValueParameter(declaration: IrValueParameter, data: Nothing?) =
      buildTrimEnd {
        append("Param(${declaration.symbol.safeName.toString()})", "")
      }

    override fun visitFunction(declaration: IrFunction, data: Nothing?) =
      buildTrimEnd {
        append("Fun(${declaration.symbol.safeName.toString()})", "")
      }

    private fun StringBuilder.renderTypeParameters(declaration: IrTypeParametersContainer) {
      if (declaration.typeParameters.isNotEmpty()) {
        appendIterableWith(declaration.typeParameters, "<", ">", ", ") { typeParameter ->
          append(typeParameter.name.asString())
        }
        append(' ')
      }
    }

    override fun visitProperty(declaration: IrProperty, data: Nothing?) =
      buildTrimEnd {
        append(declaration.visibility)
        append(' ')
        append(declaration.modality.toString().toLowerCaseAsciiOnly())
        append(' ')

        append(declaration.name.asString())

        val getter = declaration.getter
        if (getter != null) {
          append(": ")
          append(getter.renderReturnType(null, verboseErrorTypes))
        } else declaration.backingField?.type?.let { type ->
          append(": ")
          append(type.renderTypeWithRenderer(null, verboseErrorTypes))
        }

        append(' ')
        append(declaration.renderPropertyFlags())
      }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty, data: Nothing?): String =
      buildTrimEnd {
        if (declaration.isVar) append("var ") else append("val ")
        append(declaration.name.asString())
        append(": ")
        append(declaration.type.renderTypeWithRenderer(null, verboseErrorTypes))
        append(" by (...)")
      }

    private fun StringBuilder.renderDeclaredIn(irDeclaration: IrDeclaration) {
      append("declared in ")
      renderParentOfReferencedDeclaration(irDeclaration)
    }

    private fun StringBuilder.renderParentOfReferencedDeclaration(declaration: IrDeclaration) {
      val parent = try {
        declaration.parent
      } catch (e: Exception) {
        append("<no parent>")
        return
      }
      when (parent) {
        is IrPackageFragment -> {
          val fqn = parent.packageFqName.asString()
          append(fqn.ifEmpty { "<root>" })
        }
        is IrDeclaration -> {
          renderParentOfReferencedDeclaration(parent)
          append('.')
          if (parent is IrDeclarationWithName) {
            append(parent.name)
          } else {
            renderElementNameFallback(parent)
          }
        }
        else ->
          renderElementNameFallback(parent)
      }
    }

    private fun StringBuilder.renderElementNameFallback(element: Any) {
      append('{')
      append(element.javaClass.simpleName)
      append('}')
    }
  }

  override fun visitElement(element: IrElement, data: Nothing?): String =
    "?[IrElement]? ${element::class.java.simpleName} $element"

  override fun visitDeclaration(declaration: IrDeclarationBase, data: Nothing?): String =
    "?[IrDeclarationBase]? ${declaration::class.java.simpleName} $declaration"

  override fun visitModuleFragment(declaration: IrModuleFragment, data: Nothing?): String =
    "[IrModuleFragment] name:${declaration.name}"

  override fun visitExternalPackageFragment(declaration: IrExternalPackageFragment, data: Nothing?): String =
    "[IrExternalPackageFragment] fqName:${declaration.packageFqName}"

  override fun visitFile(declaration: IrFile, data: Nothing?): String =
    "[IrFile] fqName:${declaration.packageFqName} fileName:${declaration.path}"

  override fun visitFunction(declaration: IrFunction, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrFunction] ${renderOriginIfNonTrivial()}"
    }

  override fun visitScript(declaration: IrScript, data: Nothing?) = "SCRIPT"

  override fun visitSimpleFunction(declaration: IrSimpleFunction, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrSimpleFunction] ${renderOriginIfNonTrivial()} " +
          "$name" +
          renderTypeParameters() +
          renderValueParameterTypes() +
          ": ${renderReturnType(this@RenderIrElementVisitorSimple, verboseErrorTypes)} " +
          renderSimpleFunctionFlags()
    }

  private fun IrFunction.renderValueParameterTypes(): String =
    ArrayList<String>().apply {
      addIfNotNull(dispatchReceiverParameter?.run { "\$this:${type.render()}" })
      addIfNotNull(extensionReceiverParameter?.run { "\$receiver:${type.render()}" })
      valueParameters.mapTo(this) { "${it.name}:${it.type.render()}" }
    }.joinToString(separator = ", ", prefix = "(", postfix = ")")

  override fun visitConstructor(declaration: IrConstructor, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrConstructor] ${renderOriginIfNonTrivial()}" +
          "visibility:$visibility " +
          renderTypeParameters() + " " +
          renderValueParameterTypes() + " " +
          "returnType:${renderReturnType(this@RenderIrElementVisitorSimple, verboseErrorTypes)} " +
          renderConstructorFlags()
    }

  override fun visitProperty(declaration: IrProperty, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrProperty] ${renderOriginIfNonTrivial()}" +
          "name:$name visibility:$visibility modality:$modality " +
          renderPropertyFlags()
    }

  override fun visitField(declaration: IrField, data: Nothing?): String =
    renderField(declaration, this, verboseErrorTypes)

  override fun visitClass(declaration: IrClass, data: Nothing?): String =
    renderClassWithRenderer(declaration, this, verboseErrorTypes)

  override fun visitVariable(declaration: IrVariable, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrVariable] ${renderOriginIfNonTrivial()}name:${normalizedName(variableNameData)} type:${type.render()} ${renderVariableFlags()}"
    }

  override fun visitEnumEntry(declaration: IrEnumEntry, data: Nothing?): String =
    renderEnumEntry(declaration)

  override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer, data: Nothing?): String =
    "[IrAnonymousInitializer] isStatic=${declaration.isStatic}"

  override fun visitTypeParameter(declaration: IrTypeParameter, data: Nothing?): String =
    renderTypeParameter(declaration, this, verboseErrorTypes)

  override fun visitValueParameter(declaration: IrValueParameter, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrValueParameter] ${renderOriginIfNonTrivial()}" +
          "name:$name " +
          (if (index >= 0) "index:$index " else "") +
          "type:${type.render()} " +
          (varargElementType?.let { "varargElementType:${it.render()} " } ?: "") +
          renderValueParameterFlags()
    }

  override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty, data: Nothing?): String =
    declaration.runTrimEnd {
      "[IrLocalDelegatedProperty] ${declaration.renderOriginIfNonTrivial()}" +
          "name:$name type:${type.render()} flags:${renderLocalDelegatedPropertyFlags()}"
    }

  override fun visitTypeAlias(declaration: IrTypeAlias, data: Nothing?): String =
    declaration.run {
      "[IrTypeAlias] ${declaration.renderOriginIfNonTrivial()}" +
          "name:$name visibility:$visibility expandedType:${expandedType.render()}" +
          renderTypeAliasFlags()
    }

  override fun visitExpressionBody(body: IrExpressionBody, data: Nothing?): String =
    "[IrExpressionBody]"

  override fun visitBlockBody(body: IrBlockBody, data: Nothing?): String =
    "[IrBlockBody]"

  override fun visitSyntheticBody(body: IrSyntheticBody, data: Nothing?): String =
    "[IrSyntheticBody] kind=${body.kind}"

  override fun visitExpression(expression: IrExpression, data: Nothing?): String =
    "[IrExpression] ${expression::class.java.simpleName} type=${expression.type.render()}"

  override fun visitConst(expression: IrConst, data: Nothing?): String =
    "[IrConst] ${expression.value?.escapeIfRequired()}: ${expression.type.render()}"

  private fun Any.escapeIfRequired() =
    when (this) {
      is String -> "\"${StringUtil.escapeStringCharacters(this)}\""
      is Char -> "'${StringUtil.escapeStringCharacters(this.toString())}'"
      else -> this
    }

  override fun visitVararg(expression: IrVararg, data: Nothing?): String =
    "[IrVararg] type=${expression.type.render()} varargElementType=${expression.varargElementType.render()}"

  override fun visitSpreadElement(spread: IrSpreadElement, data: Nothing?): String =
    "[IrSpreadElement]"

  override fun visitBlock(expression: IrBlock, data: Nothing?): String =
    "${if (expression is IrReturnableBlock) "[IrBlock]" else "[IrReturnableBlock]"} ${expression.type.render()} (orig=${expression.origin})"

  override fun visitComposite(expression: IrComposite, data: Nothing?): String =
    "[IrComposite] type=${expression.type.render()} origin=${expression.origin}"

  override fun visitReturn(expression: IrReturn, data: Nothing?): String =
    "[IrReturn] type=${expression.type.render()} from='${expression.returnTargetSymbol.renderReference()}'"

  fun printStackTrace(): String =
    RuntimeException().stackTraceToString()

  override fun visitCall(expression: IrCall, data: Nothing?): String {
    val reciever =
      expression.dispatchReceiver?.let { "dispatch=${it.type.classFqName?.asString()}" } ?: expression.extensionReceiver?.let { "extension=${it.type.classFqName?.asString()}" } ?: "<root:${expression.symbol.fullName}>"

    //return "[IrCall] ${expression.symbol.safeName} "
    // This doesn't seem to fail, only the Fansi thing!
    //println("============= HERE: " + pprint(expression))
    println("============= HERE: " + io.exoquery.pprint.Tree.Literal("foo"))

    return "${Green("[IrCall]")} ${Red(expression.symbol.renderReference())} - ${reciever}"
  }

  private fun IrCall.renderSuperQualifier(): String =
    superQualifierSymbol?.let { "superQualifier='${it.renderReference()}' " } ?: ""

  override fun visitConstructorCall(expression: IrConstructorCall, data: Nothing?): String =
    "[IrConstructorCall] ${expression.symbol.renderReference()}:${expression.type.render()} origin=${expression.origin}"

  override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: Nothing?): String =
    "[IrDelegatingConstructorCall] ${expression.symbol.renderReference()}"

  override fun visitEnumConstructorCall(expression: IrEnumConstructorCall, data: Nothing?): String =
    "[IrEnumConstructorCall] ${expression.symbol.renderReference()}"

  override fun visitInstanceInitializerCall(expression: IrInstanceInitializerCall, data: Nothing?): String =
    "[IrInstanceInitializerCall] classDescriptor='${expression.classSymbol.renderReference()}'"

  override fun visitGetValue(expression: IrGetValue, data: Nothing?): String =
    "[IrGetValue] ${expression.symbol.renderReference()} (orig=${expression.origin}) (param=${(expression.symbol.owner.origin)})" //:${expression.type.render()} (orig=${expression.origin})

  override fun visitSetValue(expression: IrSetValue, data: Nothing?): String =
    "[IrSetValue] ${expression.symbol.renderReference()}:${expression.type.render()} (orig=${expression.origin})"

  override fun visitGetField(expression: IrGetField, data: Nothing?): String =
    "[IrGetField] ${expression.symbol.renderReference()}:${expression.type.render()} (orig=${expression.origin})"

  override fun visitSetField(expression: IrSetField, data: Nothing?): String =
    "[IrSetField] ${expression.symbol.renderReference()}:${expression.type.render()} (orig=${expression.origin})"

  override fun visitGetObjectValue(expression: IrGetObjectValue, data: Nothing?): String =
    "[IrGetObjectValue] '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  override fun visitGetEnumValue(expression: IrGetEnumValue, data: Nothing?): String =
    "[IrGetEnumValue] '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  override fun visitStringConcatenation(expression: IrStringConcatenation, data: Nothing?): String =
    "[IrStringConcatenation] type=${expression.type.render()}"

  override fun visitTypeOperator(expression: IrTypeOperatorCall, data: Nothing?): String =
    "[IrTypeOperatorCall]] type=${expression.type.render()} operator=${expression.operator} typeOperand=${expression.typeOperand.render()}"

  override fun visitWhen(expression: IrWhen, data: Nothing?): String =
    "[IrWhen] type=${expression.type.render()} origin=${expression.origin}"

  override fun visitBranch(branch: IrBranch, data: Nothing?): String =
    "[IrBranch]"

  override fun visitWhileLoop(loop: IrWhileLoop, data: Nothing?): String =
    "WHILE label=${loop.label} origin=${loop.origin}"

  override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: Nothing?): String =
    "DO_WHILE label=${loop.label} origin=${loop.origin}"

  override fun visitBreak(jump: IrBreak, data: Nothing?): String =
    "BREAK label=${jump.label} loop.label=${jump.loop.label}"

  override fun visitContinue(jump: IrContinue, data: Nothing?): String =
    "CONTINUE label=${jump.label} loop.label=${jump.loop.label}"

  override fun visitThrow(expression: IrThrow, data: Nothing?): String =
    "THROW type=${expression.type.render()}"

  override fun visitFunctionReference(expression: IrFunctionReference, data: Nothing?): String =
    "FUNCTION_REFERENCE '${expression.symbol.renderReference()}' " +
        "type=${expression.type.render()} origin=${expression.origin} " +
        "reflectionTarget=${renderReflectionTarget(expression)}"

  override fun visitRawFunctionReference(expression: IrRawFunctionReference, data: Nothing?): String =
    "RAW_FUNCTION_REFERENCE '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  private fun renderReflectionTarget(expression: IrFunctionReference) =
    if (expression.symbol == expression.reflectionTarget)
      "<same>"
    else
      expression.reflectionTarget?.renderReference()

  override fun visitPropertyReference(expression: IrPropertyReference, data: Nothing?): String =
    buildTrimEnd {
      append("PROPERTY_REFERENCE ")
      append("'${expression.symbol.renderReference()}' ")
      appendNullableAttribute("field=", expression.field) { "'${it.renderReference()}'" }
      appendNullableAttribute("getter=", expression.getter) { "'${it.renderReference()}'" }
      appendNullableAttribute("setter=", expression.setter) { "'${it.renderReference()}'" }
      append("type=${expression.type.render()} ")
      append("origin=${expression.origin}")
    }

  private inline fun <T : Any> StringBuilder.appendNullableAttribute(prefix: String, value: T?, toString: (T) -> String) {
    append(prefix)
    if (value != null) {
      append(toString(value))
    } else {
      append("null")
    }
    append(" ")
  }

  override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference, data: Nothing?): String =
    buildTrimEnd {
      append("LOCAL_DELEGATED_PROPERTY_REFERENCE ")
      append("'${expression.symbol.renderReference()}' ")
      append("delegate='${expression.delegate.renderReference()}' ")
      append("getter='${expression.getter.renderReference()}' ")
      appendNullableAttribute("setter=", expression.setter) { "'${it.renderReference()}'" }
      append("type=${expression.type.render()} ")
      append("origin=${expression.origin}")
    }

  override fun visitFunctionExpression(expression: IrFunctionExpression, data: Nothing?): String =
    buildTrimEnd {
      append("[IrFunctionExpression] type=${expression.type.render()} origin=${expression.origin}")
    }

  override fun visitClassReference(expression: IrClassReference, data: Nothing?): String =
    "CLASS_REFERENCE '${expression.symbol.renderReference()}' type=${expression.type.render()}"

  override fun visitGetClass(expression: IrGetClass, data: Nothing?): String =
    "GET_CLASS type=${expression.type.render()}"

  override fun visitTry(aTry: IrTry, data: Nothing?): String =
    "TRY type=${aTry.type.render()}"

  override fun visitCatch(aCatch: IrCatch, data: Nothing?): String =
    "CATCH parameter=${aCatch.catchParameter.symbol.renderReference()}"

  override fun visitDynamicOperatorExpression(expression: IrDynamicOperatorExpression, data: Nothing?): String =
    "DYN_OP operator=${expression.operator} type=${expression.type.render()}"

  override fun visitDynamicMemberExpression(expression: IrDynamicMemberExpression, data: Nothing?): String =
    "DYN_MEMBER memberName='${expression.memberName}' type=${expression.type.render()}"

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  override fun visitErrorDeclaration(declaration: IrErrorDeclaration, data: Nothing?): String =
    "ERROR_DECL ${declaration.descriptor::class.java.simpleName} " +
        descriptorRendererForErrorDeclarations.renderDescriptor(declaration.descriptor.original)

  override fun visitErrorExpression(expression: IrErrorExpression, data: Nothing?): String =
    "ERROR_EXPR '${expression.description}' type=${expression.type.render()}"

  override fun visitErrorCallExpression(expression: IrErrorCallExpression, data: Nothing?): String =
    "ERROR_CALL '${expression.description}' type=${expression.type.render()}"

  override fun visitConstantArray(expression: IrConstantArray, data: Nothing?): String =
    "CONSTANT_ARRAY type=${expression.type.render()}"

  override fun visitConstantObject(expression: IrConstantObject, data: Nothing?): String =
    "CONSTANT_OBJECT type=${expression.type.render()} constructor=${expression.constructor.renderReference()}"

  override fun visitConstantPrimitive(expression: IrConstantPrimitive, data: Nothing?): String =
    "CONSTANT_PRIMITIVE type=${expression.type.render()}"


  private val descriptorRendererForErrorDeclarations = DescriptorRenderer.ONLY_NAMES_WITH_SHORT_TYPES
}

internal fun DescriptorRenderer.renderDescriptor(descriptor: DeclarationDescriptor): String =
  if (descriptor is ReceiverParameterDescriptor)
    "this@${descriptor.containingDeclaration.name}: ${descriptor.type}"
  else
    render(descriptor)

internal fun IrDeclaration.renderOriginIfNonTrivial(): String =
  if (origin != IrDeclarationOrigin.DEFINED) "(orig:$origin)" else ""

internal fun IrClassifierSymbol.renderClassifierFqn(): String =
  if (isBound)
    when (val owner = owner) {
      is IrClass -> owner.renderClassFqn()
      is IrScript -> owner.renderScriptFqn()
      is IrTypeParameter -> owner.renderTypeParameterFqn()
      else -> "`unexpected classifier: ${owner.render()}`"
    }
  else
    "<unbound ${this.javaClass.simpleName}>"

internal fun IrTypeAliasSymbol.renderTypeAliasFqn(): String =
  if (isBound)
    StringBuilder().also { owner.renderDeclarationFqn(it) }.toString()
  else
    "<unbound $this>"

internal fun IrClass.renderClassFqn(): String =
  StringBuilder().also { renderDeclarationFqn(it) }.toString()

internal fun IrScript.renderScriptFqn(): String =
  StringBuilder().also { renderDeclarationFqn(it) }.toString()

internal fun IrTypeParameter.renderTypeParameterFqn(): String =
  StringBuilder().also { sb ->
    sb.append(name.asString())
    sb.append(" of ")
    renderDeclarationParentFqn(sb)
  }.toString()

private fun IrDeclaration.renderDeclarationFqn(sb: StringBuilder) {
  renderDeclarationParentFqn(sb)
  sb.append('.')
  if (this is IrDeclarationWithName) {
    sb.append(name.asString())
  } else {
    sb.append(this)
  }
}

private fun IrDeclaration.renderDeclarationParentFqn(sb: StringBuilder) {
  try {
    val parent = this.parent
    if (parent is IrDeclaration) {
      parent.renderDeclarationFqn(sb)
    } else if (parent is IrPackageFragment) {
      sb.append(parent.packageFqName.toString())
    }
  } catch (e: UninitializedPropertyAccessException) {
    sb.append("<uninitialized parent>")
  }
}

fun IrType.render() = renderTypeWithRenderer(RenderIrElementVisitorSimple(), true)

fun IrSimpleType.render() = (this as IrType).render()

fun IrTypeArgument.render() =
  when (this) {
    is IrStarProjection -> "*"
    is IrTypeProjection -> "$variance ${type.render()}"
    else -> throw AssertionError("Unexpected IrTypeArgument: $this")
  }

internal inline fun <T, Buffer : Appendable> Buffer.appendIterableWith(
  iterable: Iterable<T>,
  prefix: String,
  postfix: String,
  separator: String,
  renderItem: Buffer.(T) -> Unit
) {
  append(prefix)
  var isFirst = true
  for (item in iterable) {
    if (!isFirst) append(separator)
    renderItem(item)
    isFirst = false
  }
  append(postfix)
}

private inline fun buildTrimEnd(fn: StringBuilder.() -> Unit): String =
  buildString(fn).trimEnd()

private inline fun <T> T.runTrimEnd(fn: T.() -> String): String =
  run(fn).trimEnd()

private fun renderFlagsList(vararg flags: String?) =
  flags.filterNotNull().run {
    if (isNotEmpty())
      joinToString(prefix = "[", postfix = "] ", separator = ",")
    else
      ""
  }

private fun IrClass.renderClassFlags() =
  renderFlagsList(
    "companion".takeIf { isCompanion },
    "inner".takeIf { isInner },
    "data".takeIf { isData },
    "external".takeIf { isExternal },
    "value".takeIf { isValue },
    "expect".takeIf { isExpect },
    "fun".takeIf { isFun }
  )

private fun IrField.renderFieldFlags() =
  renderFlagsList(
    "final".takeIf { isFinal },
    "external".takeIf { isExternal },
    "static".takeIf { isStatic },
  )

private fun IrSimpleFunction.renderSimpleFunctionFlags(): String =
  renderFlagsList(
    "tailrec".takeIf { isTailrec },
    "inline".takeIf { isInline },
    "external".takeIf { isExternal },
    "suspend".takeIf { isSuspend },
    "expect".takeIf { isExpect },
    "fake_override".takeIf { isFakeOverride },
    "operator".takeIf { isOperator },
    "infix".takeIf { isInfix }
  )

private fun IrConstructor.renderConstructorFlags() =
  renderFlagsList(
    "inline".takeIf { isInline },
    "external".takeIf { isExternal },
    "primary".takeIf { isPrimary },
    "expect".takeIf { isExpect }
  )

private fun IrProperty.renderPropertyFlags() =
  renderFlagsList(
    "external".takeIf { isExternal },
    "const".takeIf { isConst },
    "lateinit".takeIf { isLateinit },
    "delegated".takeIf { isDelegated },
    "expect".takeIf { isExpect },
    "fake_override".takeIf { isFakeOverride },
    if (isVar) "var" else "val"
  )

private fun IrVariable.renderVariableFlags(): String =
  renderFlagsList(
    "const".takeIf { isConst },
    "lateinit".takeIf { isLateinit },
    if (isVar) "var" else "val"
  )

private fun IrValueParameter.renderValueParameterFlags(): String =
  renderFlagsList(
    "vararg".takeIf { varargElementType != null },
    "crossinline".takeIf { isCrossinline },
    "noinline".takeIf { isNoinline },
    "assignable".takeIf { isAssignable }
  )

private fun IrTypeAlias.renderTypeAliasFlags(): String =
  renderFlagsList(
    "actual".takeIf { isActual }
  )

private fun IrFunction.renderTypeParameters(): String =
  if (typeParameters.isEmpty())
    ""
  else
    typeParameters.joinToString(separator = ", ", prefix = "<", postfix = ">") { it.name.toString() }

private val IrFunction.safeReturnType: IrType?
  get() = returnType

private fun IrLocalDelegatedProperty.renderLocalDelegatedPropertyFlags() =
  if (isVar) "var" else "val"

private class VariableNameData(val normalizeNames: Boolean) {
  val nameMap: MutableMap<IrVariableSymbol, String> = mutableMapOf()
  var temporaryIndex: Int = 0
}

private fun IrVariable.normalizedName(data: VariableNameData): String {
  if (data.normalizeNames && (origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE || origin == IrDeclarationOrigin.FOR_LOOP_ITERATOR)) {
    return data.nameMap.getOrPut(symbol) { "tmp_${data.temporaryIndex++}" }
  }
  return name.asString()
}

private fun IrFunction.renderReturnType(renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean): String =
  safeReturnType?.renderTypeWithRenderer(renderer, verboseErrorTypes) ?: "<Uninitialized>"

private fun IrType.renderTypeWithRenderer(renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean): String =
  "${renderTypeAnnotations(annotations, renderer, verboseErrorTypes)}${renderTypeInner(renderer, verboseErrorTypes)}"

private fun IrType.renderTypeInner(renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean) =
  when (this) {
    is IrDynamicType -> "dynamic"

    is IrErrorType -> "IrErrorType(${verboseErrorTypes.ifTrue { originalKotlinType }})"

    is IrSimpleType -> buildTrimEnd {
      val isDefinitelyNotNullType =
        classifier is IrTypeParameterSymbol && nullability == SimpleTypeNullability.DEFINITELY_NOT_NULL
      if (isDefinitelyNotNullType) append("{")
      append(classifier.renderClassifierFqn())
      if (arguments.isNotEmpty()) {
        append(
          arguments.joinToString(prefix = "<", postfix = ">", separator = ", ") {
            it.renderTypeArgument(renderer, verboseErrorTypes)
          }
        )
      }
      if (isDefinitelyNotNullType) {
        append(" & Any}")
      } else if (isMarkedNullable()) {
        append('?')
      }
      abbreviation?.let {
        append(it.renderTypeAbbreviation(renderer, verboseErrorTypes))
      }
    }

    else -> "{${javaClass.simpleName} $this}"
  }

private fun IrTypeAbbreviation.renderTypeAbbreviation(renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean): String =
  buildString {
    append("{ ")
    append(renderTypeAnnotations(annotations, renderer, verboseErrorTypes))
    append(typeAlias.renderTypeAliasFqn())
    if (arguments.isNotEmpty()) {
      append(
        arguments.joinToString(prefix = "<", postfix = ">", separator = ", ") {
          it.renderTypeArgument(renderer, verboseErrorTypes)
        }
      )
    }
    if (hasQuestionMark) {
      append('?')
    }
    append(" }")
  }

private fun IrTypeArgument.renderTypeArgument(renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean): String =
  when (this) {
    is IrStarProjection -> "*"

    is IrTypeProjection -> buildTrimEnd {
      append(variance.label)
      if (variance != Variance.INVARIANT) append(' ')
      append(type.renderTypeWithRenderer(renderer, verboseErrorTypes))
    }

    else -> "IrTypeArgument[$this]"
  }

private fun renderTypeAnnotations(annotations: List<IrConstructorCall>, renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean) =
  if (annotations.isEmpty())
    ""
  else
    buildString {
      appendIterableWith(annotations, prefix = "", postfix = " ", separator = " ") {
        append("@[")
        renderAsAnnotation(it, renderer, verboseErrorTypes)
        append("]")
      }
    }

private fun StringBuilder.renderAsAnnotation(
  irAnnotation: IrConstructorCall,
  renderer: RenderIrElementVisitorSimple?,
  verboseErrorTypes: Boolean,
) {
  val annotationClassName = irAnnotation.symbol.takeIf { it.isBound }?.owner?.parentAsClass?.name?.asString() ?: "<unbound>"
  append(annotationClassName)

  if (irAnnotation.typeArgumentsCount != 0) {
    (0 until irAnnotation.typeArgumentsCount).joinTo(this, ", ", "<", ">") { i ->
      irAnnotation.getTypeArgument(i)?.renderTypeWithRenderer(renderer, verboseErrorTypes) ?: "null"
    }
  }

  if (irAnnotation.valueArgumentsCount == 0) return

  val valueParameterNames = irAnnotation.getValueParameterNamesForDebug()

  appendIterableWith(0 until irAnnotation.valueArgumentsCount, separator = ", ", prefix = "(", postfix = ")") {
    append(valueParameterNames[it])
    append(" = ")
    renderAsAnnotationArgument(irAnnotation.getValueArgument(it), renderer, verboseErrorTypes)
  }
}

private fun StringBuilder.renderAsAnnotationArgument(irElement: IrElement?, renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean) {
  when (irElement) {
    null -> append("<null>")
    is IrConstructorCall -> renderAsAnnotation(irElement, renderer, verboseErrorTypes)
    is IrConst -> {
      append('\'')
      append(irElement.value.toString())
      append('\'')
    }
    is IrVararg -> {
      appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ") {
        renderAsAnnotationArgument(it, renderer, verboseErrorTypes)
      }
    }
    else -> if (renderer != null) {
      append(irElement.accept(renderer, null))
    } else {
      append("...")
    }
  }
}

private fun renderClassWithRenderer(declaration: IrClass, renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean) =
  declaration.runTrimEnd {
    "[IrClass] ${renderOriginIfNonTrivial()}" +
        "$kind name:$name modality:$modality visibility:$visibility " +
        renderClassFlags() +
        "superTypes:[${superTypes.joinToString(separator = "; ") { it.renderTypeWithRenderer(renderer, verboseErrorTypes) }}]"
  }

private fun renderEnumEntry(declaration: IrEnumEntry) = declaration.runTrimEnd {
  "[IrEnumEntry] ${renderOriginIfNonTrivial()}name:$name"
}

private fun renderField(declaration: IrField, renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean) = declaration.runTrimEnd {
  "[IrField] ${renderOriginIfNonTrivial()}name:$name type:${
    type.renderTypeWithRenderer(
      renderer,
      verboseErrorTypes
    )
  } visibility:$visibility ${renderFieldFlags()}"
}

private fun renderTypeParameter(declaration: IrTypeParameter, renderer: RenderIrElementVisitorSimple?, verboseErrorTypes: Boolean) =
  declaration.runTrimEnd {
    "[IrTypeParameter] ${renderOriginIfNonTrivial()}" +
        "name:$name index:$index variance:$variance " +
        "superTypes:[${
          superTypes.joinToString(separator = "; ") {
            it.renderTypeWithRenderer(
              renderer, verboseErrorTypes
            )
          }
        }] " +
        "reified:$isReified"
  }
