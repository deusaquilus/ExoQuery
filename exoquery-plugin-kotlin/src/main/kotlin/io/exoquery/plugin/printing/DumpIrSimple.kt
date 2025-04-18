package io.exoquery.plugin.printing

import io.exoquery.plugin.safeName
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.utils.Printer

fun IrElement.dumpSimple(normalizeNames: Boolean = false, stableOrder: Boolean = false): String =
  try {
    StringBuilder().also { sb ->
      accept(DumpIrTreeVisitor(sb, normalizeNames, stableOrder), "")
    }.toString()
  } catch (e: Exception) {
    "(Full dump is not available: ${e.message})\n" + render()
  }

fun IrFile.dumpTreesFromLineNumber(lineNumber: Int, normalizeNames: Boolean = false): String {
  if (shouldSkipDump()) return ""
  val sb = StringBuilder()
  accept(DumpTreeFromSourceLineVisitor(fileEntry, lineNumber, sb, normalizeNames), null)
  return sb.toString()
}

private fun IrFile.shouldSkipDump(): Boolean {
  val entry = fileEntry as? NaiveSourceBasedFileEntryImpl ?: return false
  return entry.lineStartOffsetsAreEmpty
}

class DumpIrTreeVisitor(
  out: Appendable,
  normalizeNames: Boolean = false,
  private val stableOrder: Boolean = false
) : IrElementVisitor<Unit, String> {

  private val printer = Printer(out, "  ")
  private val elementRenderer = RenderIrElementVisitorSimple(normalizeNames, !stableOrder)
  private fun IrType.render() = elementRenderer.renderType(this)

  private fun List<IrDeclaration>.ordered(): List<IrDeclaration> {
    if (!stableOrder) return this

    val strictOrder = mutableMapOf<IrDeclaration, Int>()

    var idx = 0

    forEach {
      if (it is IrProperty && it.backingField != null && !it.isConst) {
        strictOrder[it] = idx++
      }
      if (it is IrAnonymousInitializer) {
        strictOrder[it] = idx++
      }
    }

    return sortedWith { a, b ->
      val strictA = strictOrder[a] ?: Int.MAX_VALUE
      val strictB = strictOrder[b] ?: Int.MAX_VALUE

      if (strictA == strictB) {
        val rA = a.render()
        val rB = b.render()
        rA.compareTo(rB)
      } else strictA - strictB
    }
  }

  override fun visitElement(element: IrElement, data: String) {
    element.dumpLabeledElementWith(data) {
      if (element is IrAnnotationContainer) {
        dumpAnnotations(element)
      }
      element.acceptChildren(this@DumpIrTreeVisitor, "")
    }
  }

  override fun visitModuleFragment(declaration: IrModuleFragment, data: String) {
    declaration.dumpLabeledElementWith(data) {
      declaration.files.dumpElements()
    }
  }

  override fun visitExternalPackageFragment(declaration: IrExternalPackageFragment, data: String) {
    declaration.dumpLabeledElementWith(data) {
      declaration.declarations.ordered().dumpElements()
    }
  }

  override fun visitFile(declaration: IrFile, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.declarations.ordered().dumpElements()
    }
  }

  override fun visitClass(declaration: IrClass, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.sealedSubclasses.dumpItems("sealedSubclasses") { it.dump() }
      declaration.thisReceiver?.accept(this, "\$this")
      declaration.typeParameters.dumpElements()
      declaration.declarations.ordered().dumpElements()
    }
  }

  override fun visitTypeAlias(declaration: IrTypeAlias, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.typeParameters.dumpElements()
    }
  }

  override fun visitTypeParameter(declaration: IrTypeParameter, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
    }
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.correspondingPropertySymbol?.dumpInternal("correspondingProperty")
      declaration.overriddenSymbols.dumpItems("overridden") { it.dump() }
      declaration.typeParameters.dumpElements()
      declaration.dispatchReceiverParameter?.accept(this, "\$this")

      val contextReceiverParametersCount = declaration.contextReceiverParametersCount
      if (contextReceiverParametersCount > 0) {
        printer.println("contextReceiverParametersCount: $contextReceiverParametersCount")
      }

      declaration.extensionReceiverParameter?.accept(this, "\$receiver")
      declaration.valueParameters.dumpElements()
      declaration.body?.accept(this, "")
    }
  }

  private fun dumpAnnotations(element: IrAnnotationContainer) {
    element.annotations.dumpItems("annotations") { irAnnotation: IrConstructorCall ->
      printer.println(elementRenderer.renderAsAnnotation(irAnnotation))
    }
  }

  private fun IrSymbol.dump(label: String? = null) =
    printer.println(
      elementRenderer.renderSymbolReference(this).let {
        if (label != null) "$label: $it" else it
      }
    )

  override fun visitConstructor(declaration: IrConstructor, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.typeParameters.dumpElements()
      declaration.dispatchReceiverParameter?.accept(this, "\$outer")
      declaration.valueParameters.dumpElements()
      declaration.body?.accept(this, "")
    }
  }

  override fun visitProperty(declaration: IrProperty, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.overriddenSymbols.dumpItems("overridden") { it.dump() }
      declaration.backingField?.accept(this, "")
      declaration.getter?.accept(this, "")
      declaration.setter?.accept(this, "")
    }
  }

  override fun visitField(declaration: IrField, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.initializer?.accept(this, "")
    }
  }

  private fun List<IrElement>.dumpElements() {
    forEach { it.accept(this@DumpIrTreeVisitor, "") }
  }

  override fun visitErrorCallExpression(expression: IrErrorCallExpression, data: String) {
    expression.dumpLabeledElementWith(data) {
      expression.explicitReceiver?.accept(this, "receiver")
      expression.arguments.dumpElements()
    }
  }

  override fun visitEnumEntry(declaration: IrEnumEntry, data: String) {
    declaration.dumpLabeledElementWith(data) {
      dumpAnnotations(declaration)
      declaration.initializerExpression?.accept(this, "init")
      declaration.correspondingClass?.accept(this, "class")
    }
  }

  override fun visitMemberAccess(expression: IrMemberAccessExpression<*>, data: String) {
    expression.dumpLabeledElementWith(data) {
      dumpTypeArguments(expression)
      expression.dispatchReceiver?.accept(this, "") // $this
      expression.extensionReceiver?.accept(this, "\$receiver")
      val valueParameterNames = expression.getValueParameterNamesForDebug()
      for (index in 0 until expression.valueArgumentsCount) {
        expression.getValueArgument(index)?.accept(this, valueParameterNames[index])
      }
    }
  }

  override fun visitConstructorCall(expression: IrConstructorCall, data: String) {
    expression.dumpLabeledElementWith(data) {
      dumpTypeArguments(expression)
      expression.outerClassReceiver?.accept(this, "\$outer")
      dumpConstructorValueArguments(expression)
    }
  }

  private fun dumpConstructorValueArguments(expression: IrConstructorCall) {
    val valueParameterNames = expression.getValueParameterNamesForDebug()
    for (index in 0 until expression.valueArgumentsCount) {
      expression.getValueArgument(index)?.accept(this, valueParameterNames[index])
    }
  }

  private fun dumpTypeArguments(expression: IrMemberAccessExpression<*>) {
    val typeParameterNames = expression.getTypeParameterNames(expression.typeArgumentsCount)
    for (index in 0 until expression.typeArgumentsCount) {
      printer.println("<${typeParameterNames[index]}>: ${expression.renderTypeArgument(index)}")
    }
  }

  private fun dumpTypeArguments(expression: IrConstructorCall) {
    val typeParameterNames = expression.getTypeParameterNames(expression.typeArgumentsCount)
    for (index in 0 until expression.typeArgumentsCount) {
      val typeParameterName = typeParameterNames[index]
      val parameterLabel =
        if (index < expression.classTypeArgumentsCount)
          "class: $typeParameterName"
        else
          typeParameterName
      printer.println("<$parameterLabel>: ${expression.renderTypeArgument(index)}")
    }
  }

  private fun IrMemberAccessExpression<*>.getTypeParameterNames(expectedCount: Int): List<String> =
    if (symbol.isBound)
      symbol.owner.getTypeParameterNames(expectedCount)
    else
      getPlaceholderParameterNames(expectedCount)

  private fun IrSymbolOwner.getTypeParameterNames(expectedCount: Int): List<String> =
    if (this is IrTypeParametersContainer) {
      val typeParameters = if (this is IrConstructor) getFullTypeParametersList() else this.typeParameters
      (0 until expectedCount).map {
        if (it < typeParameters.size)
          typeParameters[it].name.asString()
        else
          "${it + 1}"
      }
    } else {
      getPlaceholderParameterNames(expectedCount)
    }

  private fun IrConstructor.getFullTypeParametersList(): List<IrTypeParameter> {
    val parentClass = try {
      parent as? IrClass ?: return typeParameters
    } catch (e: Exception) {
      return typeParameters
    }
    return parentClass.typeParameters + typeParameters
  }

  private fun IrMemberAccessExpression<*>.renderTypeArgument(index: Int): String =
    getTypeArgument(index)?.render() ?: "<none>"

  override fun visitGetField(expression: IrGetField, data: String) {
    expression.dumpLabeledElementWith(data) {
      expression.receiver?.accept(this, "receiver")
    }
  }

  override fun visitSetField(expression: IrSetField, data: String) {
    expression.dumpLabeledElementWith(data) {
      expression.receiver?.accept(this, "receiver")
      expression.value.accept(this, "value")
    }
  }

  override fun visitWhen(expression: IrWhen, data: String) {
    expression.dumpLabeledElementWith(data) {
      expression.branches.dumpElements()
    }
  }

  override fun visitBranch(branch: IrBranch, data: String) {
    branch.dumpLabeledElementWith(data) {
      branch.condition.accept(this, "if")
      branch.result.accept(this, "then")
    }
  }

  override fun visitWhileLoop(loop: IrWhileLoop, data: String) {
    loop.dumpLabeledElementWith(data) {
      loop.condition.accept(this, "condition")
      loop.body?.accept(this, "body")
    }
  }

  override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: String) {
    loop.dumpLabeledElementWith(data) {
      loop.body?.accept(this, "body")
      loop.condition.accept(this, "condition")
    }
  }

  override fun visitTry(aTry: IrTry, data: String) {
    aTry.dumpLabeledElementWith(data) {
      aTry.tryResult.accept(this, "try")
      aTry.catches.dumpElements()
      aTry.finallyExpression?.accept(this, "finally")
    }
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall, data: String) {
    expression.dumpLabeledElementWith(data) {
      expression.acceptChildren(this, "")
    }
  }

  override fun visitDynamicOperatorExpression(expression: IrDynamicOperatorExpression, data: String) {
    expression.dumpLabeledElementWith(data) {
      expression.receiver.accept(this, "receiver")
      for ((i, arg) in expression.arguments.withIndex()) {
        arg.accept(this, i.toString())
      }
    }
  }

  override fun visitConstantArray(expression: IrConstantArray, data: String) {
    expression.dumpLabeledElementWith(data) {
      for ((i, value) in expression.elements.withIndex()) {
        value.accept(this, i.toString())
      }
    }
  }

  override fun visitConstantObject(expression: IrConstantObject, data: String) {
    expression.dumpLabeledElementWith(data) {
      for ((index, argument) in expression.valueArguments.withIndex()) {
        argument.accept(this, expression.constructor.owner.valueParameters[index].name.toString())
      }
    }
  }

  private inline fun IrElement.dumpLabeledElementWith(label: String, body: () -> Unit) {
    printer.println(accept(elementRenderer, null).withLabel(label))
    indented(body)
  }

  private inline fun <T> Collection<T>.dumpItems(caption: String, renderElement: (T) -> Unit) {
    if (isEmpty()) return
    indented(caption) {
      forEach {
        renderElement(it)
      }
    }
  }

  private fun IrSymbol.dumpInternal(label: String? = null) {
    if (isBound)
      owner.dumpInternal(label)
    else
      printer.println("$label: UNBOUND ${javaClass.simpleName}")
  }

  private fun IrElement.dumpInternal(label: String? = null) {
    if (label != null) {
      printer.println("$label: ", accept(elementRenderer, null))
    } else {
      printer.println(accept(elementRenderer, null))
    }

  }

  private inline fun indented(label: String, body: () -> Unit) {
    printer.println("$label:")
    indented(body)
  }

  private inline fun indented(body: () -> Unit) {
    printer.pushIndent()
    body()
    printer.popIndent()
  }

  private fun String.withLabel(label: String) =
    if (label.isEmpty()) this else "$label: $this"
}

