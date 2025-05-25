package io.exoquery.norm

import io.exoquery.util.TraceConfig
import io.exoquery.util.TraceType
import io.exoquery.util.Tracer
import io.exoquery.xr.XR.*
import io.exoquery.xr.XR.Map // Make sure to explicitly have this import or Scala will use Map the collection
import io.exoquery.xr.XR
import io.exoquery.xr.*
import io.exoquery.xr.BetaReduction
import io.exoquery.xr.copy.*
import io.exoquery.xrError

data class Dealias(override val state: XR.Ident?, val traceConfig: TraceConfig) : StatefulTransformer<Ident?> {
  val trace: Tracer =
    Tracer(TraceType.Standard, traceConfig, 1)

  override operator fun invoke(q: Query): Pair<Query, StatefulTransformer<Ident?>> =
    with(q) {
      when (this) {
        is FlatMap -> {
          val (a, b, c, _) = dealias(head, id, body)
          val (cn, cnt) = invoke(c) // need to recursively dealias this clause e.g. if it is a map-clause that has another alias inside
          FlatMap.cs(a, b, cn) to cnt
        }

        is ConcatMap -> {
          val (a, b, c, _) = dealias(head, id, body)
          val (cn, cnt) = invoke(c)
          ConcatMap.cs(a, b, cn) to cnt
        }

        is Map -> {
          val (a, b, c, t) = dealias(head, id, body)
          Map.cs(a, b, c) to t
        }

        is Filter -> {
          val (a, b, c, t) = dealias(head, id, body)
          Filter.cs(a, b, c) to t
        }

        is SortBy -> {
          val (an, t) = invoke(head)
          val alias = t.state
          if (alias != null) {
            val retypedAlias = alias.copy(type = id.type)
            // We don't care about aliases from the sub-criteria clobbering each other. Even if there were physical queries
            // inside (which is not even possible) then they would still be in independent scopes.
            val cn = criteria.map { ord -> ord.transform { BetaReduction(it, id to retypedAlias).asExpr() } }
            SortBy.cs(an, id, cn) to Dealias(id, traceConfig)
          } else
            SortBy.cs(an, id, criteria) to Dealias(id, traceConfig)
        }

        is DistinctOn -> {
          val (a, b, c, t) = dealias(head, id, by)
          DistinctOn.cs(a, b, c) to t
        }

        is Take -> {
          val (an, ant) = invoke(head)
          Take.cs(an, num) to ant
        }

        is Drop -> {
          val (an, ant) = invoke(head)
          Drop.cs(an, num) to ant
        }

        is Union -> {
          val (an, _) = invoke(a)
          val (bn, _) = invoke(b)
          Union.cs(an, bn) to Dealias(null, traceConfig)
        }

        is UnionAll -> {
          val (an, _) = invoke(a)
          val (bn, _) = invoke(b)
          UnionAll.cs(an, bn) to Dealias(null, traceConfig)
        }

        is FlatJoin -> {
          val (head1, id1, on1) = dealias(head, id, on)
          FlatJoin.cs(head1, id1, on1) to Dealias(id1, traceConfig)
        }

        is Entity, is Distinct, is Nested, is FlatFilter, is FlatSortBy, is FlatGroupBy, is Free, is ExprToQuery, is TagForSqlQuery, is GlobalCall, is MethodCall -> {
          this to Dealias(null, traceConfig)
        }

        is CustomQueryRef -> {
          val (customQuery, state) = customQuery.handleStatefulTransformer(this@Dealias)
          CustomQueryRef.cs(customQuery) to state
        }
        is FunctionApply, is FunctionN, is Ident ->
          xrError("Dealiasing not supported (it should have been done already) for: ${this.showRaw()}")
      }
    }

  data class DealiasResultA(val a: Query, val b: Ident, val c: Expression, val newState: StatefulTransformer<Ident?>)
  data class DealiasResultB(val a: Query, val b: Ident, val c: Query, val newState: StatefulTransformer<Ident?>)

  data class DealiasResultGen<T>(val a: Query, val b: Ident, val c: T, val newState: StatefulTransformer<Ident?>)

  private fun dealias(a: Query, b: Ident, c: Expression): DealiasResultA {
    val (an, t) = invoke(a)
    val alias = t.state
    return when {
      alias != null -> {
        val retypedAlias = alias.copy(type = b.type)
        trace("Dealias (Q/Expr) $b into $retypedAlias").andLog()
        DealiasResultA(an, retypedAlias, BetaReduction(c, b to retypedAlias).asExpr(), t)
      }
      else ->
        DealiasResultA(a, b, c, Dealias(b, traceConfig))
    }
  }

  private fun dealias(a: Query, b: Ident, c: Query): DealiasResultB {
    val (an, t) = invoke(a)
    val alias = t.state
    return when {
      alias != null -> {
        val retypedAlias = alias.copy(type = b.type)
        trace("Dealias (Q/Q) $b into $retypedAlias").andLog()
        DealiasResultB(an, retypedAlias, BetaReduction.ofQuery(c, b to retypedAlias), t)
      }
      else ->
        DealiasResultB(a, b, c, Dealias(b, traceConfig))
    }
  }
}

class DealiasApply(val traceConfig: TraceConfig) {
  operator fun invoke(query: Query): Query =
    Dealias(null, traceConfig)(query).first
}
