package io.exoquery.xr

/**
 * Similar to the transformers but it just checks if the XR matches a certain condition
 * and has early exit if the condition is true. This could fairly easily
 * be done with a collect + stateful transformer, but that is far less efficient.
 */
interface StatelessChecker {
  fun check(xr: XR): Boolean

  fun checkOrNull(xr: XR): Boolean? =
    if (check(xr)) true else null

  operator fun invoke(xr: XR.U.QueryOrExpression): Boolean =
    checkOrNull(xr) ?: when (xr) {
      is XR.Query -> invoke(xr)
      is XR.Expression -> invoke(xr)
    }

  operator fun invoke(xr: XR): Boolean =
    checkOrNull(xr) ?: with(xr) {
      when (this) {
        is XR.Expression -> invoke(this)
        is XR.Query -> invoke(this)
        is XR.Branch -> invoke(this)
        is XR.Variable -> invoke(this)
        is XR.Action -> invoke(this)
        is XR.Assignment -> invoke(this)
        is XR.Batching -> invoke(this)
      }
    }

  fun invokeIdent(xr: XR.Ident): Boolean = check(xr)

  operator fun invoke(xr: XR.Variable): Boolean = check(xr)
  operator fun invoke(xr: XR.Branch): Boolean = checkOrNull(xr) ?: with(xr) { invoke(cond) || invoke(then) }
  operator fun invoke(xr: XR.Block): Boolean = checkOrNull(xr) ?: xr.stmts.any { invoke(it) } || invoke(xr.output)
  operator fun invoke(xr: XR.FunctionN): Boolean = checkOrNull(xr) ?: xr.params.any { invoke(it as XR.Expression) } || invoke(xr.body)
  operator fun invoke(xr: XR.GlobalCall): Boolean = checkOrNull(xr) ?: xr.args.any { invoke(it) }
  operator fun invoke(xr: XR.MethodCall): Boolean = checkOrNull(xr) ?: invoke(xr.head) || xr.args.any { invoke(it) }
  operator fun invoke(xr: XR.Free): Boolean = checkOrNull(xr) ?: xr.params.any { invoke(it) }
  operator fun invoke(xr: XR.Window): Boolean = checkOrNull(xr) ?: xr.partitionBy.any { invoke(it) } || xr.orderBy.any { invoke(it.field) } || invoke(xr.over)

  operator fun invoke(xr: XR.Expression): Boolean =
    checkOrNull(xr) ?: with(xr) {
      when (this) {
        is XR.BinaryOp -> invoke(a) || invoke(b)
        is XR.Const -> check(this)
        is XR.Const.Null -> check(this)
        is XR.FunctionN -> params.any { invoke(it as XR.Expression) } || invoke(body)
        is XR.FunctionApply -> invoke(function) || args.any { invoke(it) }
        is XR.Ident -> invokeIdent(this)
        is XR.Property -> invoke(this)
        is XR.UnaryOp -> invoke(expr)
        is XR.When -> branches.any { invoke(it) } || invoke(orElse)
        is XR.Block -> stmts.any { invoke(it) } || invoke(output)
        is XR.Product -> fields.any { invoke(it.second) }
        // Free can both be Expression and Query
        is XR.Free -> invoke(this)
        is XR.Window -> partitionBy.any { invoke(it) } || orderBy.any { invoke(it.field) } || invoke(over)
        is XR.MethodCall -> invoke(this)
        is XR.GlobalCall -> invoke(this)
        is XR.QueryToExpr -> invoke(head)

        is XR.TagForParam -> check(this)
        is XR.TagForSqlExpression -> check(this)
      }
    }

  operator fun invoke(xr: XR.Property): Boolean =
    checkOrNull(xr) ?: invoke(xr.of)

  operator fun invoke(xr: XR.Entity): Boolean = check(xr)

  operator fun invoke(ord: XR.OrderField): Boolean =
    when (ord) {
      is XR.OrderField.By -> invoke(ord.field)
      is XR.OrderField.Implicit -> invoke(ord.field)
    }

