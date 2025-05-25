package io.exoquery.norm.verify

import io.exoquery.xr.StatefulTransformer
import io.exoquery.xr.XR

data class IdentName(val name: String, val from: XR.Ident) {
  private val id = Id(name)
  override fun equals(other: Any?): Boolean =
    other is IdentName && other.id == id

  override fun hashCode(): Int =
    id.hashCode()

  companion object {
    private data class Id(val name: String)
  }
}

data class State(val seen: Set<IdentName>, val free: Set<IdentName>) {
  fun contains(ident: XR.Ident): Boolean = contains(ident.asIdName())
  fun contains(ident: IdentName): Boolean = seen.contains(ident)

  fun withSeen(ident: XR.Ident): State = withSeen(ident.asIdName())
  fun withSeen(idents: List<XR.Ident>): State = State(seen + idents.map { it.asIdName() }, free)
  fun withSeen(ident: IdentName): State = State(seen + ident, free)
  fun withSeen(idents: Set<IdentName>): State = State(seen + idents, free)

  fun withFree(ident: XR.Ident): State = withFree(ident.asIdName())
  fun withFree(idents: List<XR.Ident>): State = State(seen, free + idents.map { it.asIdName() })
  fun withFree(ident: IdentName): State = State(seen, free + ident)
  fun withFree(idents: Set<IdentName>): State = State(seen, free + idents)
}

//case class State(seen: Set[IdentName], free: Set[IdentName])
//case class FreeVariables(state: State) extends StatefulTransformer[State] {

fun XR.Ident.asIdName(): IdentName = IdentName(this.name, this)

// Not sure if this check is actually necessary because in the parse has detects with GetValue instances come from inside and outside the capture block
data class FreeVariables(override val state: State) : StatefulTransformer<State> {
  override fun invoke(xr: XR.Expression): Pair<XR.Expression, StatefulTransformer<State>> =
    when {
      // When a function is encountered, we need to add its parameters to the "seen" pile because these are idents defined in in the lambda params
      xr is XR.FunctionN ->
        xr to FreeVariables(state.withSeen(xr.params))
      else ->
        super.invoke(xr)
    }

  // Scala
//  override def apply(query: Query): (Query, StatefulTransformer[State]) =
//    query match {
//      case q @ Filter(a, b, c)           => (q, free(a, b, c))
//      case q @ Map(a, b, c)              => (q, free(a, b, c))
//      case q @ DistinctOn(a, b, c)       => (q, free(a, b, c))
//      case q @ FlatMap(a, b, c)          => (q, free(a, b, c))
//      case q @ ConcatMap(a, b, c)        => (q, free(a, b, c))
//      case q @ SortBy(a, b, c, d)        => (q, free(a, b, c))
//      case q @ FlatJoin(t, a, b, c) => (q, free(a, b, c))
//      case _: Entity | _: Take | _: Drop | _: Union | _: UnionAll | _: Aggregation | _: Distinct | _: Nested =>
//        super.apply(query)
//    }
  override fun invoke(xr: XR.Query): Pair<XR.Query, StatefulTransformer<State>> =
    when (xr) {
      is XR.Filter -> xr to free(xr.head, xr.id, xr.body)
      is XR.Map -> xr to free(xr.head, xr.id, xr.body)
      is XR.DistinctOn -> xr to free(xr.head, xr.id, xr.by)
      is XR.FlatMap -> xr to free(xr.head, xr.id, xr.body)
      is XR.ConcatMap -> xr to free(xr.head, xr.id, xr.body)
      is XR.SortBy -> xr to free(xr.head, xr.id, xr.criteria.map { it.field })
      is XR.FlatJoin -> xr to free(xr.head, xr.id, xr.on)
      else -> super.invoke(xr)
    }

  //  override def apply(ast: Ast): (Ast, StatefulTransformer[State]) =
//    ast match {
//      case ident: Ident if (!state.seen.contains(ident.idName)) =>
//        (ident, FreeVariables(State(state.seen, state.free + ident.idName)))
//      case f @ Function(params, body) =>
//        val (_, t) = FreeVariables(State(state.seen ++ params.map(_.idName), state.free))(body)
//        (f, FreeVariables(State(state.seen, state.free ++ t.state.free)))
//      case q @ Foreach(a, b, c) =>
//        (q, free(a, b, c))
//      case other =>
//        super.apply(other)
//    }

  override fun invokeIdent(xr: XR.Ident): Pair<XR.Ident, StatefulTransformer<State>> =
    // When a new Ident is seen, we need to add it to the "free" pile because we haven't "seen" where it comes from yet
    if (!state.contains(xr))
      xr to FreeVariables(state.withFree(xr.asIdName()))
    else
      super.invokeIdent(xr)

