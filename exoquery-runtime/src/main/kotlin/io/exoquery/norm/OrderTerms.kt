package io.exoquery.norm

import io.exoquery.util.TraceConfig
import io.exoquery.xr.XR
import io.exoquery.xr.XR.*
import io.decomat.*
import io.exoquery.xr.*

class OrderTerms(val traceConfig: TraceConfig) {

  operator fun invoke(xr: XR.Query): XR.Query? =
    xr.match(
      // a.sortBy(b => c).filter(d => e) =>
      //     a.filter(d => e).sortBy(b => c)
      //
      // Scala:
      // case Filter(SortBy(a, b, c), d, e) =>
      //     Some(SortBy(Filter(a, d, e), b, c))
      case(Filter[SortBy[Is(), Is()], Is()]).then { (a, b, c), d, e ->
        SortBy.csf(compLeft)(Filter.csf(comp)(a, b, c), d, e, compLeft.ordering)
      },

      // a.flatMap(b => c).take(n).map(d => e) =>
      //   a.flatMap(b => c).map(d => e).take(n)
      //
      // Scala:
      // Map(Take(fm: FlatMap, n), ma, mb) =>
      //   Some(Take(Map(fm, ma, mb), n))
      case(XR.Map[Take[Is<FlatMap>(), Is()], Is()]).then { (fm, n), ma, mb ->
        Take.csf(compLeft).invoke(XR.Map.csf(comp)(fm, ma, mb), n)
      },

      // a.flatMap(b => c).drop(n).map(d => e) =>
      //   a.flatMap(b => c).map(d => e).drop(n)
      //
      // Scala:
      // Map(Drop(fm: FlatMap, n), ma, mb) =>
      //    Some(Drop(Map(fm, ma, mb), n))
      case(XR.Map[Drop[Is<FlatMap>(), Is()], Is()]).then { (fm, n), ma, mb ->
        Drop.csf(compLeft).invoke(XR.Map.csf(comp)(fm, ma, mb), n)
      }
    )
}