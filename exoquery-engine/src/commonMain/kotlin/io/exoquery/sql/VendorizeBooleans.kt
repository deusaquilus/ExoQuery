package io.exoquery.sql

import io.exoquery.xr._EqEq_
import io.exoquery.xr._And_
import io.exoquery.xr._Or_
import io.exoquery.xr.BinaryOperator
import io.exoquery.xr.OP
import io.exoquery.xr.StatelessTransformer
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.xr.copy.BinaryOp
import io.exoquery.xr.copy.Filter
import io.exoquery.xr.copy.FlatJoin
import io.exoquery.xr.copy.When
import io.exoquery.xr.copy.Map
import io.exoquery.xr.copy.Product
import io.exoquery.xr.copy.UnaryOp
import io.exoquery.xr.copy.cs
import io.exoquery.xr.csf
import io.exoquery.xr.isOperatorOnExpressions
import io.exoquery.xr.isOperatorOnValues

object VendorizeBooleans : StatelessTransformer {
  //  override def apply(ast: Ast): Ast =
  //    ast match {
  //      // Map clauses need values e.g. map(n=>n.status==true) => map(n=>if(n.status==true) 1 else 0)
  //      case Map(q, alias, body) =>
  //        Map(apply(q), alias, valuefyExpression(apply(body)))
  //      case Filter(q, alias, body) =>
  //        Filter(apply(q), alias, expressifyValue(apply(body)))
  //      case FlatJoin(typ, a, aliasA, on) =>
  //        FlatJoin(typ, a, aliasA, expressifyValue(apply(on)))
  override fun invoke(xr: XR.Query): XR.Query =
    with(xr) {
      when (this) {
        // Map clauses need values e.g. map(n=>n.status==true) => map(n=>if(n.status==true) 1 else 0)
        is XR.Map -> Map.cs(invoke(head), id, valuefyExpression((body)))
        // Filter clauses need expressions e.g. filter(n=>n.isTrue) becomes filter(n=>n.isTrue==1)
        is XR.Filter -> Filter.cs(invoke(head), id, expressifyValue((body)))
        // FlatJoin clauses need expressions e.g. flatJoin(n=>n.isTrue) becomes flatJoin(n=>n.isTrue==1)
        is XR.FlatJoin -> FlatJoin.cs(invoke(head), id, expressifyValue((on)))
        is XR.FlatFilter -> XR.FlatFilter.csf(expressifyValue((by)))(this)
        else -> super.invoke(xr)
      }
    }


  override fun invoke(xr: XR.Expression): XR.Expression =
    with(xr) {
      when {
        // Things that have ||, && between them are typically expressions, things like "true || e.isSomething"
        // need to be converted to "true == true || e.isSomething == true" so they are
        // tokenized as "1 == 1 || e.isSomething == 1"
        this is XR.BinaryOp && isOperatorOnExpressions(op) ->
          BinaryOp.cs(expressifyValue((a)), op, expressifyValue((b)))
        this is XR.BinaryOp && isOperatorOnValues(op) ->
          BinaryOp.cs(valuefyExpression((a)), op, valuefyExpression((b)))
        // Example: "q.filter(e => !e.isSomething)" which needs to be converted to
        // "q.filter(e => !(e.isSomething == 1))" so it can be tokenized to "... WHERE e.isSomething = 1
        this is XR.UnaryOp && op == OP.Not ->
          UnaryOp.cs(OP.Not, expressifyValue((expr)))

        this is XR.Product ->
          Product.cs(fields.map { (name, value) -> name to valuefyExpression((value)) })

        this is XR.When -> transformWhen(this)

        else -> super.invoke(xr)
      }
    }

  fun transformWhen(xr: XR.When): XR.When =
    with(xr) {
      When.cs(
        branches.map {
          XR.Branch.csf(expressifyValue((it.cond)), valuefyExpression((it.then)))(it)
        }, valuefyExpression((orElse))
      )
    }

  fun valuefyExpression(xr: XR.Expression): XR.Expression =
    with(xr) {
      when {
        // SQL considers a WHEN clause to always return a value as opposed to the XR which considers
        // it's type to be whatever the THEN/ELSE clause is. So we need to treat it as a value here.
        this is XR.When ->
          transformWhen(this)
        type is XRType.BooleanExpression ->
          XR.When.makeIf(invoke(this), XR.Const.Boolean(true), XR.Const.Boolean(false), loc)
        else ->
          invoke(xr)
      }
    }

  fun XR.When.allPartsBoolean(): Boolean =
    branches.all { it.then.isBoolean() } && orElse.isBoolean()

  fun XR.When.reduceToExpression(): XR.Expression = run {
    val conds = branches.map { expressifyValue(it.cond) }.reduce { a, b -> a _And_ b }
    val condThens = branches.map { expressifyValue(it.cond) _And_ expressifyValue(it.then) }
    val elseExpr = expressifyValue(orElse)
    condThens.reduce { a, b -> a _And_ b } _Or_ (XR.UnaryOp(OP.Not, conds) _And_ elseExpr)
  }


  /*
   * Generally speaking you need to add true==X to some X which is a boolean-value to make it a boolean-expression
   * For example:
   * SELECT ... WHERE person.isSober
   *   => SELECT ... WHERE true==person.isSober
   * However if you know the expression is conditional you can do better:
   * SELECT ... WHERE CASE WHEN person.isSober THEN canDrinkMoreHere ELSE canGoToAnotherClub
   *   => SELECT ... WHERE (person.isSober && canDrinkMoreHere) || (!person.isSober && canGoToAnotherClub)
   */
  fun expressifyValue(xr: XR.Expression): XR.Expression =
    with(xr) {
      when {
        this is XR.When && this.allPartsBoolean() ->
          this.reduceToExpression()
        // A regular-when-clause is always considered to return a value, in the XR
        // it is classified as a BooleanExpression when the return-type of it is a BooleanExpression but
        // that is not the way that SQL understands it.
        this is XR.When ->
          XR.Const.Boolean(true) _EqEq_ invoke(xr)
        this is XR.BinaryOp && isOperatorOnExpressions(op) ->
          BinaryOp.cs(expressifyValue(a), op, expressifyValue(b))
        this.isBooleanValue() ->
          XR.Const.Boolean(true) _EqEq_ invoke(xr)
        else ->
          invoke(xr)
      }
    }


//  def expressifyValue(ast: Ast): Ast = ast match {
//    case If(condition, HasBooleanQuat(thenClause), HasBooleanQuat(elseClause)) =>
//      val condExpr = expressifyValue(condition)
//      val thenExpr = expressifyValue(thenClause)
//      val elseExpr = expressifyValue(elseClause)
//      (condExpr +&&+ thenExpr) +||+ (UnaryOperation(BooleanOperator.`!`, condExpr) +&&+ elseExpr)
//    case HasBooleanValueQuat(ast) =>
//      Constant(true, BooleanValue) +==+ ast
//    case _ =>
//      ast
//  }
}