class DumpTreeFromSourceLineVisitor(
  val fileEntry: IrFileEntry,
  private val lineNumber: Int,
  out: Appendable,
  normalizeNames: Boolean = false
) : IrElementVisitorVoid {
  private val dumper = DumpIrTreeVisitor(out, normalizeNames)

  override fun visitElement(element: IrElement) {
    if (fileEntry.getLineNumber(element.startOffset) == lineNumber) {
      element.accept(dumper, "")
      return
    }

    element.acceptChildrenVoid(this)
  }
}

object CollectDecls {
  fun from(element: IrElement): List<IrSymbol> {
    val collector = CollectIdentDeclVisitor()
    element.accept(collector, null)
    return collector.foundIdentifiers
  }
}


class CollectIdentDeclVisitor() : IrElementVisitorVoid {
  val foundIdentifiers = mutableListOf<IrSymbol>()

  override fun visitElement(element: IrElement) {
    if (
      element is IrDeclaration &&
      element.symbol.safeName != "<anonymous>" &&
      !element.symbol.safeName.startsWith("_context_receiver")
    ) {
      foundIdentifiers.add(element.symbol)
    }

    element.acceptChildrenVoid(this)
  }
}

internal fun IrMemberAccessExpression<*>.getValueParameterNamesForDebug(): List<String> {
  val expectedCount = valueArgumentsCount
  if (symbol.isBound) {
    val owner = symbol.owner
    if (owner is IrFunction) {
      return (0 until expectedCount).map {
        if (it < owner.valueParameters.size)
          owner.valueParameters[it].name.asString()
        else
          "${it + 1}"
      }
    }
  }
  return getPlaceholderParameterNames(expectedCount)
}

internal fun getPlaceholderParameterNames(expectedCount: Int) =
  (1..expectedCount).map { "$it" }