  operator fun invoke(xr: XR.Query): Boolean =
    checkOrNull(xr) ?: with(xr) {
      when (this) {
        is XR.FlatMap -> invoke(head) || invokeIdent(id) || invoke(body)
        is XR.Map -> invoke(head) || invokeIdent(id) || invoke(body)
        is XR.Entity -> invoke(this)
        is XR.Filter -> invoke(head) || invokeIdent(id) || invoke(body)
        is XR.Union -> invoke(a) || invoke(b)
        is XR.UnionAll -> invoke(a) || invoke(b)
        is XR.Distinct -> invoke(head)
        is XR.DistinctOn -> invoke(head) || invokeIdent(id) || invoke(by)
        is XR.Drop -> invoke(head) || invoke(num)
        is XR.SortBy -> invoke(head) || invokeIdent(id) || criteria.any { invoke(it) }
        is XR.Take -> invoke(head) || invoke(num)
        is XR.FlatJoin -> invoke(head) || invokeIdent(id) || invoke(on)
        is XR.FlatGroupBy -> invoke(by)
        is XR.FlatSortBy -> criteria.any { invoke(it) }
        is XR.FlatFilter -> invoke(by)
        is XR.ConcatMap -> invoke(head) || invokeIdent(id) || invoke(body)
        is XR.Nested -> invoke(head)
        is XR.ExprToQuery -> invoke(head)
        // Free can both be Expression and Query
        is XR.Free -> invoke(this)
        is XR.TagForSqlQuery -> check(this)
        is XR.CustomQueryRef -> false // Don't care about this for now, will assume it was already transformed into an XR
        is XR.FunctionApply -> args.any { invoke(it) }
        is XR.Ident -> check(xr)

        is XR.GlobalCall -> args.any { invoke(it) }
        is XR.MethodCall -> invoke(head) || args.any { invoke(it) }
      }
    }

  operator fun invoke(xr: XR.Action): Boolean =
    checkOrNull(xr) ?: with(xr) {
      when (this) {
        is XR.Insert -> invoke(this)
        is XR.Update -> invoke(query) || invokeIdent(alias) || assignments.any { invoke(it) } || exclusions.any { invoke(it) }
        is XR.Delete -> invoke(query) || invokeIdent(alias)
        is XR.OnConflict -> invoke(this)
        is XR.FilteredAction -> invoke(action) || invokeIdent(alias) || invoke(filter)
        is XR.Returning -> invoke(action)
        is XR.Free -> params.any { invoke(it) }
        is XR.TagForSqlAction -> check(this)
      }
    }

  operator fun invoke(xr: XR.Batching): Boolean =
    checkOrNull(xr) ?: with(xr) {
      invokeIdent(alias) || invoke(action)
    }

  operator fun invoke(xr: XR.Returning.Kind): Boolean =
    when (xr) {
      is XR.Returning.Kind.Expression -> invokeIdent(xr.alias) || invoke(xr.expr)
      is XR.Returning.Kind.Keys -> xr.keys.any { invoke(it) }
    }

  operator fun invoke(xr: XR.Insert): Boolean =
    checkOrNull(xr) ?: invoke(xr.query) || invokeIdent(xr.alias) || xr.assignments.any { invoke(it) } || xr.exclusions.any { invoke(it) }

  operator fun invoke(xr: XR.Assignment): Boolean =
    checkOrNull(xr) ?: invoke(xr.property) || invoke(xr.value)

  operator fun invoke(xr: XR.OnConflict): Boolean =
    checkOrNull(xr) ?: invoke(xr.insert) || invoke(xr.target) || invoke(xr.resolution)

  operator fun invoke(xr: XR.OnConflict.Target): Boolean =
    when (xr) {
      is XR.OnConflict.Target.NoTarget -> false
      is XR.OnConflict.Target.Properties -> xr.props.any { invoke(it) }
    }

  operator fun invoke(xr: XR.OnConflict.Resolution): Boolean =
    when (xr) {
      is XR.OnConflict.Resolution.Ignore -> false
      is XR.OnConflict.Resolution.Update -> xr.assignments.any { invoke(it) }
    }
}
