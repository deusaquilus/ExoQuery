package io.exoquery.plugin.trees

import io.decomat.Is
import io.decomat.case
import io.decomat.match
import io.decomat.on
import io.exoquery.BID
import io.exoquery.Params
import io.exoquery.SqlExpression
import io.exoquery.SqlQuery
import io.exoquery.annotation.DslFunctionCall
import io.exoquery.annotation.DslNestingIgnore
import io.exoquery.annotation.ParamCtx
import io.exoquery.annotation.ParamCustom
import io.exoquery.annotation.ParamCustomValue
import io.exoquery.annotation.ParamPrimitive
import io.exoquery.annotation.ParamStatic
import io.exoquery.parseError
import io.exoquery.plugin.classId
import io.exoquery.plugin.classIdOf
import io.exoquery.plugin.funName
import io.exoquery.plugin.getAnnotationArgs
import io.exoquery.plugin.isClass
import io.exoquery.plugin.isClassStrict
import io.exoquery.plugin.isSqlQuery
import io.exoquery.plugin.loc
import io.exoquery.plugin.location
import io.exoquery.plugin.locationXR
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.plugin.logging.Messages
import io.exoquery.plugin.logging.Messages.ValueLookupComingFromExternalInExpression
import io.exoquery.plugin.ownerHasAnnotation
import io.exoquery.plugin.printing.dumpSimple
import io.exoquery.plugin.safeName
import io.exoquery.plugin.show
import io.exoquery.plugin.toXR
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.transform.containsBatchParam
import io.exoquery.plugin.transform.dumpKotlinLikePretty
import io.exoquery.plugin.transform.isBatchParam
import io.exoquery.plugin.varargValues
import io.exoquery.serial.ParamSerializer
import io.exoquery.terpal.UnzipPartsParams
import io.exoquery.xr.`+and+`
import io.exoquery.xr.`+or+`
import io.exoquery.xr.CID
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.xr.isConverterFunction
import io.exoquery.xr.isNumeric
import io.exoquery.xr.of
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.jetbrains.kotlin.backend.common.lower.loops.isInductionVariable
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.utils.typeArguments
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrDeclarationReference
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.name.ClassId

/**
 * Parses the tree and collets dynamic binds as it goes. The parser should be exposed
 * as stateless to client functions so everything should go through the `Parser` object instead of this.
 */
object ParseExpression {
  internal sealed interface Seg {
    data class Const(val value: String) : Seg {
      fun mergeWith(other: Const): Const = Const(value + other.value)
    }
    data class Expr(val expr: IrExpression) : Seg
    companion object {
      fun parse(expr: IrExpression): Seg =
        when {
          expr.isClassStrict<String>() && expr is IrConst && expr.kind == IrConstKind.String -> Const(expr.value as String)
          else -> Expr(expr)
        }
    }
    context(CX.Scope, CX.Parsing, CX.Symbology)
    fun constOrFail(): Const =
      when (this) {
        is Const -> this
        is Expr -> parseError("Expected a constant segment, but found an expression segment: Seg.Expr(${expr.dumpKotlinLike()})", this.expr)
      }
    context(CX.Scope, CX.Parsing, CX.Symbology)
    fun exprOrFail(): Expr =
      when (this) {
        is Const -> parseError("Expected an expression segment, but found a constant segment: Seg.Const(${value})")
        is Expr -> this
      }
  }

