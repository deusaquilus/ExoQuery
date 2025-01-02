package io.exoquery

import io.exoquery.printing.PrintMisc
import io.exoquery.xr.XR

interface ContainerOfXR {
  val xr: XR
  // I.e. runtime containers that are used in the expression (if any)
  val runtimes: Runtimes
}

// Create a wrapper class for runtimes for easy lifting/unlifting
data class Runtimes(val runtimes: List<Pair<BID, ContainerOfXR>>) {
  companion object {
    // TODO when splicing into the Container use this if the runtimes variable is actually empty
    //      that way we can just check for this when in order to know if a tree can be statically translated or not
    val Empty = Runtimes(emptyList())
  }
  operator fun plus(other: Runtimes): Runtimes = Runtimes(runtimes + other.runtimes)
}
// TODO similar class for lifts

/*
 * val expr: @Captured SqlExpression<Int> = capture { foo + bar }
 * val query: SqlQuery<Person> = capture { Table<Person>() }
 * val query2: SqlQuery<Int> = capture { query.map { p -> p.age } }
 *
 * so:
 * // Capturing a generic expression returns a SqlExpression
 * fun <T> capture(block: () -> T): SqlExpression<T>
 * for example:
 * {{{
 * val combo: SqlExpression<Int> = capture { foo + bar }
 * }}}
 *
 * // Capturing a SqlQuery returns a SqlQuery
 * fun <T> capture(block: () -> SqlQuery<T>): SqlQuery<T>
 * for example:
 * {{{
 * val query: SqlQuery<Person> = capture { Table<Person>() }
 * }}}
 */

data class Param<T>(val id: BID, val value: T)

data class Params(val lifts: List<Param<*>>) {
  operator fun plus(other: Params): Params = Params(lifts + other.lifts)
}

// TODO add lifts which will be BID -> ContainerOfEx
// (also need a way to get them easily from the IrContainer)

data class SqlExpression<T>(val xr: XR.Expression, val runtimes: Runtimes, val params: Params) {
  val use: T by lazy { throw IllegalArgumentException("Cannot `use` an SqlExpression outside of a quoted context") }
  fun show() = PrintMisc().invoke(this)
}




//fun <T> SqlExpression<T>.convertToQuery(): Query<T> = QueryContainer<T>(io.exoquery.xr.XR.QueryOf(xr), binds)
//fun <T> Query<T>.convertToSqlExpression(): SqlExpression<T> = SqlExpressionContainer<T>(io.exoquery.xr.XR.ValueOf(xr), binds)