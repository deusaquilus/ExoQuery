package io.exoquery.norm

import io.exoquery.xr.StatelessTransformer
import io.exoquery.xr.*
import io.exoquery.xr.XR.*
import io.exoquery.xr.XR.Map
import io.exoquery.xr.XR
import io.exoquery.xr.copy.*
import io.exoquery.xrError

class NormalizeNestedStructures(val normalize: StatelessTransformer) {

  fun Query.nullIfSameAs(q: Query) =
    if (this == q) null else this

  operator fun invoke(q: Query): Query? =
    with(q) {
      when (this) {
        is Entity -> null
        is Map -> Map.cs(normalize(head), id, normalize(body)).nullIfSameAs(q)
        is FlatMap -> FlatMap.cs(normalize(head), id, normalize(body)).nullIfSameAs(q)
        is ConcatMap -> ConcatMap.cs(normalize(head), id, normalize(body)).nullIfSameAs(q)
        is Filter -> Filter.cs(normalize(head), id, normalize(body)).nullIfSameAs(q)
        is SortBy -> SortBy.cs(normalize(head), id, criteria.map { ord -> ord.transform { normalize(it) } }).nullIfSameAs(q)
        is Take -> Take.cs(normalize(head), normalize(num)).nullIfSameAs(q)
        is Drop -> Drop.cs(normalize(head), normalize(num)).nullIfSameAs(q)
        is Union -> Union.cs(normalize(a), normalize(b)).nullIfSameAs(q)
        is UnionAll -> UnionAll.cs(normalize(a), normalize(b)).nullIfSameAs(q)
        is Distinct -> Distinct.cs(normalize(head)).nullIfSameAs(q)
        is DistinctOn -> DistinctOn.cs(normalize(head), id, normalize(by)).nullIfSameAs(q)
        is Nested -> Nested.cs(normalize(head)).nullIfSameAs(q)

        is FlatJoin -> FlatJoin.cs(normalize(head), id, normalize(on)).nullIfSameAs(q)
        // Originally in Quill this was defined as:
        //      case FlatJoin(t, a, iA, on) =>
        //        (normalize(a), normalize(on)) match {
        //          case (`a`, `on`) => None
        //          case (a, on)     => Some(FlatJoin(t, a, iA, on))
        //        }
        //    }

        is FlatFilter -> FlatFilter.cs(normalize(by)).nullIfSameAs(q)
        is FlatGroupBy -> FlatGroupBy.cs(normalize(by)).nullIfSameAs(q)
        is FlatSortBy -> FlatSortBy.cs(criteria.map { it.transform { normalize(it) } }).nullIfSameAs(q)
        // Not sure why Quill didn't normalize Infixes (maybe it didn't matter because normalization is mainly for XR.Query)
        is Free -> Free.cs(parts, params.map { normalize(it) }).nullIfSameAs(q)
        is ExprToQuery -> null
        is TagForSqlQuery -> null
        is CustomQueryRef -> CustomQueryRef.cs(customQuery.handleStatelessTransform(normalize)).nullIfSameAs(q)

        is FunctionApply, is Ident ->
          xrError("Unexpected AST node (these should have already been beta reduced): $this")
        is GlobalCall -> GlobalCall.cs(args.map { normalize(it) }).nullIfSameAs(q)
        is MethodCall -> MethodCall.cs(normalize(head), args.map { normalize(it) }).nullIfSameAs(q)
      }
    }
}
