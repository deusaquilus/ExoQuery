package io.exoquery.plugin.trees

import io.decomat.*
import io.exoquery.*
import io.exoquery.annotation.ExoCapture
import io.exoquery.annotation.ExoCaptureExpression
import io.exoquery.annotation.ExoCaptureSelect
import io.exoquery.annotation.ExoUseExpression
import io.exoquery.plugin.*
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.plugin.transform.BinaryOperators
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.transform.ReceiverCaller
import io.exoquery.plugin.transform.UnaryOperators
import io.exoquery.terpal.Interpolator
import io.exoquery.xr.BinaryOperator
import io.exoquery.xr.UnaryOperator
import org.jetbrains.kotlin.ir.backend.js.utils.valueArguments
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.classFqName
import kotlin.collections.get
import kotlin.comparisons.then

object ExtractorsDomain {

  fun IsSelectFunction() = Ir.Expr.ClassOf<SelectClauseCapturedBlock>()

  object DynamicQueryCall {
    context(CX.Scope, CX.Symbology)
    operator fun <AP: Pattern<IrExpression>> get(x: AP) =
      customPattern1("DynamicQueryCall", x) { expr: IrExpression ->
        val matches = expr.match(
          // Don't allow this for now too many possible edge-cases can happen. Adding a specific warning for it in the ParseQuery
          // case(Ir.Call[Is()]).thenIf { call -> call.isExternal() && expr.isSqlQuery() && call.symbol.owner is IrSimpleFunction }.then { _ -> true },
          case(Ir.GetField[Is()]).thenIfThis { this.isExternal() && expr.isSqlQuery() }.then { _ -> true },
          case(Ir.GetValue[Is()]).thenIfThis { this.isExternal() && expr.isSqlQuery() }.then { _ -> true }
        ) ?: false
        if (matches)
          Components1(expr)
        else
          null
      }
  }

  object DynamicExprCall {
    context(CX.Scope, CX.Symbology)
    operator fun <AP: Pattern<IrExpression>> get(x: AP) =
      customPattern1("DynamicExprCall", x) { expr: IrExpression ->
        val matches = expr.match(
          case(Ir.GetField[Is()]).thenIfThis { this.isExternal() && expr.isSqlExpression() }.then { _ -> true },
          case(Ir.GetValue[Is()]).thenIfThis { this.isExternal() && expr.isSqlExpression() }.then { _ -> true }
        ) ?: false
        if (matches)
          Components1(expr)
        else
          null
      }
  }

  sealed interface QueryDslFunction {
    context(CX.Scope) fun matchesMethod(it: IrCall): Boolean
    context(CX.Scope) fun extract(it: IrCall): Pair<RecieverCallData, ReplacementMethodToCall>?
  }

  object CaseClassConstructorCall {
    data class Data(val className: String, val fields: List<Field>)
    data class Field(val name: String, val value: IrExpression?)

    context(CX.Scope) operator fun <AP: Pattern<Data>> get(x: AP) =
      customPattern1("CaseClassConstructorCall", x) { call: IrConstructorCall ->
        when {
          call.symbol.safeName == "<init>" -> {
            val className: String = call.type.classFqName?.sanitizedClassName() ?: call.type.dumpKotlinLike()
            if (!call.symbol.owner.isPrimary)
              parseError("Detected construction of the class ${className} using a non-primary constructor. This is not allowed.")

            val params = call.symbol.owner.simpleValueParams.map { it.name.asString() }.toList()
            val args = call.valueArguments.toList()
            if (params.size != args.size)
              parseError("Cannot parse constructor of ${className} its params ${params} do not have the same cardinality as its arguments ${args.map { it?.dumpKotlinLike() }}")
            val fields = (params zip args).map { (name, value) -> Field(name, value)}
            Components1(Data(className, fields))
          }
          else -> null
        }
      }
  }

  object CaseClassConstructorCall1 {
    context(CX.Scope) operator fun <AP: Pattern<String>, BP: Pattern<IrExpression>> get(x: AP, y: BP) =
      customPattern2("CaseClassConstructorCall1", x, y) { call: IrConstructorCall ->
        when {
          call.symbol.safeName == "<init>" && call.symbol.owner.simpleValueParams.size == 1 -> {
            val className: String = call.type.classFqName?.asString() ?: call.type.dumpKotlinLike()
            if (!call.symbol.owner.isPrimary)
              parseError("Detected construction of the class ${className} using a non-primary constructor. This is not allowed.")

            val params = call.symbol.owner.simpleValueParams.map { it.name.asString() }.toList()
            val args = call.valueArguments.toList()
            if (params.size != args.size)
              parseError("Cannot parse constructor of ${className} its params ${params} do not have the same cardinality as its arguments ${args.map { it?.dumpKotlinLike() }}")
            Components2(className, args.first())
          }
          else -> null
        }
      }
  }

