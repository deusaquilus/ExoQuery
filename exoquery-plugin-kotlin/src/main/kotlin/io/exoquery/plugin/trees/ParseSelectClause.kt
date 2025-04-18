package io.exoquery.plugin.trees

import io.decomat.*
import io.exoquery.Ord
import io.exoquery.SelectClauseCapturedBlock
import io.exoquery.parseError
import io.exoquery.plugin.loc
import io.exoquery.plugin.location
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.plugin.printing.dumpSimple
import io.exoquery.plugin.safeName
import io.exoquery.plugin.toLocationXR
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.trees.ExtractorsDomain.Call.`x to y`
import io.exoquery.xr.BetaReduction
import io.exoquery.xr.SX
import io.exoquery.xr.SelectClause
import io.exoquery.xr.XR
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn

object ParseSelectClause {

  context(CX.Scope, CX.Parsing, CX.Symbology) fun processSelectLambda(statementsFromRet: List<IrStatement>, loc: CompilerMessageSourceLocation): SelectClause {
    if (statementsFromRet.isEmpty()) parseError("A select-clause usually should have two statements, a from(query) and an output. This one has neither", loc) // TODO provide example in the error
    if (statementsFromRet.last() !is IrReturn) parseError("A select-clause must return a plain (i.e. not SqlQuery) value.", loc)
    val ret = statementsFromRet.last()
    val retXR = ParseExpression.parse((ret as IrReturn).value)
    if (ret !is IrReturn) parseError("The last statement in a select-clause must be a return statement", ret) // TODO provide example in the error
    val statementsFrom = statementsFromRet.dropLast(1)
    if (statementsFrom.isEmpty()) SelectClause.justSelect(retXR, loc.toLocationXR())

    val statementsToParsed = statementsFrom.map { parseSubClause(it) to it }
    return ValidateAndOrganize(statementsToParsed, retXR)
  }

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseSelectLambda(lambda: IrStatement): SelectClause =
    lambda.match(
      // this typically happens when the top-level select is called
      case(Ir.FunctionExpression.withBlockStatements[Is(), Is()]).thenThis { _, statementsFromRet ->
        processSelectLambda(statementsFromRet, lambda.location())
      },
      // this typiclally happens when select inside of a CapturedBlock
      case(Ir.SimpleFunction[Is(), Is()]).thenThis { _, body ->
        processSelectLambda(body.statements, lambda.location())
      }
    ) ?: parseError("Could not parse Select Clause from: ${lambda.dumpSimple()}", lambda)

  // need to test case of `select { from(x.map(select { ... })) }` to see how nested recursion works
  // also test case of `select { from(select { ... } }` to see how nested recursion works
  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseSubClause(expr: IrStatement): SX =
    on(expr).match<SX>(
      case(Ir.Variable[Is(), Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SelectClauseCapturedBlock>(), Is("from"), Is()]]).thenThis { varName, (_, table) ->
        val id = XR.Ident(varName.sanitizeIdentName(), TypeParser.of(this), this.loc)
        SX.From(id, ParseQuery.parse(table))
      },
      case(Ir.Variable[Is(), Ir.Call.FunctionMem2[ExtractorsDomain.IsSelectFunction(), Is { it == "join" || it == "joinLeft" }, Is()]]).then { varName, (_, args) ->
        val joinFunc = compRight
        val (onTable, joinCondLambda) = args
        val varNameIdent = XR.Ident(varName.sanitizeIdentName(), TypeParser.of(this.comp), this.comp.loc)
        val joinType =
          when(joinFunc.symbol.safeName) {
            "join" -> XR.JoinType.Inner
            "joinLeft" -> XR.JoinType.Left
            else -> parseError("Unknown Join Type: ${joinFunc.symbol.safeName}", expr)
          }
        joinCondLambda.match(
          case(Ir.FunctionExpression.withBlock[Is(), Is()]).then { lambdaParams, stmtsAndReturn ->
            val lambdaParam = lambdaParams.first()
            val lambdaVarName = lambdaParam.sanitizedSymbolName() /* join lambda should have only one element e.g. join(Table<Addresses>()){addressesLambdaVar ->addressesLambdaVar == 123} */
            val lambdaVarIdent = XR.Ident(lambdaVarName.sanitizeIdentName(), TypeParser.of(lambdaParam), lambdaParam.loc)
            val joinCond = ParseExpression.parseFunctionBlockBody(stmtsAndReturn)
            SX.Join(joinType, varNameIdent, ParseQuery.parse(onTable), lambdaVarIdent, joinCond, joinFunc.loc).swapItVariableForOuter()
          }
        ) ?: parseError("Could not parse Join Lambda from: ${joinCondLambda.dumpSimple()}", joinCondLambda)
      },
      // Generally speaking, arbitrary variables are not allowed in select clauses but an exception is made for deconstruction e.g:
      // val (p, a) = from(select { from; join; people to addresses })
      // This will yield:
      // val <destruct>: Pair<Person, Address> = from(select { from; join; people to addresses })
      // val p: Person = <destruct>.component1()
      // val a: Address = <destruct>.component2()
      // Then p and a are used in the rest of the select clause normally so we need to know to appropriately treat the p and a situations
      case(Ir.Variable[Is(), Is()]).thenThis { varName, rhs ->
        val id = XR.Ident(varName.sanitizeIdentName(), TypeParser.of(this), this.loc)
        val rhsExpr = ParseExpression.parse(rhs)
        SX.ArbitraryAssignment(id, rhsExpr, this.loc)
      },