  override fun invoke(xr: XR.Action): Pair<XR.Action, StatefulTransformer<State>> =
    when {
      // It is the .returning { x -> stuff(... x ...) } clause
      xr is XR.Returning && xr.kind is XR.Returning.Kind.Expression -> {
        val (_, ta) = FreeVariables(state)(xr.kind.expr)
        // Recurse into the { x -> stuff(... x ...) } part in `returning(x) { x -> stuff(... x ...) }` to check potential free variables inside
        val (_, tb) = FreeVariables(state.withSeen(xr.kind.alias))(xr.kind.expr)
        // Collect any remaining free variables from the clause
        xr to FreeVariables(state.withFree(ta.state.free).withFree(tb.state.free))
      }
      // It is the .returningKeys(a, b, c) clause
      xr is XR.Returning -> {
        val (_, ta) = FreeVariables(state)(xr.kind)
        xr to FreeVariables(state.withFree(ta.state.free))
      }
      // Need to do a free-variables check on all the action-clauses. They may contain free variables themselves.
      xr is XR.Insert -> {
        val (_, ta) = FreeVariables(state)(xr.query)
        val (_, tb) = FreeVariables(state.withSeen(xr.alias)).applyList(xr.assignments) { t, v -> t.invoke(v) }
        val (_, tc) = FreeVariables(state.withSeen(xr.alias)).applyList(xr.exclusions) { t, v -> t.invoke(v) }
        xr to FreeVariables(state.withFree(ta.state.free).withFree(tb.state.free).withFree(tc.state.free))
      }
      xr is XR.Update -> {
        val (_, ta) = FreeVariables(state)(xr.query)
        val (_, tb) = FreeVariables(state.withSeen(xr.alias)).applyList(xr.assignments) { t, v -> t.invoke(v) }
        xr to FreeVariables(state.withFree(ta.state.free).withFree(tb.state.free))
      }
      xr is XR.Delete -> {
        val (_, ta) = FreeVariables(state)(xr.query)
        xr to FreeVariables(state.withFree(ta.state.free))
      }
      else -> super.invoke(xr)
    }

  private fun free(a: XR.U.QueryOrExpression, ident: XR.Ident, c: List<XR.U.QueryOrExpression>): StatefulTransformer<State> {
    val (_, ta) = FreeVariables(state)(a)
    val (_, tc) = FreeVariables(state.withSeen(ident)).applyList(c) { t, v -> t.invoke(v) }
    return FreeVariables(state.withFree(ta.state.free).withFree(tc.state.free))
  }

  private fun free(a: XR.U.QueryOrExpression, ident: XR.Ident, c: XR.U.QueryOrExpression): StatefulTransformer<State> {
    val (_, ta) = FreeVariables(state)(a)
    val (_, tc) = FreeVariables(state.withSeen(ident))(c)
    return FreeVariables(state.withFree(ta.state.free).withFree(tc.state.free))
  }

  //  private def free(a: Ast, ident: IdentName, c: Ast) = {
  //    val (_, ta) = apply(a)
  //    val (_, tc) = FreeVariables(State(state.seen + ident, state.free))(c)
  //    FreeVariables(State(state.seen, state.free ++ ta.state.free ++ tc.state.free))
  //  }


  sealed interface Result {
    data object None : Result
    data class Detected(val free: Set<IdentName>) : Result

    operator fun plus(other: Result): Result =
      when {
        this is None -> other
        other is None -> this
        else -> Detected((this as Detected).free + (other as Detected).free)
      }
  }

  companion object {
    operator fun invoke(xr: XR): Set<IdentName> =
      FreeVariables(State(setOf(), setOf()))(xr).let { (_, transformer) ->
        transformer.state.free
      }

    fun verify(xr: XR): Result =
      invoke(xr).let { free ->
        if (free.isEmpty()) Result.None
        else Result.Detected(free)
      }
  }

// Scala
//object FreeVariables {
//  def apply(ast: Ast): Set[IdentName] =
//    new FreeVariables(State(Set.empty, Set.empty))(ast) match {
//      case (_, transformer) =>
//        transformer.state.free
//    }
//
//  def verify(ast: Ast): Either[String, Ast] =
//    apply(ast) match {
//      case free if free.isEmpty => Right(ast)
//      case free =>
//        val firstVar = free.headOption.map(_.name).getOrElse("someVar")
//        Left(
//          s"""
//             |Found the following variables: ${free.map(_.name).toList} that seem to originate outside of a `quote {...}` or `run {...}` block.
//             |Quotes and run blocks cannot use values outside their scope directly (with the exception of inline expressions in Scala 3).
//             |In order to use runtime values in a quotation, you need to lift them, so instead
//             |of this `$firstVar` do this: `lift($firstVar)`.
//             |Here is a more complete example:
//             |Instead of this: `def byName(n: String) = quote(query[Person].filter(_.name == n))`
//             |        Do this: `def byName(n: String) = quote(query[Person].filter(_.name == lift(n)))`
//        """.stripMargin
//        )
//    }
//}

}


// Scala
//
//
//  override def apply(action: Action): (Action, StatefulTransformer[State]) =
//    action match {
//      case q @ Returning(a, b, c) =>
//        (q, free(a, b, c))
//      case q @ ReturningGenerated(a, b, c) =>
//        (q, free(a, b, c))
//      case other =>
//        super.apply(other)
//    }
//
//  override def apply(e: OnConflict.Target): (OnConflict.Target, StatefulTransformer[State]) = (e, this)
//

//

//}
//