  // Match case classes that have at least one paramater. The match is on the case class name and the first parameter. This is useful
  // since in many cases the primary deconstructin logic is on on that data
  object CaseClassConstructorCall1Plus {
    context(CX.Scope) operator fun <AP: Pattern<String>, BP: Pattern<IrExpression>> get(x: AP, y: BP) =
      customPattern2("CaseClassConstructorCall1Plus", x, y) { call: IrConstructorCall ->
        when {
          call.symbol.safeName == "<init>" && call.symbol.owner.simpleValueParams.size >= 1 -> {
            val className: String = call.type.classFqName?.asString() ?: call.type.dumpKotlinLike()
            if (!call.symbol.owner.isPrimary)
              parseError("Detected construction of the class ${className} using a non-primary constructor. This is not allowed.")

            val params = call.symbol.owner.simpleValueParams.map { it.name.asString() }.toList()
            val args = call.valueArguments.toList()
            if (params.size != args.size)
              parseError("Cannot parse constructor of ${className} its params ${params} do not have the same cardinality as its arguments ${args.map { it?.dumpKotlinLike() }}")
            Components2(className, args.first())
          }
          else -> null
        }
      }
  }

  object Call {
    data class OperatorCall(val x: IrExpression, val op: BinaryOperator, val y: IrExpression)
    data class UnaryOperatorCall(val x: IrExpression, val op: UnaryOperator)

    data class LambdaFunctionMethod(val matchesMethod: (IrCall) -> Boolean) {
      context(CX.Scope) operator fun <AP: Pattern<CallData.LambdaMember>> get(x: AP) =
        customPattern1("Call.LambdaFunctionMethod", x) { it: IrCall ->
          if (matchesMethod(it)) {
            it.match(
              // printExpr(.. { stuff }: IrFunctionExpression  ..): FunctionCall
              case( /* .flatMap */ Ir.Call.FunctionMem1.WithCaller[Is(), Is(), Is()]).then { reciver, expression ->
                expression.match(
                  case(Ir.FunctionExpression.withBlock[Is(), Is()]).thenThis { params, blockBody ->
                    val funExpression = this
                    Components1(CallData.LambdaMember(reciver, funExpression, params, blockBody))
                  }
                )
              }
            )
          } else null
        }
    }

    object CaptureQuery {
      object LambdaBody {
        context(CX.Scope) operator fun <AP: Pattern<IrBlockBody>> get(call: AP) =
          customPattern1("Call.CaptureQuery.LambdaBody", call) { it: IrCall ->
            if (it.ownerHasAnnotation<ExoCapture>() && it.type.isClass<SqlQuery<*>>()) {
              val arg = it.simpleValueArgs.first() ?: parseError("CaptureQuery must have a single argument but was: ${it.simpleValueArgs.map { it?.dumpKotlinLike() }}", it)
              arg.match(
                // printExpr(.. { stuff }: IrFunctionExpression  ..): FunctionCall
                case(Ir.FunctionExpression.withBlock[Is(), Is()]).thenThis { _, body ->
                  Components1(body)
                }
              )
            } else {
              null
            }
          }
      }

      context(CX.Scope) operator fun <AP: Pattern<IrCall>> get(call: AP) =
        customPattern1("Call.CaptureQuery", call) { it: IrCall ->
          if (it.ownerHasAnnotation<ExoCapture>() && it.type.isClass<SqlQuery<*>>()) {
            Components1(it)
          } else {
            null
          }
        }
    }

    object CaptureSelect {
      context(CX.Scope) operator fun <AP: Pattern<IrCall>> get(call: AP) =
        customPattern1("Call.CaptureSelect", call) { it: IrCall ->
          if (it.ownerHasAnnotation<ExoCaptureSelect>() && it.type.isClass<SqlQuery<*>>()) {
            Components1(it)
          } else {
            null
          }
        }
    }

    object UseExpression {
      object Receiver {
        context(CX.Scope) operator fun <AP: Pattern<IrExpression>> get(call: AP) =
          customPattern1("Call.UseExpression.Receiver", call) { it: IrCall ->
            if (it.ownerHasAnnotation<ExoUseExpression>()) {
              val receiver = it.extensionReceiver ?: parseError("UseExpression must have a receiver", it)
              Components1(receiver)
            } else {
              null
            }
          }
      }

      context(CX.Scope) operator fun <AP: Pattern<IrCall>> get(call: AP) =
        customPattern1("Call.UseExpression", call) { it: IrCall ->
          // Note that the output type is not SqlExression<T>, it is T since this is the sqlExpression.use call
          if (it.ownerHasAnnotation<ExoUseExpression>()) {
            Components1(it)
          } else {
            null
          }
        }
    }

    object CaptureExpression {
      object LambdaBody {
        context(CX.Scope) operator fun <AP: Pattern<IrBlockBody>> get(call: AP) =
          customPattern1("Call.CaptureExpression.LambdaBody", call) { it: IrCall ->
            if (it.ownerHasAnnotation<ExoCaptureExpression>() && it.type.isClass<SqlExpression<*>>()) {
              val arg = it.simpleValueArgs.first() ?: parseError("CaptureExpression must have a single argument but was: ${it.simpleValueArgs.map { it?.dumpKotlinLike() }}", it)
              arg.match(
                // printExpr(.. { stuff }: IrFunctionExpression  ..): FunctionCall
                case(Ir.FunctionExpression.withBlock[Is(), Is()]).thenThis { _, body ->
                  Components1(body)
                }
              )
            } else {
              null
            }
          }
      }

