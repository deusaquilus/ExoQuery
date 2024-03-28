package io.exoquery.plugin.transform

import io.exoquery.annotation.ChangeReciever
import io.exoquery.plugin.ReplacementMethodToCall
import io.exoquery.plugin.findExtensionMethodOrFail
import io.exoquery.plugin.findMethodOrFail
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

class CallMethod(private val callerRaw: Caller, private val replacementFun: ReplacementMethodToCall, private val types: List<IrType>, private val tpe: IrType?) {
  context(BuilderContext) operator fun invoke(vararg args: IrExpression): IrExpression {
    val caller =
      when (replacementFun.callerType) {
        ChangeReciever.ToDispatch -> callerRaw.toDispatch()
        ChangeReciever.ToExtension -> callerRaw.toExtension()
        ChangeReciever.DoNothing -> callerRaw
      }

    val funName = replacementFun.methodToCall

    val invoke =
      when (caller) {
        is Caller.DispatchReceiver -> caller.reciver.type.findMethodOrFail(funName)
        is Caller.ExtensionReceiver -> caller.reciver.type.findExtensionMethodOrFail(funName)
        is Caller.TopLevelMethod -> pluginCtx.referenceFunctions(CallableId(FqName(caller.packageName), Name.identifier(funName))).first()
      }

    return with (builder) {
      val invocation = if (tpe != null) irCall(invoke, tpe) else irCall(invoke)
      invocation.apply {
        when (caller) {
          is Caller.DispatchReceiver -> { dispatchReceiver = caller.reciver }
          is Caller.ExtensionReceiver -> { extensionReceiver = caller.reciver }
          is Caller.TopLevelMethod -> {}
        }

        for ((index, tpe) in types.withIndex()) {
          putTypeArgument(index, tpe)
        }
        for ((index, expr) in args.withIndex()) {
          putValueArgument(index, expr)
        }
      }
    }
  }
}

// TODO these should be implemented on Caller, not IrExpression

fun ReceiverCaller.callMethod(name: ReplacementMethodToCall) = CallMethod(this, name, listOf(), null)
fun ReceiverCaller.callMethodWithType(name: ReplacementMethodToCall, fullOutputType: IrType) = CallMethod(this, name, listOf(), fullOutputType)

fun ReceiverCaller.callMethodTyped(name: ReplacementMethodToCall, typeParams: List<IrType>): CallMethod = CallMethod(this, name, typeParams, null)
fun ReceiverCaller.callMethodTypedWithType(name: ReplacementMethodToCall, typeParams: List<IrType>, fullOutputType: IrType): CallMethod = CallMethod(this, name, typeParams, fullOutputType)


fun callMethod(packageName: String, name: String) = CallMethod(Caller.TopLevelMethod(packageName), ReplacementMethodToCall(name), listOf(), null)
fun callMethodWithType(packageName: String, name: String, tpe: IrType) = CallMethod(Caller.TopLevelMethod(packageName), ReplacementMethodToCall(name), listOf(), tpe)

fun callMethodTyped(packageName: String, name: String, typeParams: List<IrType>) = CallMethod(Caller.TopLevelMethod(packageName), ReplacementMethodToCall(name), typeParams, null)
fun callMethodTypedWithTypes(packageName: String, name: String, typeParams: List<IrType>, tpe: IrType) = CallMethod(Caller.TopLevelMethod(packageName), ReplacementMethodToCall(name), typeParams, tpe)

context (BuilderContext) fun createLambda0(functionBody: IrExpression, functionParent: IrDeclarationParent): IrFunctionExpression =
  with(builder) {
    val functionClosure = createLambda0Closure(functionBody, functionParent)
    val functionType = pluginCtx.symbols.functionN(0).typeWith(functionClosure.returnType)
    IrFunctionExpressionImpl(startOffset, endOffset, functionType, functionClosure, IrStatementOrigin.LAMBDA)
  }

context (BuilderContext) fun createLambda0Closure(functionBody: IrExpression, functionParent: IrDeclarationParent): IrSimpleFunction {
  return with(pluginCtx) {
    irFactory.buildFun {
      origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
      name = SpecialNames.NO_NAME_PROVIDED
      visibility = DescriptorVisibilities.LOCAL
      returnType = functionBody.type
      modality = Modality.FINAL
      isSuspend = false
    }.apply {
      parent = functionParent
      /*
      VERY important here to create a new irBuilder from the symbol i.e. createIrBuilder because
      the return-point needs to be the caller-function (which kotlin gets from the irBuilder).
      If the builder in the BuilderContext is used it will return back to whatever context the
      TransformInterpolatorInvoke IrCall expression is coming from (and this will be a non-local return)
      and since the return-type is wrong it will fail with a very large error that ultimately says:
      RETURN: Incompatible return type
       */
      body = pluginCtx.createIrBuilder(symbol).run {
        // don't use expr body, coroutine codegen can't generate for it.
        irBlockBody {
          +irReturn(functionBody)
        }
      }
    }
  }
}

fun IrPluginContext.createIrBuilder(
  symbol: IrSymbol,
  startOffset: Int = UNDEFINED_OFFSET,
  endOffset: Int = UNDEFINED_OFFSET,
) = DeclarationIrBuilder(this, symbol, startOffset, endOffset)
