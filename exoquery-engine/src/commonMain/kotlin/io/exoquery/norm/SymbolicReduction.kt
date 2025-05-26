package io.exoquery.norm

import io.exoquery.util.TraceConfig
import io.exoquery.xr.BetaReduction
import io.exoquery.xr.XR
import io.exoquery.xr.contains
import io.exoquery.xr.copy.*

/**
 * This stage represents Normalization Stage1: Symbolic Reduction in Philip
 * Wadler's Paper "A Practical Theory of Language Integrated Query", given in
 * Figure 11.
 * https://homepages.inf.ed.ac.uk/slindley/papers/practical-theory-of-linq.pdf
 *
 * It represents foundational normalizations done to sequences that represents
 * queries. In Wadler's paper, he characterizes them as `for x in P ...``
 * whereas in Quill they are characterized as list comprehensions i.e.
 * `P.flatMap(x => ...)`.
 *
 * NOTE: Leaving Quill commneted-out code equivalents here for now for reference
 */
class SymbolicReduction(val traceConfig: TraceConfig) {
  fun XR.FlatMap.hasTailPositionFlatJoin(): Boolean =
    body is XR.U.HasHead && body.head is XR.FlatJoin

  operator fun invoke(q: XR.Query): XR.Query? =
    with(q) {
      when {
        /*
         * Represents if-for in Figure 11.
         * This transformation is particularity difficult to understand so I have added examples with several layers of granularity.
         * It's basic form goes like this:
         *
         * bottles.filter(bottle => isMerlot).flatMap(merlotBottle => {withCheese})
         *     bottles.flatMap(merlotBottle => {withCheese}.filter(_ => isMerlot[bottle := merlotBottle]))
         *
         * For example:
         *   The AST block withCheese is cheeses.filter(cheese => cheese.pairsWith == merlotBottle)
         *   The AST block isMerlot is bottle.isMerlot
         *
         * bottles.filter(bottle => bottle.isMerlot).flatMap(merlotBottle => {cheeses.filter(cheese => cheese.pairsWith == merlotBottle)} )
         *     bottles.flatMap(merlotBottle => cheeses.filter({bottle.isMerlot}[bottle := merlotBottle]).filter(cheese => cheese.pairsWith == merlotBottle)}
         * which is:
         *     bottles.flatMap(merlotBottle => cheeses.filter({doesnt-matter} => merlotBottle.isMerlot).filter(cheese => cheese.pairsWith == merlotBottle)
         *
         * a.filter(b => c).flatMap(d => e.$) =>
         *     a.flatMap(d => e.filter(_ => c[b := d]).$)
         *
         * case FlatMap(Filter(a, b, c), d, e: Query)
         *
         */
        this is XR.FlatMap && head is XR.Filter -> {
          val (a, b, c) = Triple(head.head, head.id, head.body)
          val (d, e) = Pair(id, body)
          val cr = BetaReduction(c, b to d).asExpr()
          val er = with(head) {
            AttachToEntity({ q, id -> Filter.cs(q, id, cr) })(e)
          }
          FlatMap.cs(a, d, er)
        }

// Scala:
//      case FlatMap(Filter(a, b, c), d, e: Query) =>
//        val cr = BetaReduction(c, b -> d)
//        val er = AttachToEntity(Filter(_, _, cr))(e)
//        Some(FlatMap(a, d, er))

        /*
         * This transformation does not have an analogue in Wadler's paper, it represents the fundamental nature of the Monadic 'bind' function
         * that A.flatMap(a => B).flatMap(b => C) is isomorphic to A.flatMap(a => B.flatMap(b => C)).
         *
         * a.flatMap(b => c).flatMap(d => e) =>
         *     a.flatMap(b => c.flatMap(d => e))
         *
         * case FlatMap(FlatMap(a, b, c), d, e) =>
         *     Some(FlatMap(a, b, FlatMap(c, d, e)))
         *
         * Note that in practice there is a caveat here in that if this transformation causes FlatJoins to be both in head and body position
         * then the SelectQuery transformer will not be able to propertly constuct it (there is a check there in flattenContexts that assures it).
         * The problem stems from having the construct FlatMap(..., FlatMap(Map(FlatJoin, ...)), Map(FlatJoin, ...)) which is not a valid construct
         * for the sake a query construction. Now recall that if you have something like FlatMap( FlatMap(ent, Map(FlatJoin)) , Map(FlatJoin))
         * (P.S. which is something like ent.flatMap(a => a.flatMap(join(b))).map(join(c)) (**) and proceed to flatten it out to
         * FlatMap(ent, FlatMap(Map(FlatJoin), Map(FlatJoin)) then you will have a problem because the FlatJoin in the
         * head and body positions of the inner FlatMap. Therefore we need to check that the head and body of the outer FlatMap do not have both have
         * flatJoins in order to proceed with this transformation.
         * (**) This is typically produced by using a capture.select clause, see "variable deconstruction should work even when passed to further join" in
         * VariableReductionReq.kt for an example. Also the MonadicMachinerReq has lots of examples of this.
         *
         * The only exception to this rule if the head of the inner FlatMap has a XR.Map in the tail position (which is actually the most common case).
         * In that case we have some special handling in SqlQueryHelper.flattenDualHeadsIfPossible to make it work.
         *
         * For example, let's say we have:
         * people.flatMap(p => join(addr, p.id).map(a => (p, a)))
         *   .flatMap(kv => join(robot, kv._1.id).map(r => ...))
         *
         * In SQL-DSL land that is:
         * select {
         *   kv = from( select { p = from(people); a = join(addr).on(p.id...) } )
         *   r = join(robot).on(kv._1.id...)
         * }
         *
         * This transformation will turn it into:
         * people
         *   .flatMap(p =>
         *     join(addr, p.id...).map(a => (p, a))
         *     .flatMap(kv => join(robot, kv._1.id).map(r => ...))
         *
         * This would be something like:
         * select {
         *    p = from(people)
         *    kv = from(
         *      select {
         *        a = join(addr).map(a => (p, a)) // <- Not a valid clause!
         *        r = join(robot).on(p.id...)
         *        (a.second to r)
         *      }
         *    )
         *
         * This of course is not valid-sql because a join cannot be the 1st part of a seelct statement. This is actually
         * a significant difference from the Monadic paradigm to SQL, and because of it `join(table) on(cond)` is not
         * exactly eqivalent to `table.filter(cond)`. For the specific above case however (i.e. where the `join(addr, p.id...).map(a => (p, a))` expression
         * exists above, since the head-position is just a XR.Map(XR.Join(...)) clause we can pull out the Join and flatten the whole query.
         * See SqlQueryHelper for more details on how that is done.
         */
        this is XR.FlatMap && head is XR.FlatMap && !(this.hasTailPositionFlatJoin() && this.head.hasTailPositionFlatJoin() && this.head.body !is XR.Map) -> {
          val (a, b, c) = Triple(head.head, head.id, head.body)
          val (d, e) = Pair(id, body)
          FlatMap.cs(a, b, FlatMap.cs(c, d, e))
        }

// Scala:
//      case FlatMap(FlatMap(a, b, c), d, e) =>
//        Some(FlatMap(a, b, FlatMap(c, d, e)))

        // Represents for@ in Figure 11
        //
        // a.union(b).flatMap(c => d)
        //      a.flatMap(c => d).union(b.flatMap(c => d))
        //
        // case FlatMap(Union(a, b), c, d) =>
        //     Some(Union(FlatMap(a, c, d), FlatMap(b, c, d)))
        this is XR.FlatMap && head is XR.Union && !body.hasImpurities() -> {
          val (a, b) = Pair(head.a, head.b)
          val (c, d) = Pair(id, body)
          with(head) {
            Union.cs(FlatMap.cs(a, c, d), FlatMap.cs(b, c, d))
          }
        }

// Scala
//      case FlatMap(Union(a, b), c, d) =>
//        Some(Union(FlatMap(a, c, d), FlatMap(b, c, d)))

        // Represents for@ in Figure 11 (Wadler does not distinguish between Union and UnionAll)
        //
        // a.unionAll(b).flatMap(c => d)
        //      a.flatMap(c => d).unionAll(b.flatMap(c => d))
        this is XR.FlatMap && head is XR.UnionAll && !body.hasImpurities() -> {
          val (a, b) = Pair(head.a, head.b)
          val (c, d) = Pair(id, body)
          with(head) {
            UnionAll.cs(FlatMap.cs(a, c, d), FlatMap.cs(b, c, d))
          }
        }


        else -> null
      }
    }
}
