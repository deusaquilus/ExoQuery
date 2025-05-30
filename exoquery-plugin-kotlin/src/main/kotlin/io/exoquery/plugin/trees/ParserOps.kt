package io.exoquery.plugin.trees

import io.decomat.Is
import io.exoquery.CapturedBlock
import io.exoquery.SqlAction
import io.exoquery.SqlQuery
import io.exoquery.parseError
import io.exoquery.plugin.*
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.trees.ParseExpression.Seg
import io.exoquery.serial.ParamSerializer
import io.exoquery.terpal.UnzipPartsParams
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.xr.of
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrDeclarationReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.name.ClassId


context(CX.Symbology)
fun IrDeclarationReference.isCapturedFunctionArgument(): Boolean = run {
  val gv = this
  symbolSet.capturedFunctionParameters.find { gv.symbol.owner == it } != null
}

// To be used with IrGetValue and IrGetField
context(CX.Scope)
fun IrDeclarationReference.isCapturedVariable(): Boolean {
  tailrec fun rec(elem: IrElement, recurseCount: Int): Boolean =
    when {
      recurseCount == 0 -> false
      elem is IrFunction && elem.extensionReceiverParameter?.type?.isClass<CapturedBlock>() ?: false -> true
      elem is IrFunction -> rec(elem.symbol.owner.parent, recurseCount - 1)
      elem is IrValueParameter -> rec(elem.symbol.owner.parent, recurseCount - 1)
      elem is IrVariable -> rec(elem.symbol.owner.parent, recurseCount - 1)
      else -> false
    }

  return rec(this.symbol.owner, 100)
}

context(CX.Scope, CX.Symbology)
fun IrDeclarationReference.isExternal(): Boolean =
  !isCapturedFunctionArgument() && !isCapturedVariable()

context(CX.Scope, CX.Symbology)
fun IrDeclarationReference.isInternal(): Boolean = !isExternal()

context(CX.Scope, CX.Symbology, CX.Parsing)
fun parseFieldListOrFail(fieldExprs: List<IrExpression>): List<XR.Property> = run {
  // Keep around the field Ir's for another minute, if one of them isn't parsed right we want the right code position for it in the parseError
  val fieldsListRaw = fieldExprs.map { it to ParseExpression.parse(it) }
  fieldsListRaw.map { (ir, parsedField) ->
    parsedField as? XR.Property ?: parseError("Invalid field for onConflictIgnore: ${parsedField.showRaw()}. The onConflictIgnore fields need to be single-column values.", ir)
  }
}

context(CX.Scope)
fun IrDeclarationReference.showLineage(): String {
  val collect = mutableListOf<String>()
  fun IrVariable.declSymbol(): String = if (this.isVar) "var" else "val"

  tailrec fun rec(elem: IrElement, recurseCount: Int): Unit {
    val prefix =
      "${
        (elem as? IrFunction)?.let { "fun " + it.symbol.safeName + "(...)" }
          ?: (elem as? IrVariable)?.let { "${it.declSymbol()} " + it.symbol.safeName }
          ?: (elem as? IrValueParameter)?.let { "param " + it.symbol.safeName }
          ?: (elem as? IrFile)?.let { "File(${it.nameWithPackage})" }
          ?: (elem::class.simpleName ?: "Unknown")
      }"

    when {
      recurseCount == 0 -> {
        collect.add("${prefix}->RECURSION LIMIT HIT")
        Unit
      }
      elem is IrFunction && elem.extensionReceiverParameter?.type?.isClass<CapturedBlock>() ?: false -> {
        collect.add("${prefix}->${elem.symbol.safeName}-in CapturedBlock")
        Unit
      }
      elem is IrFunction -> {
        collect.add("${prefix}->fun.Owner")
        rec(elem.symbol.owner.parent, recurseCount - 1)
      }
      elem is IrValueParameter -> {
        collect.add("${prefix}->param.Owner")
        rec(elem.symbol.owner.parent, recurseCount - 1)
      }
      elem is IrVariable -> {
        collect.add("${prefix}->${elem.declSymbol()}.Owner")
        rec(elem.symbol.owner.parent, recurseCount - 1)
      }
      else ->
        collect.add(prefix)
    }
  }

  rec(this.symbol.owner, 100)
  return collect.map { "[${it}]" }.joinToString("->")
}

context(CX.Scope)
fun getSerializerForValueClass(type: IrType, location: CompilerMessageSourceLocation) =
  if (type.classOrNull?.owner?.isValue ?: false) {
    with (makeBuilderCtx()) {
      when (val ser = type.inferSerializer()) {
        is KnownSerializer.Ref -> ser.buildExpression(type, location)
        is KnownSerializer.Implicit -> ser.buildExpression(type)
        is KnownSerializer.None -> null
      }
    }
  } else {
    null
  }

