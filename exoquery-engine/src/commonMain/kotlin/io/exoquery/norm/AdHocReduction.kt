package io.exoquery.norm

import io.exoquery.xr.XR.*
import io.exoquery.xr.XR.Map
import io.exoquery.xr.*
import io.exoquery.xr.copy.*
import io.decomat.*
import io.exoquery.util.TraceConfig

// NOTE: Leaving Quill commneted-out code equivalents here for now for reference
class AdHocReduction(val traceConfig: TraceConfig) {

  operator fun invoke(q: Query): XR.Query? =
    on(q).match(
      // ---------------------------
      // *.filter

      // a.filter(b => c).filter(d => e) =>
      //    a.filter(b => c && e[d := b])
      //
      // Filter(Filter(a, b, c), d, e) =>
      case(Filter[Filter[Is(), Is()], Is()]).thenThis { (a, b, c), d, e ->
        val er = BetaReduction(e, d to b)
        Filter.cs(a, b, XR.BinaryOp(c, OP.And, er.asExpr()))
      },
      // ---------------------------
      // flatMap.*

      // a.flatMap(b => c).map(d => e) =>
      //    a.flatMap(b => c.map(d => e))
      //
      // Map(FlatMap(a, b, c), d, e) =>
      case(Map[FlatMap[Is(), Is()], Is()]).then { (a, b, c), d, e ->
        FlatMap.csf(a, b, XR.Map.csf(c, d, e)(comp))(compLeft)
      },
      // a.flatMap(b => c).filter(d => e) =>
      //    a.flatMap(b => c.filter(d => e))
      //
      // case Filter(FlatMap(a, b, c), d, e) =>
      case(Filter[FlatMap[Is(), Is()], Is()]).then { (a, b, c), d, e ->
        FlatMap.csf(a, b, Filter.csf(c, d, e)(comp))(compLeft)
      },

      // SymbolicReduction already does these
      //// a.flatMap(b => c.union(d))
      ////    a.flatMap(b => c).union(a.flatMap(b => d))
      ////
      //// case FlatMap(a, b, Union(c, d)) =>
      //case(FlatMap[Is(), Union[Is(), Is()]]).then { a, b, (c, d) ->
      //  Union.csf(FlatMap.csf(a, b, c)(comp), FlatMap.csf(a, b, d)(comp))(compRight)
      //},
      //// a.flatMap(b => c.unionAll(d))
      ////    a.flatMap(b => c).unionAll(a.flatMap(b => d))
      ////
      //// case FlatMap(a, b, UnionAll(c, d)) =>
      //case(FlatMap[Is(), UnionAll[Is(), Is()]]).then { a, b, (c, d) ->
      //  UnionAll.csf(FlatMap.csf(a, b, c)(comp), FlatMap.csf(a, b, d)(comp))(compRight)
      //}
    )
}

/*
  def unapply(q: Query) =
    q match {

      // ---------------------------
      // *.filter

      // a.filter(b => c).filter(d => e) =>
      //    a.filter(b => c && e[d := b])
      case Filter(Filter(a, b, c), d, e) =>
        val er = BetaReduction(e, d -> b)
        Some(Filter(a, b, BinaryOperation(c, BooleanOperator.`&&`, er)))

      // ---------------------------
      // flatMap.*

      // a.flatMap(b => c).map(d => e) =>
      //    a.flatMap(b => c.map(d => e))
      case Map(FlatMap(a, b, c), d, e) =>
        Some(FlatMap(a, b, Map(c, d, e)))

      // a.flatMap(b => c).filter(d => e) =>
      //    a.flatMap(b => c.filter(d => e))
      case Filter(FlatMap(a, b, c), d, e) =>
        Some(FlatMap(a, b, Filter(c, d, e)))

      // a.flatMap(b => c.union(d))
      //    a.flatMap(b => c).union(a.flatMap(b => d))
      case FlatMap(a, b, Union(c, d)) =>
        Some(Union(FlatMap(a, b, c), FlatMap(a, b, d)))

      // a.flatMap(b => c.unionAll(d))
      //    a.flatMap(b => c).unionAll(a.flatMap(b => d))
      case FlatMap(a, b, UnionAll(c, d)) =>
        Some(UnionAll(FlatMap(a, b, c), FlatMap(a, b, d)))

      case other => None
    }
 */
