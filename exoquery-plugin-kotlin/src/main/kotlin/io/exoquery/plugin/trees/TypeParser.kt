package io.exoquery.plugin.trees

import io.decomat.Is
import io.decomat.case
import io.decomat.on
import io.exoquery.ParseError
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.parseError
import io.exoquery.parseErrorFromType
import io.exoquery.plugin.hasAnnotation
import io.exoquery.plugin.location
import io.exoquery.plugin.logging.Location
import io.exoquery.plugin.show
import io.exoquery.plugin.source
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.util.LruCache
import io.exoquery.xr.XRType
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

object TypeParser {
  context(CX.Scope, CX.Parsing, CX.Symbology) fun of(expr: IrExpression) =
    ofElementWithType(expr, expr.type)

  context(CX.Scope, CX.Parsing, CX.Symbology) fun ofTypeArgOf(expr: IrCall) =
    ofElementWithType(expr, expr.typeArguments.firstOrNull() ?: run {
      parseErrorFromType("ERROR Could not parse type-argument", expr)
    })

  // E.g. for a function-call that returns `Param<String>` get `String`
  context(CX.Scope, CX.Parsing, CX.Symbology) fun ofFirstArgOfReturnTypeOf(expr: IrCall) =
    ofElementWithType(expr, expr.type.simpleTypeArgs.firstOrNull() ?: run {
      parseErrorFromType("ERROR Could get the first type-argument of: ${expr.type}", expr)
    })

  context(CX.Scope, CX.Parsing, CX.Symbology) fun of(expr: IrVariable) =
    ofElementWithType(expr, expr.type)

  context(CX.Scope, CX.Parsing, CX.Symbology) fun of(expr: IrFunction) =
    ofElementWithType(expr, expr.returnType)

  context(CX.Scope, CX.Parsing, CX.Symbology) fun of(expr: IrValueParameter) =
    ofElementWithType(expr, expr.type)

  context(CX.Scope, CX.Parsing, CX.Symbology) fun ofTypeAt(type: IrType, loc: Location): XRType =
    try {
      parse(type)
    } catch (e: Exception) {
      parseErrorFromType("ERROR Could not parse type: ${type.dumpKotlinLike()}", loc)
    }

  context(CX.Scope, CX.Parsing, CX.Symbology) private fun ofElementWithType(expr: IrElement, type: IrType) =
    try {
      when {
        // If this is a field from a class that is marked @Contextaul then we know immediately it is a value type
        expr is IrGetField && expr.symbol.owner.hasAnnotation<kotlinx.serialization.Contextual>() ->
          if (type.isBoolean())
            XRType.BooleanValue
          else
            XRType.Value
        else ->
          parse(type)
      }
    } catch (e: Exception) {
      parseErrorFromType("ERROR Could not parse the type: ${type.dumpKotlinLike()} (in the expression: `${expr.source()}`", expr)
    }

  // Parsing large data-classes can get cumbersome, so we cache the results
  val typeCache = LruCache<IrType, XRType>(50000)

  context(CX.Scope, CX.Parsing, CX.Symbology) private fun parse(expr: IrType): XRType =
    typeCache.getOrPut(expr) { parseFull(expr) }

  context(CX.Scope, CX.Parsing, CX.Symbology) private fun parseFull(expr: IrType): XRType =
    on(expr).match<XRType>(
      // TODO why for Components1 it's (type) bot for Components2 it's (type, type)
      //     think this is a bug with DecoMat.
      //case(Ir.Type.SqlVariable[Is()]).then { realType ->
      //  parse(realType)
      //},

      // Need to check this before checking the isDataClass clause because for things like custom types e.g. data class MyCustomDate(year: Int, month: Int, day: Int)
      // it could be a data-class but still needs to be interpreted as a value (usually this will be because the type itself is annotated with @Contextual or @ExoValue
      // when it is coming out of a param(...)/paramCtx(...) call or it is a dereferenced property of a data-class that has @Contextual or @ExoValue marked on the type
      // of the member itself e.g. `data class Person(bornOn: @Contextual LocalDate)`, `capture { Table<Person>().map { p -> p.bornOn <- type: @Contextual MyCustomDate } }`.
      case(Ir.Type.Value[Is()]).then { type ->
        //error("----------- Got here: ${type} ----------")
        if (type.isBoolean())
          XRType.BooleanValue
        else
          XRType.Value
      },

      case(Ir.Type.ClassOfType<Function<*>>()).then { functionType ->
        // functionType will be something like Function1<..., R> i.e. the last argument is the return type which we take
        parse(functionType.simpleTypeArgs.last())
      },

      case(Ir.Type.NullableOf[Is()]).then { realType ->
        parse(realType)
      },

      // If it's a SqlExpression then parse the need to get the value of the 1st generic param
      case(Ir.Type.ClassOfType<io.exoquery.SqlExpression<*>>()).then { sqlExpressionType ->
        parse(sqlExpressionType.simpleTypeArgs[0])
      },

      // If it's a SqlQuery, same idea
      case(Ir.Type.ClassOfType<io.exoquery.SqlQuery<*>>()).then { sqlQueryType ->
        sqlQueryType.simpleTypeArgs.firstOrNull()?.let { parse(it) } ?: XRType.Generic
      },

      // If it is an SqlAction, the 2nd argument is the return type so we need to use that is the IrType
      case(Ir.Type.ClassOfType<io.exoquery.SqlAction<*, *>>()).then { sqlQueryType ->
        sqlQueryType.simpleTypeArgs[1].let { parse(it) }
      },

      case(Ir.Type.ClassOfType<io.exoquery.innerdsl.SqlActionFilterable<*, *>>()).then { sqlQueryType ->
        sqlQueryType.simpleTypeArgs[1].let { parse(it) }
      },

      // For now treat lists like value types, may way to change in future
      case(Ir.Type.KotlinList[Is()]).then { realType ->
        XRType.Value
      },

      //case(Ir.Type.Query[Is()]).then { realType ->
      //  parse(realType)
      //},

      // TODO need to check if there there is a @Serializeable annotation and if that has renamed type-name and/or field-values to use for the XRType
      case(Ir.Type.DataClass[Is(), Is()]).then { name, props ->
        val fieldTypeXRs = props.map { (fieldName, fieldType) -> fieldName to parse(fieldType) }
        //warn("------------- Parsed Class props of: ${name}: ${fieldTypeXRs.map { (a, b) -> "$a -> $b" }} -------------------")
        XRType.Product(name, fieldTypeXRs)
      },

      case(Ir.Type.Generic[Is()]).then { type ->
        //error("----------- Got here: ${type} ----------")
        XRType.Generic
      },

      case(Is<IrType> { it.isAny() }).then {
        //error("----------- Got here: ${it} ----------")
        XRType.Generic
      }
    ) ?: run {
      parseErrorFromType("ERROR Could not parse type from: ${expr.dumpKotlinLike()}", currentFile)
    }
}
