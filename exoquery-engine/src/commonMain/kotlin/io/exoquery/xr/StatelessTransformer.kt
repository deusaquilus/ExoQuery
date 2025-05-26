package io.exoquery.xr

import io.exoquery.xr.XR.*
import io.exoquery.xr.XR.Map // Make sure to explicitly have this import or Scala will use Map the collection

interface StatelessTransformerSingleRoot : StatelessTransformer {
  fun <X> root(xr: X): X where X : XR

  // Need to override the things that otherwise wouldn't go through the root
  override fun invoke(xr: Expression): Expression = super.invoke(root(xr))
  override fun invoke(xr: Query): Query = super.invoke(root(xr))
  override fun invoke(xr: Branch): Branch = super.invoke(root(xr))
  override fun invoke(xr: Variable): Variable = super.invoke(root(xr))
  override fun invoke(xr: XR): XR = super.invoke(root(xr))
}


interface StatelessTransformer {

  operator fun invoke(xr: U.QueryOrExpression) =
    when (xr) {
      is XR.Query -> invoke(xr)
      is XR.Expression -> invoke(xr)
    }

  operator fun invoke(xr: XR): XR =
    with(xr) {
      when (this) {
        is Expression -> invoke(this)
        is Query -> invoke(this)
        is Branch -> invoke(this)
        is Variable -> invoke(this)
        is Action -> invoke(this)
        is Assignment -> invoke(this)
        is Batching -> invoke(this)
      }
    }

  fun invokeIdent(xr: XR.Ident) = xr

  operator fun invoke(xr: XR.Variable): XR.Variable = with(xr) { Variable.csf(name, invoke(rhs))(this) }
  operator fun invoke(xr: XR.Branch): XR.Branch = with(xr) { XR.Branch.csf(invoke(cond), invoke(then))(this) }
  operator fun invoke(xr: XR.Block): XR.Block = with(xr) { Block.csf(stmts.map { invoke(it) }, invoke(output))(this) }
  operator fun invoke(xr: XR.FunctionN): XR.FunctionN = with(xr) { FunctionN.csf(params.map { invokeIdent(it) }, invoke(body))(this) }
  operator fun invoke(xr: GlobalCall): GlobalCall = with(xr) { GlobalCall.csf(name, args.map { invoke(it) })(this) }
  operator fun invoke(xr: MethodCall): MethodCall = with(xr) { MethodCall.csf(invoke(head), name, args.map { invoke(it) })(this) }
  operator fun invoke(xr: XR.Free): Free = with(xr) { Free.csf(parts, params.map { invoke(it) })(this) }
  operator fun invoke(xr: XR.Window): XR.Window = with(xr) { Window.csf(partitionBy, orderBy, over)(this) }

  operator fun invoke(xr: XR.Expression): XR.Expression =
    with(xr) {
      when (this) {
        is BinaryOp -> BinaryOp.csf(invoke(a), op, invoke(b))(this)
        is Const -> this
        is Const.Null -> this
        is FunctionN -> FunctionN.csf(params, invoke(body))(this)
        is FunctionApply -> FunctionApply.csf(invoke(function), args.map { invoke(it) })(this)
        is Ident -> invokeIdent(this)
        is Property -> invoke(this)
        is UnaryOp -> UnaryOp.csf(op, invoke(expr))(this)
        is When -> When.csf(branches.map { invoke(it) }, invoke(orElse))(this)
        is Block -> invoke(this)
        is Product -> Product.csf(fields.map { it.first to invoke(it.second) })(this)
        // Free can both be Expression and Query
        is Free -> invoke(this)
        is Window -> invoke(this)
        is MethodCall -> invoke(this)
        is GlobalCall -> invoke(this)
        is QueryToExpr -> QueryToExpr.csf(invoke(head))(this)

        is TagForParam -> this
        is TagForSqlExpression -> this
      }
    }

  operator fun invoke(xr: XR.Property): XR.Property =
    Property.csf(invoke(xr.of), xr.name)(xr)

  operator fun invoke(xr: XR.Entity): XR.Entity = xr

  operator fun invoke(ord: XR.OrderField) =
    when (ord) {
      is XR.OrderField.By -> XR.OrderField.By(invoke(ord.field), ord.ordering)
      is XR.OrderField.Implicit -> XR.OrderField.Implicit(invoke(ord.field))
    }