context(CX.Scope)
fun getSerializerForType(type: IrType): ClassId? = run {
  val isNullable = type.isNullable()
  type.getPrimitiveType()?.let { primitiveType ->
    when {
      // Originally I believed that this was needed for nullable types but then I realized
      // that the Terpal-Controller can handle null values by taking the nullable-version of
      // a encoder ad-hoc. It does not necessarily need to handed a nullable serializer form the start.
      // (This makes sense when you think about it because PreparedStatementElementEncoder can always
      // check if the value is null upfront and if the value is null it can set it to null on the
      // PreparedStatement without doing anything else!)
      //   primitiveType == PrimitiveType.CHAR && isNullable -> classIdOf<ParamSerializer.CharNullable>()
      //   primitiveType == PrimitiveType.INT && isNullable -> classIdOf<ParamSerializer.IntNullable>()
      primitiveType == PrimitiveType.CHAR -> classIdOf<ParamSerializer.Char>()
      primitiveType == PrimitiveType.INT -> classIdOf<ParamSerializer.Int>()
      primitiveType == PrimitiveType.SHORT -> classIdOf<ParamSerializer.Short>()
      primitiveType == PrimitiveType.LONG -> classIdOf<ParamSerializer.Long>()
      primitiveType == PrimitiveType.FLOAT -> classIdOf<ParamSerializer.Float>()
      primitiveType == PrimitiveType.DOUBLE -> classIdOf<ParamSerializer.Double>()
      primitiveType == PrimitiveType.BOOLEAN -> classIdOf<ParamSerializer.Boolean>()
      else -> null
    }
  } ?: when {
    type.isClass<java.lang.Void>() -> classIdOf<ParamSerializer.NullType>()
    type.isNothing() -> classIdOf<ParamSerializer.NullType>()
    type.isNullableNothing() -> classIdOf<ParamSerializer.NullType>()
    type.isNullableString() -> classIdOf<ParamSerializer.String>()
    type.isString() -> classIdOf<ParamSerializer.String>()
    type.isClassStrict<kotlinx.datetime.LocalDate>() -> classIdOf<ParamSerializer.LocalDate>()
    type.isClassStrict<kotlinx.datetime.LocalTime>() -> classIdOf<ParamSerializer.LocalTime>()
    type.isClassStrict<kotlinx.datetime.LocalDateTime>() -> classIdOf<ParamSerializer.LocalDateTime>()
    type.isClassStrict<kotlinx.datetime.Instant>() -> classIdOf<ParamSerializer.Instant>()

    //type.isClassStrict<java.util.Date>() -> classIdOf<ParamSerializer.Date>(),

    /*
    val JDateEncoder: SqlEncoder<Session, Stmt, Date>
    val JLocalDateEncoder: SqlEncoder<Session, Stmt, LocalDate>
    val JLocalTimeEncoder: SqlEncoder<Session, Stmt, LocalTime>
    val JLocalDateTimeEncoder: SqlEncoder<Session, Stmt, LocalDateTime>
    val JZonedDateTimeEncoder: SqlEncoder<Session, Stmt, ZonedDateTime>
    val JInstantEncoder: SqlEncoder<Session, Stmt, Instant>
    val JOffsetTimeEncoder: SqlEncoder<Session, Stmt, OffsetTime>
    val JOffsetDateTimeEncoder: SqlEncoder<Session, Stmt, OffsetDateTime>
     */

    else -> null
  }
}

object ParseFree {
  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun match() =
    Ir.Call.FunctionMem0[ExtractorsDomain.Call.InterpolateInvoke[Is()], Is.of("invoke", "asPure", "asConditon", "asPureConditon")]

  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun parse(expr: IrExpression, components: List<IrExpression>, funName: String) = run {
    val segs = components.map { Seg.parse(it) }
    val (partsRaw, paramsRaw) =
      UnzipPartsParams<Seg>({ it is Seg.Const }, { a, b -> (a.constOrFail()).mergeWith(a.constOrFail()) }, { Seg.Const("") })
        .invoke(segs)
    val parts = partsRaw.map { it.constOrFail().value }
    val paramsExprs = paramsRaw.map { it.exprOrFail() }
    val paramsIrs = paramsExprs.map { paramExpr ->
      when {
        paramExpr.expr.isClass<SqlQuery<*>>() ->
          ParseQuery.parse(paramExpr.expr)
        paramExpr.expr.isClass<SqlAction<*, *>>() ->
          // Generally we REALLY don't like to create a Builder inside of the parser
          // because we should not be creating any IR inside of the parser but it is needed for the Elaborator
          // which is used inside of the action parsing.
          with(makeBuilderCtx()) {
            ParseAction.parse(paramExpr.expr, true)
          }
        else ->
          ParseExpression.parse(paramExpr.expr)
      }
    }
    //val paramsIrs = paramsExprs.map { ParseExpression.parse(it.expr) }
    when (funName) {
      "invoke" -> XR.Free(parts, paramsIrs, false, false, TypeParser.of(expr), expr.loc)
      "asPure" -> XR.Free(parts, paramsIrs, true, false, TypeParser.of(expr), expr.loc)
      "asConditon" -> XR.Free(parts, paramsIrs, false, false, XRType.BooleanExpression, expr.loc)
      "asPureConditon" -> XR.Free(parts, paramsIrs, true, false, XRType.BooleanExpression, expr.loc)
      else -> parseError("Unknown Interpolate function: ${funName}", expr)
    }
  }
}

context(CX.Scope)
fun ensureIsValidOp(expr: IrExpression, xExpr: IrExpression, yExpr: IrExpression, x: XR.Expression, y: XR.Expression, output: XR.Expression) {
  fun IrExpression.isGetTemporaryVar() =
    (this as? IrGetValue)?.symbol?.owner?.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE

  if (y.type.isProduct() && !(x is XR.Const.Null && yExpr.isGetTemporaryVar())) {
    parseError(
      "Invalid right-hand-side argument ${y.show()} (whose type was ${y.type.shortString()}) in the expression ${output.show()}. Cannot directly call operators (including null-checks) on variables representing composite types (i.e. rows-types and anything representing a group of columns) because this cannot be done in SQL. Instead, call the null-check on a column variable.",
      expr
    )
  }
  if (x.type.isProduct() && !(y is XR.Const.Null && xExpr.isGetTemporaryVar())) {
    parseError(
      "Invalid left-hand-side argument ${x.show()} (whose type was ${x.type.shortString()}) in the expression ${output.show()}.  Cannot directly call operators (including null-checks) on variables representing composite types (i.e. rows-types and anything representing a group of columns) because this cannot be done in SQL. Instead, call the null-check on a column variable.",
      expr
    )
  }
}
