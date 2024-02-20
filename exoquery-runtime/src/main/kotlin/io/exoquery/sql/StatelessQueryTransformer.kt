package io.exoquery.sql

import io.exoquery.xr.XRType

abstract class StatelessQueryTransformer {
  open fun invoke(q: SqlQuery, topLevelQuat: XRType = XRType.Unknown): SqlQuery =
    invoke(q, QueryLevel.Top(topLevelQuat))

  protected open fun invoke(q: SqlQuery, level: QueryLevel): SqlQuery =
    with (q) {
      when(this) {
        is FlattenSqlQuery -> expandNested(this, level)
        is SetOperationSqlQuery -> SetOperationSqlQuery(invoke(a, level), op, invoke(b, level), type)
        is UnaryOperationSqlQuery -> UnaryOperationSqlQuery(op, invoke(query, level), type)
      }
    }

  protected abstract fun expandNested(q: FlattenSqlQuery, level: QueryLevel): FlattenSqlQuery


  protected open fun expandContext(s: FromContext): FromContext =
    with (s) {
      when(this) {
        is QueryContext -> QueryContext(invoke(query, QueryLevel.Inner), alias)
        is FlatJoinContext -> FlatJoinContext(joinType, expandContext(from), on)
        is TableContext, is InfixContext -> this
      }
    }
}