      // where(() -> Boolean)
      case(Ir.Call.FunctionMem1[ExtractorsDomain.IsSelectFunction(), Is("where"), Ir.FunctionExpression.withBlock[Is(), Is()]]).thenThis { _, (_, body) ->
        val whereCond = ParseExpression.parseFunctionBlockBody(body)
        SX.Where(whereCond, this.loc)
      },
      // where(Boolean)
      case(Ir.Call.FunctionMem1[ExtractorsDomain.IsSelectFunction(), Is("where"), Ir.Expr.ClassOf<Boolean>()]).thenThis { _, argValue ->
        SX.Where(ParseExpression.parse(argValue), this.loc)
      },
      // groupBy(...Any)
      case(Ir.Call.FunctionMemVararg[ExtractorsDomain.IsSelectFunction(), Is("groupBy"), Is(), Is()]).thenThis { _, argValues ->
        val groupings = argValues.map { ParseExpression.parse(it) }
        if (groupings.size == 1) {
          SX.GroupBy(groupings.first(), this.loc)
        }
        else {
          SX.GroupBy(XR.Product.TupleSmartN(groupings, this.loc), this.loc)
        }
      },
      // sortBy(...Pair<*, Ord>)
      case(Ir.Call.FunctionMemVararg[ExtractorsDomain.IsSelectFunction(), Is("sortBy"), Ir.Type.ClassOfType<Pair<*, *>>(), Is()]).thenThis { _, argValues ->
        val clausesRaw = argValues.map { ParseOrder.parseOrdTuple(it) }
        if (clausesRaw.size == 1) {
          val (expr, ord) = clausesRaw.first()
          SX.SortBy(expr, ord, this.loc)
        }
        else {
          val (exprs, clauses) = clausesRaw.unzip()
          SX.SortBy(XR.Product.TupleSmartN(exprs, this.loc), XR.Ordering.TupleOrdering(clauses), this.loc)
        }
      },
    ) ?: parseError("Could not parse Select Clause from: ${expr.dumpSimple()}", expr)


  object ParseOrder {
    // Can either be `x to Ord` or Pair(x, Ord)
    context(CX.Scope, CX.Parsing, CX.Symbology) fun parseOrdTuple(expr: IrExpression): Pair<XR.Expression, XR.Ordering> =
      expr.match(
        case(`x to y`[Is(), Is()]).thenThis { property, ord ->
          val propertyXR = ParseExpression.parse(property)
          if (propertyXR.type.isProduct()) {
            parseError("You cannot order by `${propertyXR.show()}` because this is composite type (i.e. a type that consists of multiple columns). You must order by a single column.", property)
          }
          propertyXR to parseOrd(ord)
        }
      ) ?: parseError("Could not parse a proper ordering from the expression: ${expr.dumpSimple()}. Orderings must always come in the form `property to Ord` for example `person.name to Desc`.", expr)

    context(CX.Scope, CX.Parsing, CX.Symbology) fun parseOrd(expr: IrExpression): XR.Ordering =
      expr.match(
        case(Ir.Expr.ClassOf<Ord.Asc>()).then { XR.Ordering.Asc },
        case(Ir.Expr.ClassOf<Ord.Desc>()).then { XR.Ordering.Desc },
        case(Ir.Expr.ClassOf<Ord.AscNullsFirst>()).then { XR.Ordering.AscNullsFirst },
        case(Ir.Expr.ClassOf<Ord.DescNullsFirst>()).then { XR.Ordering.DescNullsFirst },
        case(Ir.Expr.ClassOf<Ord.AscNullsLast>()).then { XR.Ordering.AscNullsLast },
        case(Ir.Expr.ClassOf<Ord.DescNullsLast>()).then { XR.Ordering.DescNullsLast },
      ) ?: parseError("Could not parse an ordering from the expression: ${expr.dumpSimple()}. Orderings must be specified as one of the following compile-time constant values: Asc, Desc, AscNullsFirst, DescNullsFirst, AscNullsLast, DescNullsLast", expr)
  }
}
