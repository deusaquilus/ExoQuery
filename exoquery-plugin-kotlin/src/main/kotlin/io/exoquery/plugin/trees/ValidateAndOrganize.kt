package io.exoquery.plugin.trees

import io.exoquery.xr.SX
import io.exoquery.xr.SelectClause
import io.exoquery.plugin.logging.CompileLogger
import io.exoquery.xr.XR
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement

object ValidateAndOrganize {
  private interface Phase {
    data object BEGIN: Phase
    data object FROM: Phase
    data object JOIN: Phase
    data object MODIFIER: Phase // I.e. for group/sort/take/drop
  }

  // The "pure functiona' way to do this would be to have a State object that is copied with new values but since
  // everything here is completely private and the only way to interact with it is through the invoke function, we it should be fine for now.
  private class State(var phase: Phase = Phase.BEGIN, val froms: MutableList<SX.From> = mutableListOf(), val joins: MutableList<SX.Join> = mutableListOf(), var where: SX.Where? = null, var groupBy: SX.GroupBy? = null, var sortBy: SX.SortBy? = null) {
    context(ParserContext, CompileLogger)
    fun validPhases(vararg validPhases: Phase) = { errorMsg: String, expr: IrElement ->
      if (!validPhases.contains(phase)) error(errorMsg, expr)
    }
    fun setPhaseTo(phase: Phase) {
      this.phase = phase
    }

    context(ParserContext, CompileLogger)
    fun addFrom(from: SX.From, expr: IrElement) {
      validPhases(Phase.BEGIN, Phase.FROM)("Cannot add a FROM clause after a JOIN clause or any other kind of clause", expr)
      setPhaseTo(Phase.FROM)
      froms += from
    }
    context(ParserContext, CompileLogger)
    fun addJoin(join: SX.Join, expr: IrElement) {
      validPhases(Phase.JOIN, Phase.FROM)("At least one FROM-clause is needed and can only add JOIN clauses after FROM and before any WHERE/GROUP/SORT clauses.", expr)
      setPhaseTo(Phase.JOIN)
      joins += join
    }
    context(ParserContext, CompileLogger)
    fun addWhere(where: SX.Where, expr: IrElement) {
      //if (phase != Phase.JOIN && phase != Phase.FROM) error("", expr)
      //phase = Phase.MODIFIER
      validPhases(Phase.JOIN, Phase.FROM, Phase.MODIFIER)("Only one `WHERE` clause is allowed an it must be after from/join calls and before any group/sort clauses", expr)
      setPhaseTo(Phase.MODIFIER) // Basically `WHERE` is like it's on phase but we don't need to create one since it can only occur once
      this.where = where
    }
    context(ParserContext, CompileLogger)
    fun addGroupBy(groupBy: SX.GroupBy, expr: IrElement) {
      validPhases(Phase.JOIN, Phase.FROM, Phase.MODIFIER)("Only one `GROUP BY` clause is allowed an it must be after from/join calls and before any sort clauses", expr)
      setPhaseTo(Phase.MODIFIER)
      this.groupBy = groupBy
    }
    context(ParserContext, CompileLogger)
    fun addSortBy(sortBy: SX.SortBy, expr: IrElement) {
      validPhases(Phase.JOIN, Phase.FROM, Phase.MODIFIER)("Only one `SORT BY` clause is allowed an it must be after from/join calls and before any group clauses", expr)
      setPhaseTo(Phase.MODIFIER)
      this.sortBy = sortBy
    }
  }

  context(ParserContext, CompileLogger)
  operator fun invoke(statements: List<Pair<SX, IrStatement>>, ret: XR.Expression): SelectClause {
    val state = State()
    statements.forEach { (sx, stmt) ->
      when (sx) {
        is SX.From -> state.addFrom(sx, stmt)
        is SX.Join -> state.addJoin(sx, stmt)
        is SX.Where -> state.addWhere(sx, stmt)
        is SX.GroupBy -> state.addGroupBy(sx, stmt)
        is SX.SortBy -> state.addSortBy(sx, stmt)
      }
    }
    return SelectClause(state.froms, state.joins, state.where, state.groupBy, state.sortBy, ret, ret.type)
  }

}
