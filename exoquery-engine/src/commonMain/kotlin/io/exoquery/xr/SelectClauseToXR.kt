package io.exoquery.xr

import io.exoquery.util.tail
import io.exoquery.xr.XR.*

object SelectClauseToXR {
  operator fun invoke(selectClause: SelectClause): XR.Query = run {
    // TODO cover case where there is no from-clause
    // COMPLEX! Need to walk throught the select-clause and recurisvley use `nest` function to nest things in each-other
    val components = selectClause.allComponents()
    if (components.isEmpty()) {
      XR.ExprToQuery(selectClause.select)
    } else {
      val firstFrom = components.first() as? SX.From ?: error("First clause must be a FROM clause but was ${components.first()}")
      nest(firstFrom.xr, firstFrom.variable, components.tail(), selectClause.select)
    }
  }


  /*
  Consider conceptually:
    for {
      x <- from(foo)
      y <- join(bar){stuff}
    } yield (x, y)
  This becomes:
    FlatMap(from(foo), x, FlatMap(join(bar){stuff}, y, Return(Tuple(x, y)))

  However, you can also have multiple froms:
    for {
      x <- from(foo)
      y <- from(bar)
    } yield (x, y)
  This becomes:
    FlatMap(from(foo), x, FlatMap(from(bar), y, Return(Tuple(x, y)))

  Some the `from` clause can also be in tail-position. Now, every single case
  that 'nest' deals with, the from will be in the tail-position.

  Now the most tricky thing that this function does is flip the position of the SX-type clause
  variable (that is on the left) into a FlatMap type variable (that is on the right).
  For example from this:
    val x = foo
    bar(x)
  to this:
    foo.flatMap(x => bar(x))
  which is:
    FlatMap(foo, x, bar(x))
  */

  fun nest(prev: XR.Query, prevVar: XR.Ident, remaining: List<SX>, output: XR.Expression): XR.Query {
    fun nestRecurse(prev: XR.Query, prevVar: XR.Ident, remaining: List<SX>): XR.Query =
      if (remaining.isEmpty())
        XR.Map(prev, prevVar, output)
      else
      // TODO need to work through this and verify
        when (val curr = remaining.first()) {
          // This is not the 1st FROM clause (which will always be in a head-position
          is SX.From ->
            FlatMap(prev, prevVar, nestRecurse(curr.xr, curr.variable, remaining.tail))
          is SX.Join ->
            FlatMap(
              prev, prevVar,
              nestRecurse(FlatJoin(curr.joinType, curr.onQuery, curr.conditionVariable, curr.condition, curr.loc), curr.variable, remaining.tail)
            )

          /*
           * This is a deconstruction case e.g.
           * select {
           *   val (p, a) = from(select { from; join; people to addresses })
           *   val j = join(somethingElse) { ... }
           *   Something(p.name, j.somethingElse)
           * }
           * Kotlin understands this as:
           * select {
           *   val <destruct>: Pair<Person, Address> = from(select { from; join; people to addresses })
           *   val p: Person = <destruct>.component1()
           *   val a: Address = <destruct>.component2()
           *   val j = join(somethingElse) { ... }
           *   Something(p.name, j.somethingElse)
           * }
           * An ArbitraryAssignment will be
           *   ArbitraryAssignment(p, <destruct>.component1())
           *   ArbitraryAssignment(a, <destruct>.component2())
           *
           * In the Ast it needs to become something like:
           *   XR.Block(
           *     p = <destruct>.component1()
           *     XR.Block(
           *       a = <destruct>.component2()
           *       QueryToExpr(FlatMap(FlatJoin(somethingElse) { ... }))
           *     )
           *   )
           * Some additional nesting of ExprToQuery is needed because XR.Block is an expression type
           */
          is SX.ArbitraryAssignment ->
            XR.ExprToQuery(
              XR.Block(
                listOf(XR.Variable(curr.variable, curr.expression)),
                QueryToExpr(nestRecurse(prev, prevVar, remaining.tail))
              )
            )

          is SX.Where ->
            FlatMap(
              prev, prevVar,
              // Since there is no 'new' variable to bind to use use Ident.Unused
              nestRecurse(FlatFilter(curr.condition, curr.loc), XR.Ident.Unused, remaining.tail)
            )
          is SX.GroupBy ->
            FlatMap(
              prev, prevVar,
              nestRecurse(FlatGroupBy(curr.grouping, curr.loc), XR.Ident.Unused, remaining.tail)
            )
          is SX.SortBy ->
            FlatMap(
              prev, prevVar,
              nestRecurse(FlatSortBy(curr.criteria, curr.loc), XR.Ident.Unused, remaining.tail)
            )
        }
    return nestRecurse(prev, prevVar, remaining)
  }


  //when (curr) {
  //  is SX.From -> XR.FlatMap(prev, curr.variable, curr.xr, curr.loc)
  //  is SX.Join -> XR.FlatMap(prev, curr.variable, XR.FlatJoin(curr.joinType, curr.onQuery, curr.conditionVariable, curr.condition, curr.loc))
  //  is SX.Where -> TODO()
  //  is SX.GroupBy -> TODO()
  //  is SX.SortBy -> TODO()
  //}
}
