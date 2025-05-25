package io.exoquery.xr

import io.exoquery.pprint.PPrinterConfig
import io.exoquery.pprint.Tree
import io.exoquery.printing.PrintSkipLoc
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// The structure should be:
// val from: SX.From, val joins: List<SX.JoinClause>, val where: SX.Where?, val groupBy: SX.GroupBy?, val sortBy: SX.SortBy?
@Serializable
data class SelectClause(
  val assignments: List<SX.U.Assignment>,
  val where: SX.Where?,
  val groupBy: SX.GroupBy?,
  val sortBy: SX.SortBy?,
  val select: XR.Expression,
  override val type: XRType,
  override val loc: XR.Location = XR.Location.Synth
) : XR.CustomQuery.Convertable {

  override fun toQueryXR(): XR.Query = SelectClauseToXR(this)
  fun allComponents(): List<SX> = assignments + listOfNotNull(where, groupBy, sortBy)

  // Do nothing for now, in the cuture recurse in queries and expressions inside the SX clauses
  override fun handleStatelessTransform(t: StatelessTransformer): XR.CustomQuery.Convertable =
    copy(
      assignments.map {
        when (it) {
          is SX.From -> {
            val from = it
            from.copy(t.invokeIdent(from.variable), t(from.xr))
          }
          is SX.Join -> {
            val join = it
            join.copy(variable = t.invokeIdent(join.variable), onQuery = t(join.onQuery), conditionVariable = t.invokeIdent(join.conditionVariable), condition = t(join.condition))
          }
          is SX.ArbitraryAssignment -> {
            val assignment = it
            assignment.copy(variable = t.invokeIdent(assignment.variable), expression = t(assignment.expression))
          }
        }
      },
      where?.let { where -> where.copy(t(where.condition)) },
      groupBy?.let { groupBy -> groupBy.copy(t(groupBy.grouping)) },
      sortBy?.let { sortBy ->
        val newCriteria = sortBy.criteria.map { ord -> ord.transform { t.invoke(it) } }
        sortBy.copy(criteria = newCriteria)
      },
      t(select)
    )

  // Do nothing for now, in the cuture recurse in queries and expressions inside the SX clauses
  override fun <S> handleStatefulTransformer(transformer: StatefulTransformer<S>): Pair<XR.CustomQuery.Convertable, StatefulTransformer<S>> = this to transformer
  override fun showTree(config: PPrinterConfig): Tree = PrintSkipLoc<SelectClause>(serializer(), config).treeify(this, null, false, false)

  companion object {
    fun justSelect(select: XR.Expression, loc: XR.Location): SelectClause = SelectClause(emptyList(), null, null, null, select, select.type, loc)

    // A friendlier constructor for tests
    fun of(
      assignments: List<SX.U.Assignment>,
      where: SX.Where? = null,
      groupBy: SX.GroupBy? = null,
      sortBy: SX.SortBy? = null,
      select: XR.Expression,
      type: XRType,
      loc: XR.Location = XR.Location.Synth
    ): SelectClause = SelectClause(assignments, where, groupBy, sortBy, select, type, loc)
  }

  fun toXrTransform(): XR.Query = SelectClauseToXR(this)
  fun toXrRef(): XR.CustomQueryRef = XR.CustomQueryRef(this)


  data class Id(val assignments: List<SX.U.Assignment>, val where: SX.Where?, val groupBy: SX.GroupBy?, val sortBy: SX.SortBy?, val select: XR.Expression)

  @Transient
  val id = Id(assignments, where, groupBy, sortBy, select)
  override fun equals(other: Any?): Boolean = (this === other) || (other is SelectClause && id == other.id)
  override fun hashCode(): Int = id.hashCode()
}
