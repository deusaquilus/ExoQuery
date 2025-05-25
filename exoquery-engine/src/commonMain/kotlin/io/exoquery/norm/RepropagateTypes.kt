package io.exoquery.norm

import io.exoquery.util.TraceConfig
import io.exoquery.util.TraceType
import io.exoquery.util.Tracer
import io.exoquery.util.outerZipWith
import io.exoquery.xr.*
import io.exoquery.xr.XR
import io.exoquery.xr.XR.*
import io.exoquery.xr.XRType.*
import io.exoquery.xr.TypeBehavior.ReplaceWithReduction as RWR
import io.exoquery.xr.copy.*

// NOTE: Leaving Quill commneted-out code equivalents here for now for reference
class RepropagateTypes(val traceConfig: TraceConfig) : StatelessTransformer {
  val trace: Tracer = Tracer(TraceType.RepropagateTypes, traceConfig, 1)

  fun XRType.retypeFrom(other: XRType): XRType =
    when {
      this is BooleanValue && other is BooleanExpression -> BooleanValue
      this is BooleanExpression && other is BooleanValue -> BooleanValue
      this is Value && other is BooleanValue -> BooleanValue
      this is Value && other is BooleanExpression -> Value
      this is BooleanValue && other is Value -> BooleanValue
      this is BooleanExpression && other is Value -> Value
      this is XRType.Product && other is XRType.Product -> retypeProduct(other)
      else -> other
    }

//  def retypeFrom(other: Quat): Quat =
//      (q, other) match {
//        case (Quat.BooleanValue, Quat.BooleanExpression) => Quat.BooleanValue
//        case (Quat.BooleanExpression, Quat.BooleanValue) => Quat.BooleanValue
//        case (Quat.Value, Quat.BooleanValue)             => Quat.BooleanValue
//        case (Quat.Value, Quat.BooleanExpression)        => Quat.Value
//        case (Quat.BooleanValue, Quat.Value)             => Quat.BooleanValue
//        case (Quat.BooleanExpression, Quat.Value)        => Quat.Value
//        case (me: Quat.Product, other: Quat.Product)     => me.retypeProduct(other)
//        case (_, other)                                  => other
//      }


  fun XRType.Product.retypeProduct(other: XRType.Product): XRType.Product {
    val q = this
    val newFieldsIter =
      q.fieldsHash().outerZipWith(other.fieldsHash()) { key, thisQuat, otherQuat ->
        when {
          thisQuat != null && otherQuat != null -> Pair(key, thisQuat.retypeFrom(otherQuat))
          thisQuat != null && otherQuat == null -> Pair(key, thisQuat)
          thisQuat == null && otherQuat != null -> Pair(key, otherQuat)
          else -> throw IllegalStateException("Unexpected state")
        }
      }
    val newFields = linkedMapOf(*newFieldsIter.toTypedArray())
    // Note, some extra renames from properties that don't exist could make it here.
    // Need to make sure to ignore extra ones when they are actually applied.
    return XRType.Product(q.name, newFields.toList())
  }

//  def retypeProduct(other: Quat.Product): Quat.Product = {
//      val newFieldsIter =
//        q.fields.outerZipWith(other.fields) {
//          case (key, Some(thisQuat), Some(otherQuat)) => (key, thisQuat.retypeFrom(otherQuat))
//          case (key, Some(value), None)               => (key, value)
//          case (key, None, Some(value))               => (key, value)
//        }
//      val newFields = mutable.LinkedHashMap(newFieldsIter.toList: _*)
//      // Note, some extra renames from properties that don't exist could make it here.
//      // Need to make sure to ignore extra ones when they are actually applied.
//      Quat.Product(q.name, newFields).withRenames(other.renames).withType(newTpe)
//    }

  fun XR.Ident.retypeFrom(from: XRType): XR.Ident =
    this.copy(type = this.type.retypeFrom(from))


//  implicit final class IdentExt(id: Ident) {
//    def retypeQuatFrom(from: Quat): Ident =
//      id.copy(quat = id.quat.retypeFrom(from))
//  }

  fun applyBody(a: XR.Query, b: XR.Ident, c: XR.Query, f: (XR.Query, XR.Ident, XR.Query) -> XR.Query): XR.Query = run {
    val ar = invoke(a)
    val br = b.retypeFrom(ar.type)
    val cr = BetaReduction.ofQuery(c, RWR, b to br)
    trace("Repropagate ${a.type.shortString()} from ${a} into:") andReturn { f(ar, br, invoke(cr)) }
  }

