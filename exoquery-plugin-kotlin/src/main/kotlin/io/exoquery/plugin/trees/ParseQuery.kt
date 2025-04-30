package io.exoquery.plugin.trees

import io.decomat.Is
import io.decomat.case
import io.decomat.match
import io.decomat.on
import io.exoquery.*
import io.exoquery.annotation.*
import io.exoquery.plugin.*
import io.exoquery.plugin.logging.Messages
import io.exoquery.plugin.printing.dumpSimple
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.transform.isBatchParam
import io.exoquery.xr.BetaReduction
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.xr.of
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

object ParseQuery {

  context(CX.Scope, CX.Parsing, CX.Symbology)
  private fun processQueryLambda(head: IrExpression, lambda: IrExpression) =
    lambda.match(
      case(Ir.FunctionExpression.withBlock[Is(), Is()]).thenThis { _, blockBody ->
        val headXR = parse(head)
        val firstParam = firstParam().makeIdent()
        val tailExpr = ParseExpression.parseFunctionBlockBody(blockBody)
        Triple(headXR, firstParam, tailExpr)
      }
    )

  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun IrCall.isDslMethod() =
    this.symbol.owner.let { it.hasAnnotation<Dsl>() } ?: false

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parse(expr: IrExpression): XR.Query =
    when {
      // We don't want arbitrary functions returning SqlQuery to be treated as dynamic so we make sure they are annotated with @Dsl
      // this processes everything like that.
      expr is IrCall && (expr.ownerHasAnnotation<DslFunctionCall>() || expr.ownerHasAnnotation<DslNestingIgnore>()) ->
        CallParser.parse(expr).asQuery() ?: parseError("Could not parse the DslCall", expr)

      expr is IrCall && expr.isDslMethod() ->
        parseDslCall(expr) ?: parseError("Could not parse the DSL call", expr)

      else -> {
        on(expr).match<XR.Query>(
          // Not sure why it doesn't detect this as a DSL-call (so that it can be put into parseDslCall)
          case(ParseFree.match()).thenThis { (components), _ ->
            ParseFree.parse(expr, components, funName)
          },

          // TODO need to make sure 1st arg is SqlQuery instance and also the owner function has a @CapturedFunction annotate
          //     (also parser should check for any IrFunction that has a @CapturedFunction annotation that doesn't have scaffolding and immediately report an error on that)
          case(Ir.Call.FunctionUntethered2[Is(PT.io_exoquery_util_scaffoldCapFunctionQuery), Is(), Ir.Vararg[Is()]]).thenThis { sqlQueryArg, (args) ->
            val loc = this.loc
            val warppedQueryCall =
              sqlQueryArg.match(
                case(SqlQueryExpr.Uprootable[Is()]).thenThis { uprootable ->
                  val sqlQueryIr = this
                  // Add all binds from the found SqlQuery instance, this will be truned into something like `currLifts + SqlQuery.lifts` late
                  binds.addAllParams(sqlQueryIr)
                  // Then unpack and return the XR
                  uprootable.unpackOrErrorXR().successOrParseError(sqlQueryArg)
                },
                case(ExtractorsDomain.DynamicQueryCall[Is()]).then { _ ->
                  val bid = BID.Companion.new()
                  binds.addRuntime(bid, sqlQueryArg)
                  // need to type the parse
                  XR.TagForSqlQuery(bid, TypeParser.of(sqlQueryArg), expr.loc)
                }
              ) ?: parseError("Could not parse the SqlQuery from the scaffold call", sqlQueryArg)

            //logger.warn("---------------- Processing Scaffold Whole Expression---------------\n${expr.dumpKotlinLike()}\n--------------------- Query Arg --------------------------\n${sqlQueryArg.dumpKotlinLike()}\n--------------------- Args ------------------\n${args.map { it.dumpKotlinLike() }.joinToString("\n------------\n")}", sqlQueryArg)

            val parsedArgs = args.map { arg -> arg?.let { Parser.parseArg(it) } ?: XR.Const.Null(loc) }

            XR.FunctionApply(warppedQueryCall, parsedArgs, expr.loc)
          },

          case(Ir.GetValue[Is()]).thenIfThis { this.isCapturedVariable() || this.isCapturedFunctionArgument() }.thenThis { sym ->
            if (this.isBatchParam()) parseError(Messages.UsingBatchParam, expr)
            XR.Ident(sym.sanitizedSymbolName(), TypeParser.of(this), this.locationXR())
          },
          // If we couldn't parse the expression treat (and it is indeed a SqlQuery<*> treat it as dynamic i.e. non-uprootable
          // Since there's no splice-operator for SqlQuery like there is .use for SqlExpression (i.e. the variable/function-call is used directly)
          // if nothing else matches the expression, we need to look at it in a couple of different ways and then find out if it is a dynamic query
          // TODO When QueryMethodCall and QueryGlobalCall are introduced need to revisit this to see what happens if there is a dynamic call on a query
          //      and how to differentitate it from something that we want to capture. Perhaps we would need some kind of "query-method whitelist"
          case(SqlQueryExpr.Uprootable[Is()]).thenThis { uprootable ->
            val sqlQueryIr = this
            // Add all binds from the found SqlQuery instance, this will be truned into something like `currLifts + SqlQuery.lifts` late
            binds.addAllParams(sqlQueryIr)
            // Then unpack and return the XR
            uprootable.unpackOrErrorXR().successOrParseError(sqlQueryIr)
          },
          // TODO check that the output is a SqlQuery and pass to this parser instead of expression parser?
          case(Ir.Call.FunctionMemN[Ir.Expr.ClassOf<Function<*>>(), Is("invoke"), Is()]).thenThis { hostFunction, args ->
            XR.FunctionApply(ParseExpression.parse(hostFunction), args.map { ParseExpression.parse(it) }, expr.loc)
          },
          case(ExtractorsDomain.DynamicQueryCall[Is()])
            .thenIf {
              // We don't want arbitrary functions returning SqlQuery to be treated as dynamic (e.g. right now I am working on parsing for .nested
              // and since it doesn't exist yet this case is being hit). Make the user either annotate the type or the function with @CapturedReturn
              // in order to know we shuold actually be doing this. Should use a similar strategy for QueryMethodCall and QueryGlobalCall
              //it.type.hasAnnotation<CapturedDynamic>() || it.type.hasAnnotation(FqName("io.exoquery.Captured"))
              true
            }.then { _ ->
              if (expr is IrGetValue && expr.isBatchParam()) parseError(Messages.UsingBatchParam, expr)
              if (expr is IrGetValue && expr.symbol.owner is IrFunction)
                logger.warn(Messages.VariableComingFromNonCapturedFunction(expr, expr.ownerFunName ?: "<???>"), expr)

              val bid = BID.Companion.new()
              binds.addRuntime(bid, expr)
              XR.TagForSqlQuery(bid, TypeParser.of(expr), expr.loc)
            }
        ) ?: run {
          val additionalHelp =
            when {
              expr is IrCall && expr.isExternal() && expr.isSqlQuery() && expr.symbol.owner is IrSimpleFunction ->
                """|It looks like you are attempting to call the external function `${expr.symbol.safeName}` in a captured block
                   |only functions specifically made to be interpreted by the ExoQuery system are allowed inside
                   |of captured blocks. If you are trying to use a runtime-value in the query stored it in a variable
                   |first and then pass it into the block.
                """.trimMargin()

              expr is IrGetValue ->
                """|It looks like the variable `${expr.symbol.safeName}` is coming from outside the capture/select block
                   |but it could not be parsed as a static or dynamic query call of type SqlQuery<T>. We detected that
                   |it's type is ${expr.type.dumpKotlinLike()} which cannot be used (${expr.type.isClass<SqlQuery<*>>()}, ${expr.symbol.owner.type.annotations.map { it.dumpKotlinLike() }}).
                   |
                   |(Lineage: ${expr.showLineage()})
                """.trimMargin()
              else -> ""
            }

          parseError("Could not parse the Query." + (if (additionalHelp.isNotEmpty()) "\n${additionalHelp}" else ""), expr)
        }
      }
    }

