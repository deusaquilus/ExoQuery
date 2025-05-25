package io.exoquery.xr

import io.exoquery.xr.XR.OrderField
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Unline XR, SX is not a recursive AST, is merely a prefix AST that has a common-base class
@Serializable
sealed interface SX {
  // Union types for labelling
  object U {
    @Serializable
    sealed interface Assignment : SX
  }

  @Serializable
  data class From(val variable: XR.Ident, val xr: XR.Query, val loc: XR.Location = XR.Location.Synth) : U.Assignment, SX {
    data class Id(val variable: XR.Ident, val xr: XR.Query)

    @Transient
    private val id = Id(variable, xr)
    override fun equals(other: Any?): Boolean = if (this === other) true else other is From && id == other.id
    override fun hashCode(): Int = id.hashCode()
  }

  @Serializable
  data class Join(val joinType: XR.JoinType, val variable: XR.Ident, val onQuery: XR.Query, val conditionVariable: XR.Ident, val condition: XR.Expression, val loc: XR.Location = XR.Location.Synth) : U.Assignment, SX {
    data class Id(val joinType: XR.JoinType, val variable: XR.Ident, val onQuery: XR.Query, val conditionVariable: XR.Ident, val condition: XR.Expression)

    @Transient
    private val id = Id(joinType, variable, onQuery, conditionVariable, condition)
    override fun equals(other: Any?): Boolean = if (this === other) true else other is Join && id == other.id
    override fun hashCode(): Int = id.hashCode()

    /**
     * In cases where `it` is used as the join-variable we typically don't want to use `it` in the SQL and instead should opt
     * to use the variable define on the other side of the join. For example:
     * ```
     * select {
     *   val p = from(people)
     *   val a = join(addresses) { it.personId == p.id }
     *   p.name, a.zip
     * }
     * // Would yield the query (since `a` would be dealiased by the Dealias phase to `it`:
     * // SELECT p.name, a.zip FROM people p JOIN addresses it ON it.personId = p.id
     * // Instead we want it to be:
     * // SELECT p.name, a.zip FROM people p JOIN addresses a ON a.personId = p.id
     * // So we need to swap the `it` for `a` in the condition variable and beta-reduce with the new one in the condition
     * ```
     */
    fun swapItVariableForOuter() =
      when {
        this.variable.name != "it" && this.conditionVariable.name == "it" -> {
          val newConditionVariable = this.conditionVariable.copy(name = this.variable.name)
          val newCondition = BetaReduction(this.condition, this.conditionVariable to newConditionVariable).asExpr()
          this.copy(conditionVariable = newConditionVariable, condition = newCondition)
        }
        else -> this
      }
  }

  @Serializable
  data class ArbitraryAssignment(val variable: XR.Ident, val expression: XR.Expression, val loc: XR.Location = XR.Location.Synth) : U.Assignment, SX {
    data class Id(val variable: XR.Ident, val expression: XR.Expression)

    @Transient
    private val id = Id(variable, expression)
    override fun equals(other: Any?): Boolean = if (this === other) true else other is ArbitraryAssignment && id == other.id
    override fun hashCode(): Int = id.hashCode()
  }

  @Serializable
  data class Where(val condition: XR.Expression, val loc: XR.Location = XR.Location.Synth) : SX {
    data class Id(val condition: XR.Expression)

    @Transient
    private val id = Id(condition)
    override fun equals(other: Any?): Boolean = if (this === other) true else other is Where && id == other.id
    override fun hashCode(): Int = id.hashCode()
  }

  @Serializable
  data class GroupBy(val grouping: XR.Expression, val loc: XR.Location = XR.Location.Synth) : SX {
    data class Id(val grouping: XR.Expression)

    @Transient
    private val id = Id(grouping)
    override fun equals(other: Any?): Boolean = if (this === other) true else other is GroupBy && id == other.id
    override fun hashCode(): Int = id.hashCode()
  }

  @Serializable
  data class SortBy(val criteria: List<OrderField>, val loc: XR.Location = XR.Location.Synth) : SX {
    data class Id(val criteria: List<OrderField>)

    @Transient
    private val id = Id(criteria)
    override fun equals(other: Any?): Boolean = if (this === other) true else other is SortBy && id == other.id
    override fun hashCode(): Int = id.hashCode()
  }
}
