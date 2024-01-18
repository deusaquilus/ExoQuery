package io.exoquery.xr

import io.exoquery.xr.XR.*

interface StatelessTransformerSingleRoot: StatelessTransformer {
  fun <X> root(xr: X): X where X: XR

  // Need to override the things that otherwise wouldn't go through the root
  override fun invoke(xr: Expression): Expression = super.invoke(root(xr))
  override fun invoke(xr: Query): Query = super.invoke(root(xr))
  override fun invoke(xr: Branch): Branch = super.invoke(root(xr))
  override fun invoke(xr: Variable): Variable = super.invoke(root(xr))
  override fun invoke(xr: XR): XR = super.invoke(root(xr))
}

interface StatelessTransformer {

  operator fun invoke(xr: XR): XR =
    with(xr) {
      when (this) {
        is XR.Expression -> invoke(this)
        is XR.Query -> invoke(this)
        // is XR.Action -> this.lift()
        is XR.Branch -> invoke(this)
        is XR.Variable -> invoke(this)
      }
    }

  operator fun invoke(xr: XR.Variable): XR.Variable = with(xr) { Variable(name, invoke(rhs)) }
  operator fun invoke(xr: XR.Branch): XR.Branch = with(xr) { XR.Branch(invoke(cond), invoke(then)) }
  operator fun invoke(xr: XR.Block): XR.Block = with(xr) { Block(stmts.map { invoke(it) }, invoke(output)) }

  operator fun invoke(xr: XR.Expression): XR.Expression =
    with(xr) {
      when (this) {
        is BinaryOp -> BinaryOp(invoke(a), op, invoke(b))
        is Const -> this
        is Function1 -> Function1(param, invoke(body))
        is FunctionN -> FunctionN(params, invoke(body))
        is FunctionApply -> FunctionApply(invoke(function), args.map { invoke(it) })
        is Ident -> this
        is IdentOrigin -> this
        is Property -> Property(invoke(of), name)
        is UnaryOp -> UnaryOp(op, invoke(expr))
        Const.Null -> this
        is When -> When(branches.map { invoke(it) }, invoke(orElse))
        is XR.Block -> invoke(this)
        is Product -> Product(name, fields.map { it.first to invoke(it.second) })
        // The below must go in Function/Query/Expression/Action invoke clauses
        is Marker -> this
      }
    }

  operator fun invoke(xr: XR.Query): XR.Query =
    with(xr) {
      when (this) {
        is FlatMap -> FlatMap(invoke(a), ident, invoke(b))
        is XR.Map -> XR.Map(invoke(a), ident, invoke(b))
        is Entity -> this
        is Filter -> Filter(invoke(a), ident, invoke(b))
        is Union -> Union(invoke(a), invoke(b))
        is UnionAll -> UnionAll(invoke(a), invoke(b))
        is Distinct -> Distinct(invoke(query))
        is DistinctOn -> DistinctOn(invoke(query), alias, invoke(by))
        is Drop -> Drop(invoke(query), invoke(num))
        is SortBy -> SortBy(invoke(query), alias, invoke(criteria), ordering)
        is Take -> Take(invoke(query), invoke(num))
        is FlatJoin -> FlatJoin(joinType, invoke(a), aliasA, invoke(on))
        is ConcatMap -> ConcatMap(invoke(a), ident, invoke(b))
        is GroupByMap -> GroupByMap(invoke(query), byAlias, invoke(byBody), mapAlias, invoke(mapBody))
        is Aggregation -> Aggregation(operator, invoke(body))
        is Nested -> Nested(invoke(query))
        // The below must go in Function/Query/Expression/Action invoke clauses
        is Marker -> this
      }
    }
}