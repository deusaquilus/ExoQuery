package io.exoquery.xr

import io.decomat.Components2M
import io.decomat.Pattern
import io.decomat.customPattern2M

infix fun XR.Expression.`+||+`(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, BooleanOperator.or, other)
infix fun XR.Expression.`+&&+`(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, BooleanOperator.and, other)
infix fun XR.Expression.`+==+`(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, EqualityOperator.`==`, other)
infix fun XR.Expression.`+!=+`(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, EqualityOperator.`!=`, other)

fun XR.Map.containsImpurities(): Boolean =
  CollectXR(this) {
    with(it) {
      when {
        this is XR.Aggregation -> this
        this is XR.Infix && !this.pure -> this
        else -> null
      }
    }
  }.isNotEmpty()


  /*
  def unapply(ast: Ast) =
  CollectAst(ast) {
    case agg: Aggregation          => agg
      case inf: Infix if (!inf.pure) => inf
  }.nonEmpty
   */

val XR.Map.Companion.DistinctHead get() = DistinctHeadMap()
class DistinctHeadMap() {
  operator fun <AP: Pattern<XR.Query>, BP: Pattern<XR.Expression>> get(x: AP, y: BP) =
    customPattern2M(x, y) { it: XR.Map ->
      with(it) {
        when {
          this.head is XR.Distinct -> Components2M(this.head.head, this.id, this.body)
          else -> null
        }
      }
    }
}


val XR.SortBy.Companion.DistinctHead get() = DistinctHeadMatchSortBy()
class DistinctHeadMatchSortBy() {
  operator fun <AP: Pattern<XR.Query>, BP: Pattern<XR.Expression>> get(x: AP, y: BP) =
    customPattern2M(x, y) { it: XR.SortBy ->
      with(it) {
        when {
          this.head is XR.Distinct -> Components2M(this.head.head, this.id, this.criteria)
          else -> null
        }
      }
    }
}