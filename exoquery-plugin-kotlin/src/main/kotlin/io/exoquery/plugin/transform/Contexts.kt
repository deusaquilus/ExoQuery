package io.exoquery.plugin.transform

import io.exoquery.config.ExoCompileOptions
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.plugin.trees.DynamicsAccum
import io.exoquery.plugin.trees.Lifter
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.types.model.TypeConstructorMarker

context(CX.Builder) fun makeLifter() = Lifter(this@Builder)

object CX {

  data class Parsing(val binds: DynamicsAccum = DynamicsAccum.newEmpty())

  data class QueryAccum(val accum: FileQueryAccum)

  data class Symbology(val symbolSet: SymbolSet)

  data class Scope(
    val currentExpr: IrElement,
    val logger: CompileLogger,
    val currentFile: IrFile,
    val pluginCtx: IrPluginContext,
    val compilerConfig: CompilerConfiguration,
    val options: ExoCompileOptions,
    val scopeOwner: IrSymbol
  ) {
    val compileLogger get() = logger
    val typeSystem by lazy {
      val baseContext = IrTypeSystemContextImpl(pluginCtx.irBuiltIns)
      object : IrTypeSystemContext by baseContext {
        override fun TypeConstructorMarker.isError(): Boolean {
          return false
        }
      }
    }
  }

  // Need the scope-owner in order to be able to construct lambdas (which is why this is in the builder)
  data class Builder(val scopeContext: Scope, val scopeOwner: IrSymbol) {
    val builder by lazy { DeclarationIrBuilder(scopeContext.pluginCtx, scopeOwner, scopeContext.currentExpr.startOffset, scopeContext.currentExpr.endOffset) }
  }
}


//data class TransformerOrigin(
//  val pluginCtx: IrPluginContext,
//  val config: CompilerConfiguration,
//  val currentFile: IrFile,
//  val parentScopeSymbols: TransformerScope,
//  val options: ExoCompileOptions
//) {
//  fun makeBuilderContext(expr: IrElement, scopeOwner: IrSymbol) =
//    BuilderContext(pluginCtx, config, scopeOwner, currentFile, expr, parentScopeSymbols, options)
//}