  // Assuming everything that gets into here is already annotated with @Dsl
  context(CX.Scope, CX.Parsing, CX.Symbology) private fun parseDslCall(expr: IrExpression): XR.Query? =
    // Note, every single instance being parsed here shuold be of SqlQuery<*>, should check for that as an entry sanity-check
    on(expr).match<XR.Query>(
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is.of("map", "concatMap", "filter"), Is()]).thenThis { head, lambda ->
        val (head, id, body) = processQueryLambda(head, lambda) ?: parseError("Could not parse XR.Map/ConcatMap/Filter", expr)
        when (symName) {
          "map" -> XR.Map(head, id, body, expr.loc)
          "concatMap" -> XR.ConcatMap(head, id, body, expr.loc)
          "filter" -> XR.Filter(head, id, body, expr.loc)
          else -> parseError("Unknown SqlQuery method call: ${symName} in: ${expr.dumpKotlinLike()}", expr)
        }
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is("where"), Ir.FunctionExpression.withBlock[Is(), Is()]]).then { head, (_, body) ->
        val ident = compRight.function.symbol.owner.extensionReceiverParameter?.makeIdent() ?: parseError("Could not parse the this-receiver for the where clause", expr)
        // Since the identifier for where clauses is typically going to be $this$where we want to replace with something more innocous e.g. 'x'
        val whereBodyRaw = ParseExpression.parseFunctionBlockBody(body)
        val identX = ident.copy(name = "x")
        val whereBody = BetaReduction(whereBodyRaw, ident to identX).asExpr()
        XR.Filter(parse(head), identX, whereBody, expr.loc)
      },
      // There are some situations where someone could do capture.expression { ... SqlQuery } and we need to handle that
      // later injecting that into a query-capture with a .use function. For example capture.expression { { p: Person -> flatJoin(addresses, ...) } }.
      // we need to handle those cases.
      case(ExtractorsDomain.Call.UseExpression.Receiver[Is()]).thenThis { head ->
        ParseExpression.parse(head).asQuery()
      },
      case(Ir.Call.FunctionMemN[Is(), Is.of("flatJoin", "flatJoinLeft"), Is()]).thenIfThis { _, _ -> ownerHasAnnotation<FlatJoin>() || ownerHasAnnotation<FlatJoinLeft>() }.thenThis { _, args ->
        on(args[1]).match(
          case(Ir.FunctionExpression.withBlock[Is(), Is()]).thenThis { params, block ->
            val cond = ParseExpression.parseFunctionBlockBody(block)
            val id = params.first().makeIdent()
            when (symName) {
              "flatJoin" -> XR.FlatJoin(XR.JoinType.Inner, ParseQuery.parse(args[0]), id, cond, expr.loc)
              "flatJoinLeft" -> XR.FlatJoin(XR.JoinType.Left, ParseQuery.parse(args[0]), id, cond, expr.loc)
              else -> parseError("Invalid flatJoin method: ${symName}", expr)
            }
          }
        ) ?: parseError("Could not parse flatJoin", expr)
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is("flatMap"), Is()]).thenThis { head, lambda ->
        lambda.match(
          case(Ir.FunctionExpression.withReturnOnlyBlock[Is()]).thenThis { tail ->
            XR.FlatMap(parse(head), firstParam().makeIdent(), parse(tail), expr.loc)
          }
          // TODO for this error message need to have a advanced "mode" that will print out the RAW IR
        ) ?: parseError(
          "SqlQuery.flatMap(...) lambdas can only be single-statement expressions, they cannot be block-lambdas like:\n${lambda.dumpKotlinLike()}\n-----------------------------------\n${lambda.dumpSimple()}",
          lambda
        )
      },
      case(Ir.Call.FunctionMem0[Ir.Expr.ClassOf<SqlQuery<*>>(), Is.of("distinct", "nested")]).thenThis { head, _ ->
        when (symName) {
          "distinct" -> XR.Distinct(parse(head), expr.loc)
          "nested" -> XR.Nested(parse(head), expr.loc)
          else -> parseErrorSym(this)
        }
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is("distinctOn"), Is()]).thenThis { head, lambda ->
        val (head, id, body) = processQueryLambda(head, lambda) ?: parseError("Could not parse distinctBy", expr)
        XR.DistinctOn(head, id, body, expr.loc)
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is.of("sortedBy", "sortedByDescending"), Ir.FunctionExpression.withBlock[Is(), Is()]]).thenThis { head, (params, body) ->
        when (symName) {
          "sortedBy" -> XR.SortBy(parse(head), params.first().makeIdent(), ParseExpression.parseFunctionBlockBody(body), XR.Ordering.Asc, expr.loc)
          "sortedByDescending" -> XR.SortBy(parse(head), params.first().makeIdent(), ParseExpression.parseFunctionBlockBody(body), XR.Ordering.Desc, expr.loc)
          else -> parseError("Invalid sortedBy method: ${symName}", expr)
        }
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is.of("take", "drop"), Is()]).thenThis { head, num ->
        when (symName) {
          "take" -> XR.Take(parse(head), ParseExpression.parse(num), expr.loc)
          "drop" -> XR.Drop(parse(head), ParseExpression.parse(num), expr.loc)
          else -> parseError("Invalid take/drop method: ${symName}", expr)
        }
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlQuery<*>>(), Is.of("union", "unionAll", "plus"), Is()]).thenThis { head, tail ->
        val tailXR = parse(tail)
        when (symName) {
          "union" -> XR.Union(parse(head), tailXR, expr.loc)
          "unionAll" -> XR.UnionAll(parse(head), tailXR, expr.loc)
          "plus" -> XR.UnionAll(parse(head), tailXR, expr.loc)
          else -> parseError("Invalid union method: ${symName}", expr)
        }
      },
      case(Ir.Call.FunctionMem0[Ir.Expr.ClassOf<CapturedBlock>(), Is("Table")]).thenThis { _, _ ->
        entityFromType(this.typeArguments[0] ?: parseError("Type arguemnt of Table() call was not found>"), this.location())
      },
      // This is the select defined in the capture-block (that returns SqlQuery<T> as opposed to the top-level one which returns @Captured SqlQuery<T>.
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<CapturedBlock>(), Is("select"), Ir.FunctionExpression[Is()]]).thenThis { _, (selectLambda) ->
        XR.CustomQueryRef(ParseSelectClause.parseSelectLambda(selectLambda))
      }
    )

  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun entityFromType(type: IrType, location: CompilerMessageSourceLocation): XR.Entity {
    val tpe = TypeParser.ofTypeAt(type, location)
    val tpeProd = tpe as? XRType.Product ?: parseError("Table<???>() call argument type must be a data-class, but was: ${tpe}", location)
    return XR.Entity(tpeProd.name, tpeProd, location.toLocationXR())
  }

  context(CX.Scope, CX.Parsing, CX.Symbology)
  fun parseEntity(type: IrType, location: CompilerMessageSourceLocation): XR.Entity {
    val tpe = TypeParser.ofTypeAt(type, location)
    val tpeProd = tpe as? XRType.Product ?: parseError("Table<???>() call argument type must be a data-class, but was: ${tpe}", location)
    return XR.Entity(tpeProd.name, tpeProd, location.toLocationXR())
  }
}
