package io.exoquery

import io.exoquery.annotation.ExoInternal
import io.exoquery.select.SelectClause
import io.exoquery.select.program
import io.exoquery.xr.XR

fun <T> getSqlVar(name: String): T =
  throw IllegalArgumentException("something meaningful")

sealed interface Expression {
  val xr: XR

  // Create an instance of this by doing Expression.lambda { (p: Person) -> p.name == "Joe" }
  // or Expression.lambda [{ (p: Person) -> p.name == "Joe" }] depending on the direction we'd like to go
  companion object {
    fun <T, R> lambda(f: (T) -> R): Lambda1Expression = TODO()
  }
}

data class Lambda1Expression(override val xr: XR.Function1): Expression {
  val ident get() = xr.param
}

data class EntityExpression(override val xr: XR.Entity): Expression

// TODO make this an inline class?
//data class MapClause<T>(val xr: XR) {
//  operator fun <R> get(f: (T) -> R): Query<R> = error("The map expression of the Query was not inlined")
//  fun <R> fromExpr(f: Lambda1Expression): Query<R> = QueryContainer(XR.Map(this.xr, f.ident, f.xr))
//}

// TODO find a way to make this private so user cannot grab it since only supposed to be used in query functions
class EnclosedExpression

context(EnclosedExpression) fun <T> param(value: T): T = error("Lifting... toto write this message")

// TODO Rename to SqlRow
class SqlVariable<T>(variableName: String /* don't want this to intersect with extension function properties*/) {
  private val _variableName = variableName

  // can this be internal if it's replaced in the transformers
  @ExoInternal
  fun withName(newName: String) = SqlVariable<T>(newName)

  @ExoInternal
  fun getVariableName() = _variableName

  companion object {
    fun <T> new(name: String) = SqlVariable<T>(name)
  }

  context(EnclosedExpression) operator fun invoke(): T =
    throw IllegalStateException("meaningful error about how can't use a sql variable in a runtime context and it should be impossible anyway becuase its not an EnclosedExpression")
}


sealed interface Query<T> {
  val xr: XR

//  val map get() = MapClause<T>(xr)

  // Table<Person>().filter(name == "Joe")
  fun <R> filterBy(f: context(EnclosedExpression) (T).() -> R): Query<T> = error("The map expression of the Query was not inlined")

  // Table<Person>().map(name)
  fun <R> mapBy(f: context(EnclosedExpression) (T).() -> R): Query<T> = error("The map expression of the Query was not inlined")

  fun <R> map(f: context(EnclosedExpression) (SqlVariable<T>) -> R): Query<T> = error("The map expression of the Query was not inlined")
  // TODO Need to understand how this would be parsed if the function body had val-assignments
  fun <R> mapExpr(f: Lambda1Expression): Query<R> =
    QueryContainer(XR.Map(this.xr, f.ident, f.xr.body))


  // Search for every Ident (i.e. GetValue) that has @SqlVariable in it's type
  // and check its ExtensionReciever which should be of type EnclosedExpression
  // (i.e. how to get that?)

  // "Cannot use the value of the variable 'foo' outside of a Enclosed Expression context

  fun <R> flatMap(f: (SqlVariable<T>) -> Query<R>): Query<R> = error("needs to be replaced by compiler")
  // TODO Make the compiler plug-in a SqlVariable that it creates based on the variable name in f
  fun <R> flatMapInternal(ident: XR.Ident, body: Query<R>): Query<R> =
    QueryContainer(XR.FlatMap(this.xr, ident, body.xr))




  //fun <R> flatMapExpr(f: Lambda1Expression): Query<R> =
  //  QueryContainer(XR.FlatMap(this.xr, f.ident, f.xr.body))

//  fun <T> join(source: Query<T>): OnClause<T> = OnClause(source)
}

//data class OnClause<T>(val source: Query<T>) {
//  fun on(predicate: (T) -> Boolean): Query<T> =  error("The join-on expression of the Query was not inlined")
//  fun onExpr(f: Lambda1Expression): Query<T> =  error("The join-on expression of the Query was not inlined")
//}

data class QueryContainer<T>(override val xr: XR): Query<T>

// TODO make this constructor private? Shuold have a TableQuery.fromExpr constructor
class TableQuery<T> private constructor (override val xr: XR.Entity): Query<T> {
  companion object {
    // TODO need to implement this in the plugin
    operator fun <T> invoke(): TableQuery<T> = error("The TableQuery create-table expression was not inlined")
    fun <T> fromExpr(entity: EntityExpression) = TableQuery<T>(entity.xr)
  }
}

@Suppress("UNCHECKED_CAST")
public fun <T, Q: Query<T>> select(block: suspend SelectClause<T>.() -> SqlVariable<T>): Q {
  /*
  public fun <Action, Result, T : ProgramBuilder<Action, Result>> program(
    machine: T,
    f: suspend T.() -> Result
): Action
   */
  val q =
    program<Query<T>, SqlVariable<T>, SelectClause<T>>(
      machine = SelectClause<T>(),
      f = block
    ) as Query<T>

  // TODO Need to change the innermost map into a flatMap
  return q as Q
  //return InnerMost.mark(q) as Q
}

//data class Table<T>(override val xt: XR): Query<T>