  fun applyBody(a: XR.Query, b: XR.Ident, c: XR.Expression, f: (XR.Query, XR.Ident, XR.Expression) -> XR.Query): XR.Query = run {
    val ar = invoke(a)
    val br = b.retypeFrom(ar.type)
    val cr = BetaReduction(c, RWR, b to br)
    trace("Repropagate ${a.type.shortString()} from ${a} into:") andReturn { f(ar, br, invoke(cr).asExpr()) }
  }


//  def applyBody(a: Ast, b: Ident, c: Ast)(f: (Ast, Ident, Ast) => Query): Query = {
//    val ar = apply(a)
//    val br = b.retypeQuatFrom(ar.quat)
//    val cr = BetaReduction(c, RWR, b -> br)
//    trace"Repropagate ${a.quat.suppress(msg)} from ${a} into:" andReturn f(ar, br, apply(cr))
//  }

  override fun invoke(e: Query): Query =
    with(e) {
      when (this) {
        is Filter -> applyBody(head, id, body) { a, b, c -> Filter.cs(a, b, c) }
        is XR.Map -> applyBody(head, id, body) { a, b, c -> Map.cs(a, b, c) }
        is FlatMap -> applyBody(head, id, body) { a, b, c -> FlatMap.cs(a, b, c) }
        is ConcatMap -> applyBody(head, id, body) { a, b, c -> ConcatMap.cs(a, b, c) }
        is DistinctOn -> applyBody(head, id, by) { a, b, c -> DistinctOn.cs(a, b, c) }
        is SortBy -> {
          val ar = invoke(head)
          val idr = id.retypeFrom(ar.type)
          val cr = criteria.map { ord -> ord.transform { BetaReduction(it, RWR, id to idr).asExpr() } }
          trace("Repropagate ${head.type.shortString()} from $head into:") andReturn { SortBy.cs(ar, idr, cr.map { ord -> ord.transform { invoke(it) } }) }
        }
        is FlatJoin -> {
          val ar = invoke(head)
          val iAr = id.retypeFrom(ar.type)
          val onr = BetaReduction(on, RWR, id to iAr)
          trace("Repropagate ${head.type.shortString()} from $head into:") andReturn { FlatJoin.cs(ar, iAr, invoke(onr).asExpr()) }
        }
        // FlatFilter, FlatGroupBy, FlatSortBy, Nested, and Distinct etc... do not have head-fields to repropagate types from
        else -> super.invoke(this)
      }
    }


//  override def apply(e: Query): Query =
//    e match {
//      case Filter(a, b, c) => applyBody(a, b, c)(Filter)
//      case Map(a, b, c) =>
//        applyBody(a, b, c)(Map)
//      case FlatMap(a, b, c)   => applyBody(a, b, c)(FlatMap)
//      case ConcatMap(a, b, c) => applyBody(a, b, c)(ConcatMap)
//      case GroupByMap(a, iA1, c, iA2, e) =>
//        val ar   = apply(a)
//        val iA1r = iA1.retypeQuatFrom(ar.quat)
//        val iA2r = iA2.retypeQuatFrom(ar.quat)
//        val cr   = BetaReduction(c, RWR, iA1 -> iA1r)
//        val er   = BetaReduction(e, RWR, iA2 -> iA2r)
//        trace"Repropagate ${a.quat.suppress(msg)} from $a into:" andReturn GroupByMap(ar, iA1r, cr, iA2r, er)
//      case DistinctOn(a, b, c) => applyBody(a, b, c)(DistinctOn)
//      case SortBy(a, b, c, d)  => applyBody(a, b, c)(SortBy(_, _, _, d))
//      case FlatJoin(t, a, iA, on) =>
//        val ar  = apply(a)
//        val iAr = iA.retypeQuatFrom(ar.quat)
//        val onr = BetaReduction(on, RWR, iA -> iAr)
//        trace"Repropagate ${a.quat.suppress(msg)} from $a into:" andReturn FlatJoin(t, ar, iAr, apply(onr))
//      case other =>
//        super.apply(other)
//    }


  // TODO reassign when we implement actions
  // TODO invoke(a: Action) when we implement actions

}