  operator fun invoke(xr: XR.Query): XR.Query =
    with(xr) {
      when (this) {
        is FlatMap -> FlatMap.csf(invoke(head), invokeIdent(id), invoke(body))(this)
        is Map -> Map.csf(invoke(head), invokeIdent(id), invoke(body))(this)
        is Entity -> invoke(this)
        is Filter -> Filter.csf(invoke(head), invokeIdent(id), invoke(body))(this)
        is Union -> Union.csf(invoke(a), invoke(b))(this)
        is UnionAll -> UnionAll.csf(invoke(a), invoke(b))(this)
        is Distinct -> Distinct.csf(invoke(head))(this)
        is DistinctOn -> DistinctOn.csf(invoke(head), invokeIdent(id), invoke(by))(this)
        is Drop -> Drop.csf(invoke(head), invoke(num))(this)
        is SortBy -> SortBy.csf(invoke(head), invokeIdent(id), criteria.map { invoke(it) })(this)
        is Take -> Take.csf(invoke(head), invoke(num))(this)
        is FlatJoin -> FlatJoin.csf(invoke(head), invokeIdent(id), invoke(on))(this)
        is FlatGroupBy -> FlatGroupBy.csf(invoke(by))(this)
        is FlatSortBy -> FlatSortBy.csf(criteria.map { invoke(it) })(this)
        is FlatFilter -> FlatFilter.csf(invoke(by))(this)
        is ConcatMap -> ConcatMap.csf(invoke(head), invokeIdent(id), invoke(body))(this)
        is Nested -> Nested.csf(invoke(head))(this)
        is ExprToQuery -> ExprToQuery.csf(invoke(head))(this)
        // Free can both be Expression and Query
        is Free -> invoke(this)
        is TagForSqlQuery -> this
        is CustomQueryRef -> CustomQueryRef.csf(customQuery.handleStatelessTransform(this@StatelessTransformer))(this)
        is FunctionApply -> FunctionApply.csf(invoke(function), args.map { invoke(it) })(this)
        is Ident -> invokeIdent(this)
        is GlobalCall -> invoke(this)
        is MethodCall -> invoke(this)
      }
    }

  operator fun invoke(xr: XR.Action): XR.Action =
    with(xr) {
      when (this) {
        is Insert -> invoke(this)
        is Update -> Update.csf(invoke(query), invokeIdent(alias), assignments.map { invoke(it) }, exclusions.map { invoke(it) })(this)
        is Delete -> Delete.csf(invoke(query), invokeIdent(alias))(this)
        is OnConflict -> invoke(this)
        // TODO need to have invoke(action) here so that the Transformer will work on TransformAction, however need to think about potential consequences of casting here
        is FilteredAction -> FilteredAction.csf(invoke(action) as XR.U.CoreAction, invokeIdent(alias), invoke(filter))(this)
        is Returning -> Returning.csf(invoke(action), invoke(kind))(this)
        is Free -> Free.csf(parts, params.map { invoke(it) })(this)
        is TagForSqlAction -> this
      }
    }

  operator fun invoke(xr: XR.Batching): XR.Batching =
    XR.Batching.csf(invokeIdent(xr.alias), xr.action)(xr)

  operator fun invoke(xr: XR.Returning.Kind): XR.Returning.Kind =
    when (xr) {
      is XR.Returning.Kind.Expression -> XR.Returning.Kind.Expression(invokeIdent(xr.alias), invoke(xr.expr))
      is XR.Returning.Kind.Keys -> XR.Returning.Kind.Keys(xr.alias, xr.keys.map { invoke(it) })
    }

  operator fun invoke(xr: XR.Insert): XR.Insert =
    Insert.csf(invoke(xr.query), invokeIdent(xr.alias), xr.assignments.map { invoke(it) }, xr.exclusions.map { invoke(it) })(xr)

  operator fun invoke(xr: XR.Assignment): XR.Assignment =
    Assignment.csf(invoke(xr.property), invoke(xr.value))(xr)

  operator fun invoke(xr: XR.OnConflict): XR.OnConflict =
    OnConflict.csf(invoke(xr.insert), invoke(xr.target), invoke(xr.resolution))(xr)

  operator fun invoke(xr: XR.OnConflict.Target): XR.OnConflict.Target =
    when (xr) {
      is XR.OnConflict.Target.NoTarget -> xr
      is XR.OnConflict.Target.Properties -> XR.OnConflict.Target.Properties(xr.props.map { invoke(it) })
    }

  operator fun invoke(xr: XR.OnConflict.Resolution): XR.OnConflict.Resolution =
    when (xr) {
      is XR.OnConflict.Resolution.Ignore -> xr
      is XR.OnConflict.Resolution.Update -> XR.OnConflict.Resolution.Update(xr.excludedId, xr.existingParamIdent, xr.assignments.map { invoke(it) })
    }
}