  context(CX.Scope, CX.Parsing, CX.Symbology)
  private fun IrGetValue.isCurrentlyActiveBatchParam() =
    batchAlias != null && this.isBatchParam()

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseBlockStatement(expr: IrStatement): XR.Variable =
    on(expr).match(
      case(Ir.Variable[Is(), Is()]).thenThis { name, rhs ->
        val irType = TypeParser.of(this)
        val rhsXR = parse(rhs)
        XR.Variable(XR.Ident(name.sanitizeIdentName(), irType, rhs.locationXR()), rhsXR, expr.loc)
      }
    ) ?: parseError("Could not parse Ir Variable statement from:\n${expr.dumpSimple()}")

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseBranch(expr: IrBranch): XR.Branch =
    on(expr).match(
      case(Ir.Branch[Is(), Is()]).then { cond, then ->
        XR.Branch(parse(cond), parse(then), expr.loc)
      }
    ) ?: parseError("Could not parse Branch from: ${expr.dumpSimple()}")

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseFunctionBlockBody(blockBody: IrBlockBody): XR.Expression =
    blockBody.match(
      case(Ir.BlockBody.ReturnOnly[Is()]).then { irReturnValue ->
        parse(irReturnValue)
      },
      case(Ir.BlockBody.StatementsWithReturn[Is(), Is()]).then { stmts, ret ->
          val vars = stmts.map { parseBlockStatement(it) }
          val retExpr = parse(ret)
          XR.Block(vars, retExpr, blockBody.locationXR())
        }
    ) ?: parseError("Could not parse IrBlockBody:\n${blockBody.dumpKotlinLike()}")

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parse(expr: IrExpression): XR.Expression =
    on(expr).match<XR.Expression>(

      case(Ir.Call[Is()]).thenIf { it.ownerHasAnnotation<DslFunctionCall>() || it.ownerHasAnnotation<DslNestingIgnore>() }.then { call ->
        CallParser.parse(call).asExpr()
      },

      //case(Ir.Call.FunctionMem0[Ir.Expr.ClassOf<SqlQuery<*>>(), Is("isNotEmpty")]).then { sqlQueryIr, _ ->
      //  XR.QueryToExpr(QueryParser.parse(sqlQueryIr), sqlQueryIr.loc)
      //},

      // Converter functions for string e.g. toInt, toLong, etc.
      case(Ir.Call.FunctionMem0[Ir.Expr.ClassOf<String>(), Is { it.isConverterFunction() }]).then { head, method ->
        XR.MethodCall(parse(head), method, emptyList(), XR.CallType.PureFunction, CID.kotlin_String, XRType.Value, expr.loc)
      },

      // Numeric conversion functions toInt, toString, etc... on numeric types Int, Long, etc...
      case(Ir.Call.FunctionMem0[Is(), Is { it.isConverterFunction() }])
        .thenIf { head, _ -> head.type.classId()?.toXR()?.isNumeric() ?: false }
        .thenThis { head, method ->
          val classId = this.type.classId() ?: parseError("Cannot determine the classId of the type ${this.type.dumpKotlinLike()} of this expression.", this)
          XR.MethodCall(parse(head), method, emptyList(), XR.CallType.PureFunction, classId.toXR(), XRType.Value, expr.loc)
        },

      case(Ir.Call.FunctionMemN[Is { it.type.classId()?.let { MethodWhitelist.allowedHost(it) } ?: false }, Is(), Is()])
        .thenIfThis { _, _ -> MethodWhitelist.allowedMethodForHost(this.type.classId(), funName) }
        .thenThis { head, args ->
          val classId = this.type.classId() ?: parseError("Cannot determine the classId of the type ${this.type.dumpKotlinLike()} of this expression.", this)
          val argsXR = args.map { parse(it) }
          XR.MethodCall(parse(head), funName, argsXR, XR.CallType.PureFunction, classId.toXR(), XRType.Value, expr.loc)
        },

      case(Ir.Expr.ClassOf<SqlQuery<*>>()).then { expr ->
        // Even dynamic variables will be handled by this so don't need to do anything for dynamic SqlQuery instances here.
        XR.QueryToExpr(ParseQuery.parse(expr), expr.loc)
      },

      case(ExtractorsDomain.CaseClassConstructorCall[Is()]).then { data ->
        XR.Product(data.className, data.fields.map { (name, valueOpt) -> name to (valueOpt?.let { parse(it) } ?: XR.Const.Null(expr.loc)) }, expr.loc)
      },

      // parse lambda in a capture block
      case(Ir.FunctionExpression.withBlock[Is(), Is()]).thenThis { params, blockBody ->
        XR.FunctionN(params.map { it.makeIdent() }, parseFunctionBlockBody(blockBody), expr.loc)
      },

      case(Ir.Call.FunctionMemN[Ir.Expr.ClassOf<Function<*>>(), Is("invoke"), Is()]).thenThis { hostFunction, args ->
        XR.FunctionApply(parse(hostFunction), args.map { parse(it) }, expr.loc)
      },

      // TODO add the batch IrValueParameter to the ParseContext
      // TODO check if the component inside the Param contains the batch param (if it is used as a regular ident in the query we need to handle that too)
      // TODO if it is a batch param then add the ParamBind into a new ParamUsingBatchAlias and then add it to the binds
      // TODO also need to handle setParams case in parseAction where a batch-param is used
      case(Ir.Call.FunctionMemN[Is(), Is.of("param", "paramCtx", "paramCustom"), Is()]).thenThis { _, args ->
        val paramValue = args.first()
        val paramBindTypeRaw =
          when {
            this.ownerHasAnnotation<ParamStatic>() -> {
              val staticRef = this.symbol.owner.getAnnotationArgs<ParamStatic>().firstOrNull()?.let { param -> param as? IrClassReference
                ?: parseError("ParamStatic annotation must have a single argument that is a class-reference (e.g. PureFunction::class)", this)
              } ?: parseError("Could not find ParamStatic annotation", this)
              val classId = staticRef.classType.classId() ?: parseError("Could not find classId for ParamStatic annotation", this)
              ParamBind.Type.ParamStatic(classId)
            }
            this.ownerHasAnnotation<ParamCustom>() -> {
              // serializer should be the 2nd arg i.e. paramCustom(value, serializer)
              ParamBind.Type.ParamCustom(args.lastOrNull() ?: parseError("ParamCustom annotation must have a second argument that is a class-reference (e.g. PureFunction::class)", this), paramValue.type)
            }
            this.ownerHasAnnotation<ParamCustomValue>() -> {
              ParamBind.Type.ParamCustomValue(paramValue)
            }
            this.ownerHasAnnotation<ParamCtx>() -> {
              ParamBind.Type.ParamCtx(paramValue.type)
            }
            else -> parseError("Could not find Param annotation on the param function of the call", this)
          }

        val bid = BID.new()

        val varsUsed = IrTraversals.collectGetValue(paramValue)
        varsUsed.forEach { varUsed ->
          if (varUsed.isInternal() && !varUsed.isCurrentlyActiveBatchParam())
            parseError(
              """Cannot use the variable `${varUsed.symbol.safeName}` inside of a param(...) function because it originates inside of the capture-block. 
                |The `param` function is only used to bring external variables into the capture (i.e. runtime-variables that are defined outside of it). 
                |If you want to use the `${varUsed.symbol.safeName}` symbol inside this captured block, you should be able to use it directly.""".trimMargin(),
              varUsed
            )
        }

        val (paramBind, paramType) =
          if (batchAlias != null && varsUsed.any { it.isCurrentlyActiveBatchParam() }) {
            ParamBind.Type.ParamUsingBatchAlias(batchAlias, paramBindTypeRaw) to XR.ParamType.Batch
          } else {
            paramBindTypeRaw to XR.ParamType.Single
          }

        binds.addParam(bid, paramValue, paramBind)
        XR.TagForParam(bid, paramType, TypeParser.of(this), paramValue.loc)
      },

      // x.let { stuff(...it...) } -> Apply(stuff(...it...), x)

      case(Ir.Call.FunctionMem1[Is(), Is("let"), Is()]).thenThis { head, lambda ->
        val reciever = parse(head)
        val lambda: XR.FunctionN = parse(lambda).let { it as? XR.FunctionN ?: parseError("Expected a lambda function to be parsed from the let call but was:\n${it.showRaw()}", lambda) }
        XR.FunctionApply(lambda, listOf(reciever), expr.loc)
      },

      case(Ir.CastingTypeOperator[Is(), Is()]).thenThis { target, newType ->
        val callType: XR.CallType = XR.CallType.PureFunction
        XR.GlobalCall(XR.FqName.Cast, listOf(parse(target)), callType, TypeParser.of(this), this.loc) //, TypeParser.of(this), loc)
      },

      case(Ir.Call.FunctionMemN[Is(), Is.of("params", "paramsCtx", "paramsCustom"), Is()]).thenThis { _, args ->
        val paramValue = args.first()

        // TODO if a batch param is used then fail here since you cannot use batch queries with multi-params
        val paramBindType =
          when {
            // currently not used because the specific ones have been commented out. Waiting for @SignatureName in KMP
            this.ownerHasAnnotation<ParamStatic>() -> {
              val staticRef = this.symbol.owner.getAnnotationArgs<ParamStatic>().firstOrNull()?.let { param -> param as? IrClassReference
                ?: parseError("ParamStatic annotation must have a single argument that is a class-reference (e.g. PureFunction::class)", this)
              } ?: parseError("Could not find ParamStatic annotation", this)
              val classId = staticRef.classType.classId() ?: parseError("Could not find classId for ParamStatic annotation", this)
              ParamBind.Type.ParamListStatic(classId)
            }
            this.ownerHasAnnotation<ParamPrimitive>() -> {
              val irType = this.typeArguments.firstOrNull() ?: parseError("params-call must have a single type argument", this)
              val paramSerializerClassId = getSerializerForType(irType)
                ?: parseError(
                  "Could not find primitive-serializer for type: ${irType.dumpKotlinLike()}. Primitive serializers are only defined for: Int, Long, Float, Double, String, Boolean, and the kotlinx LocalDate, LocalTime, LocalDateTime, and Instant",
                  this
                )
              ParamBind.Type.ParamListStatic(paramSerializerClassId)
            }
            this.ownerHasAnnotation<ParamCustom>() -> {
              // serializer should be the 2nd arg i.e. paramCustom(value, serializer)
              val irType = this.typeArguments.firstOrNull() ?: parseError("params-call must have a single type argument", this)
              ParamBind.Type.ParamListCustom(args.lastOrNull() ?: parseError("ParamCustom annotation must have a second argument that is a class-reference (e.g. PureFunction::class)", this), irType)
            }
            this.ownerHasAnnotation<ParamCustomValue>() -> {
              ParamBind.Type.ParamListCustomValue(paramValue)
            }
            this.ownerHasAnnotation<ParamCtx>() -> {
              val irType = this.typeArguments.firstOrNull() ?: parseError("params-call must have a single type argument", this)
              ParamBind.Type.ParamListCtx(irType)
            }
            else -> parseError("Could not find Param annotation on the params function of the call", this)
          }

        val varsUsed = IrTraversals.collectGetValue(paramValue)
        varsUsed.forEach { varUsed ->
          if (varUsed.isCurrentlyActiveBatchParam()) {
            parseError("Cannot use the batch-parameter `${varUsed.symbol.safeName}` with multi-parameter functions (i.e. params, paramsCtx, paramsCustom, etc.). The batch-parameter is only used for single-parameter functions (i.e. param, paramCtx, paramCustom, etc.).", varUsed)
          }
          if (varUsed.isInternal())
            parseError(
              """Cannot use the variable `${varUsed.symbol.safeName}` inside of a param(...) function because it originates inside of the capture-block. 
                |The `param` function is only used to bring external variables into the capture (i.e. runtime-variables that are defined outside of it). 
                |If you want to use the `${varUsed.symbol.safeName}` symbol inside this captured block, you should be able to use it directly.""".trimMargin(),
              varUsed
            )
        }

        val bid = BID.Companion.new()
        binds.addParam(bid, paramValue, paramBindType)
        XR.TagForParam(bid, XR.ParamType.Multi, TypeParser.ofFirstArgOfReturnTypeOf(this), paramValue.loc)
      },

      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<Params<*>>(), Is("contains"), Is()]).thenThis { head, params ->
        val cid = head.type.classId()?.toXR() ?: parseError("Could not find classId for the head of the contains call", head)
        XR.MethodCall(parse(head), "contains", listOf(parse(params)), XR.CallType.PureFunction, cid, XRType.Value, expr.loc)
      },

      case(ParseFree.match()).thenThis { (components), _ ->
        ParseFree.parse(expr, components, funName)
      },

      case(Ir.Call.FunctionMem0[Is(), Is("value")]).thenIf { useExpr, _ -> useExpr.type.isClass<SqlQuery<*>>() }.then { sqlQueryIr, _ ->
        XR.QueryToExpr(ParseQuery.parse(sqlQueryIr), sqlQueryIr.loc)
      },

      case(ExtractorsDomain.Call.UseExpression.Receiver[Ir.Call.FunctionUntethered2[Is(PT.io_exoquery_util_scaffoldCapFunctionQuery), Is(), Is()]]).thenThis { (sqlExprArg, irVararg) ->
        processScaffolded(sqlExprArg, irVararg, expr)
      },

      // In certain odd situations (e.g. using a `@CatpuredFunction fun foo(p: Person) = capture.expression { flatJoin(Table<Address>, ...) }` inside of a other query
      // like so capture.select { val p = from(Person); val a = from(joinAddress(...)) }. We can have a scaffold without a proceeding use-function
      // need to handle that case.
      case(Ir.Call.FunctionUntethered2[Is(PT.io_exoquery_util_scaffoldCapFunctionQuery), Is(), Is()]).thenThis { sqlExprArg, irVararg ->
        processScaffolded(sqlExprArg, irVararg, expr)
      },

      // TODO check that the extension reciever is Ir.Expr.ClassOf<SqlExpression<*>> (and the dispatch method is CapturedBlock)
      // TODO make this into an annotated function similar to Param and move the matching into ExtractorsDomain
      case(ExtractorsDomain.Call.UseExpression.Receiver[Is()]).thenIf { useExpr -> useExpr.type.isClass<SqlExpression<*>>() }.then { sqlExprIr ->
        sqlExprIr.match(
          case(SqlExpressionExpr.Uprootable[Is()]).then { uprootable ->
            // Add all binds from the found SqlExpression instance, this will be truned into something like `currLifts + SqlExpression.lifts` late
            binds.addAllParams(sqlExprIr)
            // Then unpack and return the XR
            uprootable.unpackOrErrorXR().successOrParseError(sqlExprIr)
          },
          case(ExtractorsDomain.DynamicExprCall[Is()]).then { call ->
            val bid = BID.Companion.new()
            binds.addRuntime(bid, sqlExprIr)
            XR.TagForSqlExpression(bid, TypeParser.of(sqlExprIr), sqlExprIr.loc)
          },
        ) ?: parseError(Messages.CannotCallUseOnAnArbitraryDynamic(), sqlExprIr)
      },
      // Binary Operators
      case(ExtractorsDomain.Call.`x op y`[Is()]).thenThis { opCall ->
        fun IrExpression.isGetTemporaryVar() =
          (this as? IrGetValue)?.symbol?.owner?.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE

        val (xExpr, op, yExpr) = opCall
        val x = parse(xExpr)
        val y = parse(yExpr)
        val output = XR.BinaryOp(x, op, y, expr.loc)

        if (y.type.isProduct() && !(x is XR.Const.Null && yExpr.isGetTemporaryVar())) {
          parseError("Invalid right-hand-side argument ${y.show()} (whose type was ${y.type.shortString()}) in the expression ${output.show()}. Cannot directly call operators (including null-checks) on variables representing composite types (i.e. rows-types and anything representing a group of columns) because this cannot be done in SQL. Instead, call the null-check on a column variable.", expr)
        }
        if (x.type.isProduct() && !(y is XR.Const.Null && xExpr.isGetTemporaryVar())) {
          parseError("Invalid left-hand-side argument ${x.show()} (whose type was ${x.type.shortString()}) in the expression ${output.show()}.  Cannot directly call operators (including null-checks) on variables representing composite types (i.e. rows-types and anything representing a group of columns) because this cannot be done in SQL. Instead, call the null-check on a column variable.", expr)
        }

        output
      },
      // Unary Operators
      case(ExtractorsDomain.Call.`(op)x`[Is()]).thenThis { opCall ->
        val (x, op) = opCall
        XR.UnaryOp(op, parse(x), expr.loc)
      },

      case(ExtractorsDomain.Call.`x to y`[Is(), Is()]).thenThis { x, y ->
        XR.Product.Tuple(parse(x), parse(y), expr.loc)
      },

      // Other situations where you might have an identifier which is not an SqlVar e.g. with variable bindings in a Block (inside an expression)
      case(Ir.GetValue[Is()]).thenIfThis { this.isCapturedVariable() || this.isCapturedFunctionArgument() }.thenThis { sym ->
        if (this.isBatchParam()) parseError(Messages.batchParamError(), expr)
        XR.Ident(sym.sanitizedSymbolName(), TypeParser.of(this), this.locationXR()) // this.symbol.owner.type
      },
      case(Ir.Const[Is()]).thenThis {
        parseConst(this)
      },

      // TODO need to check for @SerialName("name_override") annotations from the Kotlin seriazation API and override the name
      //      (the parser also needs to be able to generated these based on a mapping)
      case(Ir.Call.Property[Is(), Is()]).thenThis { expr, propKind ->
        // If a batch-alias is being dereferenced should we potentially search inside of it? Might have performance implications
        if (expr.containsBatchParam()) parseError(Messages.batchParamError(), this)

        val core = parse(expr)
        when (propKind) {
          is Ir.Call.Property.PropertyKind.Named ->
            XR.Property(core, propKind.name, XR.Visibility.Visible, expr.loc)
          is Ir.Call.Property.PropertyKind.Component -> {
            (core.type as? XRType.Product)?.let { productType ->
              val field = productType.fields[propKind.index]?.first
                ?: parseError("Could not find field at index ${propKind.index} in product type ${productType.name}. The fields were: ${productType.fields.map { (fieldName, _) -> fieldName }.withIndex()}", expr)
              XR.Property(core, field, XR.Visibility.Visible, expr.loc)
            } ?: parseError("Component property can only be used on a product type but the IRType of the expression was: ${core.type}.\nThe expression was parsed as:\n${core.showRaw(false)}", expr)
          }
        }
      },

      // case(Ir.Call.Function[Is()]).thenIf { (list) -> list.size == 2 }.thenThis { list ->
      //   val a = list.get(0)
      //   val b = list.get(1)
      //   // TODO need to check that's its actually a binary operator!!!
      //   XR.BinaryOp(parse(a), parseSymbol(this.symbol), parse(b))
      // }
      // ,
      case(Ir.Block[Is(), Is()]).thenThis { stmts, ret ->
        val tpe = TypeParser.of(this)
        if (this.origin == IrStatementOrigin.ELVIS && tpe.isProduct()) {
          parseError("Elvis operator cannot be called on a composite type (i.e. rows-types and anything representing a group of columns) because this cannot be done in SQL", this)
        }
        XR.Block(stmts.map { parseBlockStatement(it) }, parse(ret), expr.loc)
      },
      case(Ir.When[Is()]).thenThis { cases ->
        val elseBranch = cases.find { it is IrElseBranch }?.let { parseBranch(it) }
        val casesAst = cases.filterNot { it is IrElseBranch }.map { parseBranch(it) }
        val allReturnsAreBoolean = cases.all { it.result.type.isClass<Boolean>() }
        // Kotlin converts (A && B) to `if(A) B else false`. This undoes that
        if (
            allReturnsAreBoolean &&
            elseBranch != null && casesAst.size == 1
              && casesAst.first().then.type is XRType.Boolean
              // Implicitly the else-clause in this case cannot have additional conditions
              && elseBranch.cond == XR.Const.Boolean(true) && elseBranch.then == XR.Const.Boolean(false)
          ) {
          val firstClause = casesAst.first()
          firstClause.cond `+and+` firstClause.then
        }
        // Kotlin converts (A || B) to `if(A) true else B`. This undoes that
        else if (
          allReturnsAreBoolean &&
          elseBranch != null && casesAst.size == 1
          && casesAst.first().then == XR.Const.Boolean(true)
          // Implicitly the else-clause in this case cannot have additional conditions
          && elseBranch.cond == XR.Const.Boolean(true)
        ) {
          val firstClause = casesAst.first()
          firstClause.cond `+or+` elseBranch.then
        }
        else {
          val elseBranchOrLast = elseBranch ?: casesAst.lastOrNull() ?: parseError("Empty when expression not allowed:\n${this.dumpKotlinLike()}")
          XR.When(casesAst, elseBranchOrLast.then, expr.loc)
        }
      },
    ) ?: run {
      val additionalHelp =
        when {
          expr is IrGetValue && expr.isExternal() -> ValueLookupComingFromExternalInExpression(expr, "expression")
          expr is IrCall && expr.isExternal() && expr.symbol.owner is IrSimpleFunction ->
            """|It looks like you are attempting to call the external function `${expr.symbol.safeName}` in a captured block
               |only functions specifically made to be interpreted by the ExoQuery system are allowed inside
               |of captured blocks. If you are trying to use a runtime-value of a primitive, you need to bring
               |it into the captured block by using `param(myCall(...))`. If this is an instance of SqlExpression then
               |use the `use` function to splice the value e.g. `myExpression.use`.
            """.trimMargin()

          else -> ""
        }

      parseError("Could not parse the expression." + (if (additionalHelp.isNotEmpty()) "\n${additionalHelp}" else ""), expr)
    }

  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun processScaffolded(sqlExprArg: IrExpression, irVararg: IrExpression, currentExpr: IrExpression) = run {
    val loc = currentExpr.loc
    //if (this.dumpKotlinLikePretty().contains("Table(Address).join { a -> p.id == a.ownerId }.toExpr")) {
    //  throw IllegalArgumentException("--------------------- HERE (${sqlExprArg.dumpKotlinLikePretty()}) --------------------")
    //}

    val wrappedExprCall =
      sqlExprArg.match(
        // It is possible to capture a SqlQuery<*> value inside an capture.expression. Handle that case.
        // The actual type of the expression in this case will be SqlExpression<SqlQuery<T>> so that's what we need to check for
        case(Ir.Expr.ClassOf<SqlExpression<*>>()).thenIf { sqlExprArg.type.simpleTypeArgs.firstOrNull()?.isClass<SqlQuery<*>>() ?: false }.then {

          ParseQuery.parse(sqlExprArg)
        },
        case(SqlExpressionExpr.Uprootable[Is()]).then { uprootable ->
          // Add all binds from the found SqlExpression instance, this will be truned into something like `currLifts + SqlExpression.lifts` late
          binds.addAllParams(sqlExprArg)
          // Then unpack and return the XR
          uprootable.unpackOrErrorXR().successOrParseError(sqlExprArg)
        },
        case(ExtractorsDomain.DynamicExprCall[Is()]).then { call ->
          val bid = BID.Companion.new()
          binds.addRuntime(bid, sqlExprArg)
          XR.TagForSqlExpression(bid, TypeParser.of(sqlExprArg), sqlExprArg.loc)
        },
      ) ?: parseError(Messages.CannotCallUseOnAnArbitraryDynamic(), sqlExprArg)
    val args = irVararg.varargValues()
    val parsedArgs = args.map { arg -> arg?.let { Parser.parseArg(it) } ?: XR.Const.Null(loc) }
    XR.FunctionApply(wrappedExprCall, parsedArgs, loc)
  }

  context(CX.Scope) fun parseConst(irConst: IrConst): XR.Expression =
    if (irConst.value == null) XR.Const.Null(irConst.loc)
    else when (irConst.kind) {
      IrConstKind.Null -> XR.Const.Null(irConst.loc)
      IrConstKind.Boolean -> XR.Const.Boolean(irConst.value as Boolean, irConst.loc)
      IrConstKind.Char -> XR.Const.Char(irConst.value as Char, irConst.loc)
      IrConstKind.Byte -> XR.Const.Byte(irConst.value as Int, irConst.loc)
      IrConstKind.Short -> XR.Const.Short(irConst.value as Short, irConst.loc)
      IrConstKind.Int -> XR.Const.Int(irConst.value as Int, irConst.loc)
      IrConstKind.Long -> XR.Const.Long(irConst.value as Long, irConst.loc)
      IrConstKind.String -> XR.Const.String(irConst.value as String, irConst.loc)
      IrConstKind.Float -> XR.Const.Float(irConst.value as Float, irConst.loc)
      IrConstKind.Double -> XR.Const.Double(irConst.value as Double, irConst.loc)
      else -> parseError("Unknown IrConstKind: ${irConst.kind}")
    }


}