      context(CX.Scope) operator fun <AP: Pattern<IrCall>> get(call: AP) =
        customPattern1("Call.CaptureSelect", call) { it: IrCall ->
          if (it.ownerHasAnnotation<ExoCaptureExpression>() && it.type.isClass<SqlExpression<*>>()) {
            Components1(it)
          } else {
            null
          }
        }
    }

    object InterpolateInvoke {
      context(CX.Scope) operator fun <BP: Pattern<List<IrExpression>>> get(terpComps: BP) =
        customPattern1("InterpolateInvoke", terpComps) { call: IrCall ->
          call.match(
            // I.e. (CapturedBlock).free("foo, bar"):FreeBlock
            // which will be part of something like:
            // ((CapturedBlock).free("foo, bar"):FreeBlock).asPure<String>()
            case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<CapturedBlock>(), Is("free"), Is()]).thenIfThis { _, _ -> type.isClassStrict<FreeBlock>() }.thenThis { _, arg ->
              arg.match(
                case(Ir.StringConcatenation[Is()]).then { components ->
                  Components1(components)
                },
                case(Ir.Call.NamedExtensionFunctionZeroArg[Is("kotlin.text.trimIndent"), Ir.StringConcatenation[Is()]]).then { str, (components) ->
                  Components1(components)
                },
                // it's a single string-const in this case
                case(Ir.Const[Is()]).then { const ->
                  Components1(listOf(const))
                }
              )
            }
          )
        }
      }

    object `x op y` {
      context(CX.Scope) operator fun <AP: Pattern<OperatorCall>> get(x: AP) =
        customPattern1("x op y", x) { it: IrCall ->
          it.match(
            case(Ir.Call.FunctionUntethered2[Is(), Is(), Is()])
              .thenIfThis { _, _ -> BinaryOperators.operators.get(symbol.safeName) != null }
              .thenThis { arg1, arg2 -> Triple(this.symbol.safeName, arg1, arg2) },
            case(Ir.Call.FunctionRec1[Is(), Is()])
              .thenIfThis { _, _ -> BinaryOperators.operators.get(symbol.safeName) != null }
              .thenThis { arg1, arg2 -> Triple(this.symbol.safeName, arg1, arg2) }
          )?.let { result ->
            val (opName, arg1, arg2) = result
            BinaryOperators.operators.get(opName)?.let { op ->
              Components1(OperatorCall(arg1, op, arg2))
            }
          }
        }
    }

    inline fun <T> Boolean.thenLet(predicate: () -> T): T? =
      if (this) predicate() else null

    object `x to y` {
      context(CX.Scope) operator fun <AP: Pattern<IrExpression>, BP: Pattern<IrExpression>> get(x: AP, y: BP) =
        customPattern2("x to y", x, y) { it: IrCall ->
          // TODO see what other descriptors it has to make sure it's only a system-level a to b
          (it.symbol.safeName == "to").thenLet {
            it.extensionReceiver?.let { argA ->
              it.simpleValueArgs.first()?.let { argB ->
                Components2(argA, argB)
              }
            }
          }
        }
    }


    object `(op)x` {
      context(CX.Scope) operator fun <AP: Pattern<UnaryOperatorCall>> get(x: AP) =
        customPattern1("(op)x", x) { it: IrCall ->
          on(it).match(
            case(Ir.Call.FunctionUntethered1.Arg[Is()]).thenThis { arg1 -> Pair(this.symbol.safeName, arg1) },
            case(Ir.Call.FunctionRec0[Is()]).thenThis { arg1 -> Pair(this.symbol.safeName, arg1) }
          )?.let { result ->
            val (opName, arg1) = result
            UnaryOperators.operators.get(opName)?.let { op ->
              Components1(UnaryOperatorCall(arg1, op))
            }
          }
        }
    }
  }


}

sealed interface RecieverCallData: CallData
sealed interface CallData {
  // i.e. Something.someMethod { someLambda }
  data class LambdaMember(val reciver: ReceiverCaller, val functionExpression: IrFunctionExpression, val params:  List<IrValueParameter>, val body: IrBlockBody): RecieverCallData
  // i.e. someMethod { someLambda }
  data class LambdaTopLevel(val functionExpression: IrFunctionExpression, val params:  List<IrValueParameter>, val body: IrBlockBody): CallData

  data class MultiArgMember(val reciver: ReceiverCaller, val argValues: List<Pair<ArgType, IrExpression>>): RecieverCallData {
    sealed interface ArgType {
      object ParsedXR: ArgType
      object Passthrough: ArgType
    }
  }
}
