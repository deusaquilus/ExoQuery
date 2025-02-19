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
import io.exoquery.xr.XRType
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

object TypeParser {
  context(ParserContext, CompileLogger) fun of(expr: IrExpression) =
    ofElementWithType(expr, expr.type)

  context(ParserContext, CompileLogger) fun of(expr: IrVariable) =
    ofElementWithType(expr, expr.type)

  context(ParserContext, CompileLogger) fun of(expr: IrFunction) =
    ofElementWithType(expr, expr.returnType)

  context(ParserContext, CompileLogger) fun of(expr: IrValueParameter) =
    ofElementWithType(expr, expr.type)

  context(ParserContext, CompileLogger) fun ofTypeAt(type: IrType, loc: Location): XRType =
    try {
      parse(type)
    } catch (e: Exception) {
      parseErrorFromType("ERROR Could not parse type: ${type.dumpKotlinLike()}", loc)
    }

  context(ParserContext, CompileLogger) private fun ofElementWithType(expr: IrElement, type: IrType) =
    try {
      when {
        // If this is a field from a class that is marked @Contextaul then we know immediately it is a value type
        expr is IrGetField && expr.symbol.owner.hasAnnotation<kotlinx.serialization.Contextual>() -> XRType.Value
        else -> parse(type)
      }
    } catch (e: Exception) {
      parseErrorFromType("ERROR Could not parse the type: ${type.dumpKotlinLike()} (${type.toString()}", expr)
    }

  context(ParserContext, CompileLogger) private fun parse(expr: IrType): XRType =
    on(expr).match<XRType>(
      // TODO why for Components1 it's (type) bot for Components2 it's (type, type)
      //     think this is a bug with DecoMat.
      //case(Ir.Type.SqlVariable[Is()]).then { realType ->
      //  parse(realType)
      //},

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

      case(Ir.Type.Value[Is()]).then { type ->
        //error("----------- Got here: ${type} ----------")
        if (type.isBoolean())
          XRType.BooleanValue
        else
          XRType.Value
      },

      case(Ir.Type.Generic[Is()]).then { type ->
        //error("----------- Got here: ${type} ----------")
        XRType.Generic
      }
    ) ?: run {
      val loc = currentLocation()
      parseErrorFromType("ERROR Could not parse type from: ${expr.dumpKotlinLike()}", loc)
    }
}
