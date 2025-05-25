package io.exoquery.sql

import io.decomat.Is
import io.decomat.Pattern
import io.decomat.Pattern2
import io.decomat.Typed
import io.exoquery.xr._And_
import io.exoquery.xr.XR
import io.exoquery.xrError

data class LayerComponents(val grouping: SqlQueryApply.Layer.Grouping?, val sorting: SqlQueryApply.Layer.Sorting?, val filtering: SqlQueryApply.Layer.Filtering?)

fun List<SqlQueryApply.Layer>.findComponentsOrNull(): LayerComponents {
  val groupings = this.mapNotNull { if (it is SqlQueryApply.Layer.Grouping) it else null }
  if (groupings.size > 1) xrError("Multiple groupings detected, this is illegal:\n" + groupings.map { it.groupBy }.joinToString("\n"))

  val sortings = this.mapNotNull { if (it is SqlQueryApply.Layer.Sorting) it else null }
  if (sortings.size > 1) xrError("Multiple sortings detected, this is illegal:\n" + sortings.map { it.criteria }.joinToString("\n"))

  val filterings = this.mapNotNull { if (it is SqlQueryApply.Layer.Filtering) it else null }
  if (filterings.size > 1) xrError("Multiple sortings detected, this is illegal:\n" + filterings.map { it.where }.joinToString("\n"))

  return LayerComponents(groupings.firstOrNull(), sortings.firstOrNull(), filterings.firstOrNull())
}


fun combineWhereClauses(expr: XR.Expression?, combineWith: XR.Expression) =
  if (expr != null) expr _And_ combineWith
  else combineWith


// Define decomat extensions for selectvalue manually since the codegen doesn't seem to work right with things like generics e.g. turns List<String> into List<*>
class SelectValue_M<A : Pattern<AP>, B : Pattern<BP>, AP : XR.Expression, BP : List<String>>(a: A, b: B) : Pattern2<A, B, AP, BP, SelectValue>(a, b, Typed<SelectValue>())

operator fun <A : Pattern<AP>, B : Pattern<BP>, AP : XR.Expression, BP : List<String>> SelectValue.Companion.get(a: A, b: B) = SelectValue_M(a, b)
val SelectValue.Companion.Is get() = Is<SelectValue>()
