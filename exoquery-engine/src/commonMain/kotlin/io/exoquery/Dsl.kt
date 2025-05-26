package io.exoquery

import io.exoquery.annotation.Dsl
import io.exoquery.annotation.DslBooleanExpression
import io.exoquery.annotation.DslFunctionCall
import io.exoquery.annotation.DslFunctionCallType
import io.exoquery.annotation.DslNestingIgnore
import io.exoquery.annotation.ExoCapture
import io.exoquery.annotation.ExoCaptureBatch
import io.exoquery.annotation.ExoCaptureExpression
import io.exoquery.annotation.ExoCaptureSelect
import io.exoquery.annotation.ExoDelete
import io.exoquery.annotation.ExoInsert
import io.exoquery.annotation.ExoUpdate
import io.exoquery.annotation.ExoUseExpression
import io.exoquery.annotation.ExoValue
import io.exoquery.annotation.FlatJoin
import io.exoquery.annotation.FlatJoinLeft
import io.exoquery.annotation.ParamCtx
import io.exoquery.annotation.ParamCustom
import io.exoquery.annotation.ParamCustomValue
import io.exoquery.annotation.ParamPrimitive
import io.exoquery.annotation.ParamStatic
import io.exoquery.innerdsl.SqlActionFilterable
import io.exoquery.innerdsl.set
import io.exoquery.innerdsl.setParams
import io.exoquery.serial.ParamSerializer
import io.exoquery.sql.SqlQueryModel
import io.exoquery.xr.EncodingXR
import io.exoquery.xr.XR
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromHexString
import org.intellij.lang.annotations.Language
import kotlin.reflect.KClass

fun unpackExpr(expr: String): XR.Expression =
  EncodingXR.protoBuf.decodeFromHexString<XR.Expression>(expr)

fun unpackQueryModel(query: String): SqlQueryModel =
  EncodingXR.protoBuf.decodeFromHexString<SqlQueryModel>(query)

fun unpackQueryModelLazy(query: String): () -> SqlQueryModel =
  { unpackQueryModel(query) }

fun unpackQuery(query: String): XR.Query =
  EncodingXR.protoBuf.decodeFromHexString<XR.Query>(query)

fun unpackQueryLazy(query: String): () -> XR.Query =
  { unpackQuery(query) }

fun unpackAction(action: String): XR.Action =
  EncodingXR.protoBuf.decodeFromHexString<XR.Action>(action)

fun unpackActionLazy(action: String): () -> XR.Action =
  { unpackAction(action) }

fun unpackBatchAction(batchAction: String): XR.Batching =
  EncodingXR.protoBuf.decodeFromHexString<XR.Batching>(batchAction)

fun unpackBatchActionLazy(batchAction: String): () -> XR.Batching =
  { unpackBatchAction(batchAction) }

// data class Person(val name: String, val age: Int)
// fun example() {
//   val v = capture {
//     Table<Person>().map { p -> p.age }
//   }
// }

class MissingCaptureError(val msg: String) : IllegalStateException(msg)

fun errorCap(message: Any): Nothing = throw MissingCaptureError(message.toString())


object capture {

  // TODO When context recivers are finally implemented in KMP, make this have a context-reciver that allows `use` to be used, otherwise don't allow it
  @ExoCaptureExpression
  fun <T> expression(block: CapturedBlock.() -> T): @Captured SqlExpression<T> =
    errorCap("Compile time plugin did not transform the tree")

  // Very interesting thing happen to the annotation if we do this. I.e. when something like a conditional
// happens e.g. `if (foo) capture { query } else throw ...` then instead of being @Captured SqlQuery it will be just SqlQuery which
// is actually the behavior that we wanted since the start.
  @ExoCapture
  operator fun <SqlContainer : ContainerOfXR> invoke(block: CapturedBlock.() -> SqlContainer): @Captured SqlContainer =
    errorCap("Compile time plugin did not transform the tree")

  /**
   * Use this to start a top-level Select Clause (i.e. one that is not already inside of a Capture).
   * For example:
   * ```
   * val people: SqlQuery<<Pair<Person, Address>> =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val a = join(Table<Address>()) { a -> a.ownerId == p.id }
   *     p to a
   *   }
   * ```
   * The `from` and `join` clauses can use SqlQuery instances that are already created. For example:
   * ```
   * val joes = capture { Table<Person>().filter { it.first.name == "Joe" } }
   * val addressesInNYC = capture { Table<Address>().filter { it.city == "New York" } }
   * val joesInNYC =
   *   capture.select {
   *     val p = from(joes)
   *     val a = join(addressesInNYC) { a -> a.ownerId == p.id }
   *     p to a
   *   }
   * // SQL: SELECT p.id, p.name, p.age, a.id, a.ownerId, a.city FROM people p JOIN addresses a ON a.ownerId = p.id WHERE p.name = 'Joe' AND a.city = 'New York'
   * ```
   * Note that in the above examples we would define `Person` and `Address` as follows:
   * ```
   * data class Person(val id: Int, val name: String, val age: Int)
   * data class Address(val id: Int, val ownerId: Int, val city: String)
   * ```
   * There are several additional constructs that can be used in a select-clause for example. You can add a `where` clause to filter the whole block:
   * ```
   * val joesInNYC =
   *  capture.select {
   *    val p = from(Table<Person>())
   *    val a = join(Table<Address>()) { a -> a.ownerId == p.id }
   *    where { p.name == "Joe" && a.city == "New York" }
   *    p to a
   *  }
   * ```
   * You can also add a `groupBy` clause to group the results:
   * ```
   * val countPeopleByCity =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val a = join(Table<Address>()) { a -> a.ownerId == p.id }
   *     groupBy { a.city }
   *     Stats(a.city, count(p.id))
   *   }
   * // SQL: SELECT a.city, COUNT(p.id) FROM people p JOIN addresses a ON a.ownerId = p.id GROUP BY a.city
   * // Where Stats is defined as:
   * // data class Stats(val city: String, val peopleCount: Int)
   * ```
   *
   * You can also use sortBy:
   * ```
   * val peopleSortedByAge =
   *  capture.select {
   *    val p = from(Table<Person>())
   *    sortBy(p.age to Asc)
   *  }
   *  // SQL: SELECT p.id, p.name, p.age FROM people p ORDER BY p.age ASC
   */
  @ExoCaptureSelect
  fun <T> select(block: SelectClauseCapturedBlock.() -> T): @Captured SqlQuery<T> =
    errorCap("The `select` expression of the Query was not inlined")

  // TODO go on to build transform for this
  @ExoCaptureBatch
  fun <BatchInput, Input : Any, Output> batch(
    batchCollection: Sequence<BatchInput>,
    block: SelectClauseCapturedBlock.(BatchInput) -> SqlAction<Input, Output>
  ): @Captured SqlBatchAction<BatchInput, Input, Output> =
    errorCap("The `batch` expression of the Query was not inlined")
}

//fun <T> capture(block: CapturedBlock.() -> SqlQuery<T>): @Captured SqlQuery<T> = errorCap("Compile time plugin did not transform the tree")

interface StringSqlDsl {
  @DslFunctionCall(DslFunctionCallType.PureFunction::class)
  fun left(i: Int): String = errorCap("The `left` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.PureFunction::class)
  fun right(i: Int): String = errorCap("The `right` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.PureFunction::class)
  fun replace(old: String, new: String): String = errorCap("The `replace` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.PureFunction::class)
  fun substring(start: Int, end: Int): String = errorCap("The `substring` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.PureFunction::class)
  fun uppercase(): String = errorCap("The `upperCase` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.PureFunction::class)
  fun lowercase(): String = errorCap("The `upperCase` expression of the Query was not inlined")
}


// Use for interpolate blocks e.g. free("foo bar").<String>() where free("foo bar") is a FreeBlock instance
// no values of this should ever be created (that is called a "phantom type" in various circles)


sealed interface CapturedBlockInternal {
  @Dsl
  @FlatJoin
  fun <T> flatJoin(query: SqlQuery<T>, cond: (T) -> Boolean): SqlQuery<T> =
    errorCap("The `flatJoin` expression of the Query was not inlined")

  @Dsl
  @FlatJoinLeft
  fun <T> flatJoinLeft(query: SqlQuery<T>, cond: (T) -> Boolean): SqlQuery<T?> =
    errorCap("The `flatJoinLeft` expression of the Query was not inlined")
}

sealed interface FreeBlock
interface CapturedBlock {

  // Render this as an in-set, if any of them are actually params need to use param(...) inside
  //fun <T: Any> values(vararg values: T): Values<T> = errorCap("Compile time plugin did not transform the tree")

  /**
   * Create a SqlQuery instance from a data-class. For example:
   * ```
   * data class Person(val id: Int, val name: String, val age: Int)
   * val people = capture { TableQuery<Person>() }
   * // SQL: SELECT p.id, p.name, p.age FROM people p
   * ```
   *
   * Use this with things like `map`, `filter`, `union`, etc.. to compose your query:
   * ```
   * val joes = capture { people.filter { it.name == "Joe" } }
   * // SQL: SELECT p.id, p.name, p.age FROM people p WHERE p.name = 'Joe'
   * ```
   */
  @Dsl
  fun <T> Table(): SqlQuery<T> = errorCap("The `Table<T>` constructor function was not inlined")

  /**
   * Project a SqlQuery to a different type. There are several kinds of things you can project to.
   *
   * Project to a primitive:
   * ```
   * val ages = capture { p -> people.map { p.age } }
   * // SQL: SELECT p.age FROM people p
   * ```
   *
   * Project do a tuple (or triple)
   * ```
   * val namesAndAges = capture { p -> people.map { p.name to p.age } }
   * // SQL: SELECT p.name, p.age FROM people p
   * ```
   *
   * Project to a data-class:
   * ```
   * data class NamesAndAges(val name: String, val age: Int)
   * val namesAndAges = capture { people.map { p -> NamesAndAges(p.name, p.age) } }
   * // SQL: SELECT p.name, p.age FROM people p
   * ```
   * Note that the name of the variable that you use will be directly translated into SQL including the `it` variable:
   * ```
   * val namesAndAges = capture { people.map { NamesAndAges(it.name, it.age) } }
   * // SQL: SELECT it.name, it.age FROM people it
   * ```
   *
   */
  @Dsl
  fun <T, R> SqlQuery<T>.map(f: (T) -> R): SqlQuery<R> = errorCap("The `map` expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlQuery<T>.flatMap(f: (T) -> SqlQuery<R>): SqlQuery<R> =
    errorCap("The `flatMap` expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlQuery<T>.concatMap(f: (T) -> Iterable<R>): SqlQuery<R> =
    errorCap("The `concatMap` expression of the Query was not inlined")

  /**
   * Filter a SqlQuery based on a predicate
   * ```
   * val joes = capture { people.filter { it.name == "Joe" } }
   * ```
   * If you are starting with just a data-class use it like this:
   * ```
   * data class Person(val id: Int, val name: String, val age: Int)
   * val joes = capture { TableQuery<Person>().filter { it.name == "Joe" } }
   * ```
   */
  @Dsl
  fun <T> SqlQuery<T>.filter(f: (T) -> Boolean): SqlQuery<T> =
    errorCap("The `filter` expression of the Query was not inlined")

  /**
   * Filter a SqlQuery based on a predicate (same as `.filter` using a receiver for more compact expressions)
   * ```
   * val joes = capture { people.where { name == "Joe" } }
   * ```
   * If you are starting with just a data-class use it like this:
   * ```
   * data class Person(val id: Int, val name: String, val age: Int)
   * val joes = capture { TableQuery<Person>().where { name == "Joe" } }
   * ```
   */
  @Dsl
  fun <T> SqlQuery<T>.where(f: (T).() -> Boolean): SqlQuery<T> =
    errorCap("The `where` expression of the Query was not inlined")

  /**
   * Make a union of two queries. Both queries must be SqlQuery<T> instances with the same T.
   * ```
   * val allPeople = capture { somePeople.union(otherPeople) }
   * ```
   * For example:
   * ```
   * data class Person(val id: Int, val name: String, val age: Int)
   * val joes = capture { TableQuery<Person>().filter { it.name == "Joe" } }
   * val jacks = capture { TableQuery<Person>().filter { it.name == "Jack" } }
   * val joesAndJacks = capture { joes.union(jacks) }
   */
  @Dsl
  infix fun <T> SqlQuery<T>.union(other: SqlQuery<T>): SqlQuery<T> =
    errorCap("The `union` expression of the Query was not inlined")

  /**
   * Same as Union but UnionAll in SQL
   * ```
   * val allPeople = capture { somePeople.unionAll(otherPeople) }
   * ```
   * For example:
   * ```
   * data class Person(val id: Int, val name: String, val age: Int)
   * val joes = capture { TableQuery<Person>().filter { it.name == "Joe" } }
   * val jacks = capture { TableQuery<Person>().filter { it.name == "Jack" } }
   * val joesAndJacks = capture { joes.unionAll(jacks) }
   * ```
   */
  @Dsl
  infix fun <T> SqlQuery<T>.unionAll(other: SqlQuery<T>): SqlQuery<T> =
    errorCap("The `unionAll` expression of the Query was not inlined")

  @Dsl
  operator fun <T> SqlQuery<T>.plus(other: SqlQuery<T>): SqlQuery<T> =
    errorCap("The `unionAll` expression of the Query was not inlined")

  @Dsl
  fun <T> SqlQuery<T>.distinct(): SqlQuery<T> = errorCap("The `distinct` expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlQuery<T>.distinctOn(f: (T) -> R): SqlQuery<T> =
    errorCap("The `distinctBy` expression of the Query was not inlined")

  /**
   * Use this to call specialized functions on Strings that are specific to SQL. For example:
   * ```
   * people.map { p -> p.name.sql.right(3) }
   * // SQL: SELECT RIGHT(p.name, 3) FROM people p
   * ```
   * Aside from substring and concatenation, most Kotlin string methods cannot be used inside of capture clauses.
   */
  @DslNestingIgnore
  val String.sql @DslNestingIgnore get(): StringSqlDsl = errorCap("The `sql-dsl` expression of the Query was not inlined")

  @Dsl
  fun <T> SqlQuery<T>.nested(): SqlQuery<T> = errorCap("The `nested` expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlQuery<T>.sortedBy(f: (T) -> R): SqlQuery<T> =
    errorCap("The sort-by expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlQuery<T>.sortedByDescending(f: (T) -> R): SqlQuery<T> =
    errorCap("The sort-by expression of the Query was not inlined")

  @Dsl
  fun <T> SqlQuery<T>.take(f: Int): SqlQuery<T> = errorCap("The take expression of the Query was not inlined")

  @Dsl
  fun <T> SqlQuery<T>.drop(f: Int): SqlQuery<T> = errorCap("The drop expression of the Query was not inlined")

  @Dsl
  fun <T> SqlQuery<T>.size(): SqlQuery<Int> = errorCap("The size expression of the Query was not inlined")

  // Used in groupBy and various other places to convert query to an expression
  // e.g. events.filter(ev -> people.map(p -> customFunction(p.age)).value() > ev.minAllowedAge).value()
  @Dsl
  fun <T> SqlQuery<T>.value(): T = errorCap("The `value` expression of the Query was not inlined")

  @Dsl
  fun over(): WindowDsl

  /* ------------------------------------------------------------------------------------------------ */
  /* ----------------------------------------- Composition ------------------------------------------ */
  /* ------------------------------------------------------------------------------------------------ */

  @Dsl
  fun free(@Language("SQL") block: String): FreeBlock = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  fun <T> select(block: SelectClauseCapturedBlock.() -> T): SqlQuery<T> =
    errorCap("The `select` expression of the Query was not inlined")

  @Dsl
  val <T> SqlExpression<T>.use: T
    @ExoUseExpression @Dsl get() =
      throw IllegalArgumentException("Cannot `use` an SqlExpression outside of a quoted context")

  /* ------------------------------------------------------------------------------------------------ */
  /* ------------------------------------ Aggregation Operators ------------------------------------ */
  /* ------------------------------------------------------------------------------------------------ */

  // Use this in the select or map clauses e.g. people.map(p -> min(p.age))
  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T : Comparable<T>> min(value: T): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T : Comparable<T>> max(value: T): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T : Comparable<T>> avg(value: T): Double = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T : Comparable<T>> stddev(value: T): Double = errorCap("The `stddev` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T : Comparable<T>> sum(value: T): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T> count(value: T): Int = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.Aggregator::class)
  fun <T> countDistinct(vararg value: T): Int = errorCap("The `min` expression of the Query was not inlined")


  /**
   * Use this in the select or map clauses to do a `COUNT(*)` query. For example:
   * ```
   * val peopleCount = capture { people.map { count() } }
   * // SQL: SELECT COUNT(*) FROM people p
   * ```
   * Or:
   * ```
   * val peopleCount =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     count()
   *   }
   * // SQL: SELECT COUNT(*) FROM people p
   * ```
   */
  @DslFunctionCall(DslFunctionCallType.ImpureFunction::class, "COUNT_STAR")
  fun count(): Int = errorCap("The `min` expression of the Query was not inlined")

  // Use this as an aggregator for a query e.g. people.map(p -> p.age).min()
  // this is useful for co-releated subqueries e.g. events.filter(ev -> people.map(p -> p.age).avg() > ev.minAllowedAge) i.e. events to which the average person can come to
  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  fun <T : Comparable<T>> SqlQuery<T>.min(): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  fun <T : Comparable<T>> SqlQuery<T>.max(): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  fun <T : Comparable<T>> SqlQuery<T>.avg(): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  fun <T : Comparable<T>> SqlQuery<T>.sum(): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  fun <T> SqlQuery<T>.count(): T = errorCap("The `min` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  @DslBooleanExpression
  fun <T> SqlQuery<T>.isNotEmpty(): Boolean = errorCap("The `isNotEmpty` expression of the Query was not inlined")

  @DslFunctionCall(DslFunctionCallType.QueryAggregator::class)
  @DslBooleanExpression
  fun <T> SqlQuery<T>.isEmpty(): Boolean = errorCap("The `isEmpty` expression of the Query was not inlined")


  /* ------------------------------------------------------------------------------------------------ */
  /* ----------------------------------------------- Params ----------------------------------------- */
  /* ------------------------------------------------------------------------------------------------ */

  // TODO add asAction<Input, Output> and asQuery<Output> to avoid asPure overuse
  operator fun <T> FreeBlock.invoke(): T = errorCap("The `invoke` expression of the Query was not inlined")
  fun <T> FreeBlock.asPure(): T = errorCap("The `invoke` expression of the Query was not inlined")
  fun FreeBlock.asConditon(): Boolean = errorCap("The `invoke` expression of the Query was not inlined")
  fun FreeBlock.asPureConditon(): Boolean = errorCap("The `invoke` expression of the Query was not inlined")

  val internal: CapturedBlockInternal

  @Dsl
  @ParamStatic(ParamSerializer.String::class)
  fun param(value: String): String = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Char::class)
  fun param(value: Char): Char = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Int::class)
  fun param(value: Int): Int = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Short::class)
  fun param(value: Short): Short = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Long::class)
  fun param(value: Long): Long = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Float::class)
  fun param(value: Float): Float = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Double::class)
  fun param(value: Double): Double = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.Boolean::class)
  fun param(value: Boolean): Boolean = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.LocalDate::class)
  fun param(value: kotlinx.datetime.LocalDate): kotlinx.datetime.LocalDate =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.LocalTime::class)
  fun param(value: kotlinx.datetime.LocalTime): kotlinx.datetime.LocalTime =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamStatic(ParamSerializer.LocalDateTime::class)
  fun param(value: kotlinx.datetime.LocalDateTime): kotlinx.datetime.LocalDateTime =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamPrimitive
  fun <T> param(value: T): T =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamCtx
  fun <T> paramCtx(value: T): @Contextual T = errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamCustom
  fun <T : Any> paramCustom(value: T, serializer: SerializationStrategy<T>): @ExoValue T =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamCustomValue
  fun <T : Any> param(value: ValueWithSerializer<T>): @ExoValue T =
    errorCap("Compile time plugin did not transform the tree")

  // I.e. the the list is lifted but as individual elements
  //@Dsl @ParamStatic(ParamSerializer.String::class) fun params(values: List<String>): Params<String> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Char::class) fun params(values: List<Char>): Params<Char> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Int::class) fun params(values: List<Int>): Params<Int> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Short::class) fun params(values: List<Short>): Params<Short> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Long::class) fun params(values: List<Long>): Params<Long> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Float::class) fun params(values: List<Float>): Params<Float> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Double::class) fun params(values: List<Double>): Params<Double> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.Boolean::class) fun params(values: List<Boolean>): Params<Boolean> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.LocalDate::class) fun params(values: List<kotlinx.datetime.LocalDate>): Params<kotlinx.datetime.LocalDate> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.LocalTime::class) fun params(values: List<kotlinx.datetime.LocalTime>): Params<kotlinx.datetime.LocalTime> = errorCap("Compile time plugin did not transform the tree")
  //@Dsl @ParamStatic(ParamSerializer.LocalDateTime::class) fun params(values: List<kotlinx.datetime.LocalDateTime>): Params<kotlinx.datetime.LocalDateTime> = errorCap("Compile time plugin did not transform the tree")

  // TODO remove this and introduce the ones above when @SignatureName is available in KMP: https://youtrack.jetbrains.com/issue/KT-52009/Kotlin-binary-signature-name
  @Dsl
  @ParamPrimitive
  fun <T : Any> params(values: List<T>): Params<@ExoValue T> =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamCtx
  fun <T : Any> paramsCtx(values: List<T>): Params<@Contextual T> =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamCustom
  fun <T : Any> paramsCustom(values: List<T>, serializer: SerializationStrategy<T>): Params<@ExoValue T> =
    errorCap("Compile time plugin did not transform the tree")

  @Dsl
  @ParamCustomValue
  fun <T : Any> params(values: ValuesWithSerializer<T>): Params<@ExoValue T> =
    errorCap("Compile time plugin did not transform the tree")

  /* ----------------------------------------------------------------------------------------------------------- */
  /* --------------------------------- Actions (Insert, Update, Delete) ---------------------------------------- */
  /* ----------------------------------------------------------------------------------------------------------- */


  @Dsl
  @ExoInsert
  fun <T> insert(valueBlock: (T).() -> set<T>): SqlAction<T, Long> =
    errorCap("The `insertValues` expression of the Query was not inlined")

  @Dsl
  @ExoUpdate
  fun <T> update(valueBlock: (T).() -> set<T>): SqlActionFilterable<T, Long> =
    errorCap("The `insertValues` expression of the Query was not inlined")

  @Dsl
  @ExoDelete
  fun <T> delete(): SqlActionFilterable<T, Long> =
    errorCap("The `insertValues` expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlAction<T, Long>.returning(expression: (T) -> R): SqlAction<T, R> =
    errorCap("The `returning` expression of the Query was not inlined")

  @Dsl
  fun <T, R> SqlAction<T, Long>.returningKeys(expression: (T).() -> R): SqlAction<T, R> =
    errorCap("The `returning` expression of the Query was not inlined")

  // Specifically for doing generated-key return that is implicit i.e. where you use PreparedStatement.getGeneratedKeys() without an explicit RETURNING/OUTPUT clause in the SQL
  @Dsl
  fun <Input, Output, R1> SqlAction<Input, Output>.retruningKeys(columns: List<Any>): SqlAction<Input, R1> =
    errorCap("The `returning` expression of the Query was not inlined")

  @Dsl
  fun <Input, Output> SqlActionFilterable<Input, Output>.filter(block: (Input) -> Boolean): SqlAction<Input, Output> =
    errorCap("The `filter` expression of the Query was not inlined")

  @Dsl
  fun <Input, Output> SqlActionFilterable<Input, Output>.where(block: (Input).() -> Boolean): SqlAction<Input, Output> =
    errorCap("The `where` expression of the Query was not inlined")

  // If the user is performing a Update without a filter we want this to be stated explicitly
  @Dsl
  fun <Input, Output> SqlActionFilterable<Input, Output>.all(): SqlAction<Input, Output> =
    errorCap("The `where` expression of the Query was not inlined")


  fun <T> set<T>.onConflictUpdate(vararg fields: Any?, exclusions: (T) -> set<T>): set<T> =
    errorCap("The `onConflictUpdate` expression of the Query was not inlined")

  fun <T> set<T>.onConflictIgnore(vararg fields: Any?): set<T> =
    errorCap("The `onConflictUpdate` expression of the Query was not inlined")

  /** Only for insert and update */
  @Dsl
  fun <T> T.set(vararg values: Pair<Any?, Any?>): set<T> = errorCap("The `set` expression of the Query was not inlined")

  @Dsl
  fun <T> T.setParams(value: T): setParams<T> = errorCap("The `setParams` expression of the Query was not inlined")

  @Dsl
  fun <T> setParams<T>.excluding(vararg columns: Any?): set<T> =
    errorCap("The `excluding` expression of the Query was not inlined")
}

interface Params<T> {
  operator fun contains(value: T): Boolean = errorCap("The `contains` expression of the Query was not inlined")
}

sealed interface Ord {
  @Dsl
  object Asc : Ord

  @Dsl
  object Desc : Ord

  @Dsl
  object AscNullsFirst : Ord

  @Dsl
  object DescNullsFirst : Ord

  @Dsl
  object AscNullsLast : Ord

  @Dsl
  object DescNullsLast : Ord
}

interface SelectClauseCapturedBlock : CapturedBlock {
  /**
   * Use this to delcare a new `FROM MyTable t` SQL clause and return the `t`. For example:
   * ```
   * val people = capture.select {
   *   val p = from(Table<Person>())
   *   p
   * }
   * // SQL: SELECT p.id, p.name, p.age FROM people p
   * ```
   * You can use multiple `from` clauses in to do full joins. For example:
   * ```
   * val allPeopleAllAddresses =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val a = from(Table<Address>())
   *     p to a
   *   }
   * // SQL: SELECT p.id, p.name, p.age, a.id, a.ownerId, a.city FROM people p, addresses a
   * ```
   */
  @Dsl
  fun <T> from(query: SqlQuery<T>): T = errorCap("The `from` expression of the Query was not inlined")

  /**
   * Use this to join a table. For example:
   * ```
   * val peopleWithAddresses =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val a = join(Table<Address>()) { a -> a.ownerId == p.id }
   *     p to a
   *   }
   * // SQL: SELECT p.id, p.name, p.age, a.id, a.ownerId, a.city FROM people p JOIN addresses a ON a.ownerId = p.id
   * ```
   */
  @Dsl
  fun <T> join(onTable: SqlQuery<T>, condition: (T) -> Boolean): T =
    errorCap("The `join` expression of the Query was not inlined")

  /**
   * Use this to left-join a table. For example:
   * ```
   * val peopleWithAddresses =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val aa: Address? = joinLeft(Table<Address>()) { a: Address -> aa.ownerId == p.id }
   *     p to aa
   *   }
   * // SQL: SELECT p.id, p.name, p.age, a.id, a.ownerId, a.city FROM people p LEFT JOIN addresses a ON a.ownerId = p.id
   * ```
   * Note that the returned address variable `aa` will be nullable while the variable `a` used in the join
   * condition will not. These will be unified when the SQL query is synthesized.
   */
  @Dsl
  fun <T> joinLeft(onTable: SqlQuery<T>, condition: (T) -> Boolean): T? =
    errorCap("The `joinLeft` expression of the Query was not inlined")

  /**
   * Use this to filter the whole block. For example:
   * ```
   * val joesInNYC =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val a = join(Table<Address>()) { a -> a.ownerId == p.id }
   *     where { p.name == "Joe" && a.city == "New York" }
   *     p to a
   *   }
   * ```
   */
  @Dsl
  fun where(condition: () -> Boolean): Unit = errorCap("The `where` expression of the Query was not inlined")

  /**
   * Use this to group by one or multiple columns. For example:
   * ```
   * val countPeopleByCity =
   *   capture.select {
   *     val p = from(Table<Person>())
   *     val a = join(Table<Address>()) { a -> a.ownerId == p.id }
   *     groupBy { a.city }
   *     Stats(a.city, count(p.id))
   *  }
   * // SQL: SELECT a.city, COUNT(p.id) FROM people p JOIN addresses a ON a.ownerId = p.id GROUP BY a.city
   * // Where Stats is defined as:
   * // data class Stats(val city: String, val peopleCount: Int)
   * ```
   */
  @Dsl
  fun groupBy(vararg groupings: Any?): Unit = errorCap("The `groupBy` expression of the Query was not inlined")

  /**
   * Use this to sort by one or multiple columns. For example:
   * ```
   * val peopleSortedByAge =
   *  capture.select {
   *    val p = from(Table<Person>())
   *    orderBy(p.age to Ord.Asc, p.name to Ord.Desc)
   *    p
   *  }
   *  // SQL: SELECT p.id, p.name, p.age FROM people p ORDER BY p.age
   * ```
   */
  @Dsl
  fun orderBy(vararg orderings: Pair<*, Ord>): Unit = errorCap("The `sortBy` expression of the Query was not inlined")


  /** Synonym for [orderBy] */
  @Dsl
  fun sortBy(vararg orderings: Pair<*, Ord>): Unit = errorCap("The `sortBy` expression of the Query was not inlined")
}

interface ValueWithSerializer<T : Any> {
  val value: T
  val serializer: KSerializer<T>
  val cls: KClass<T>

  fun asParam(): ParamSerializer<T> = ParamSerializer.Custom(serializer, cls)

  companion object {
    operator inline fun <reified T : Any> invoke(
      value: T,
      serializer: KSerializer<T>
    ): ValueWithSerializer<T> =
      ConcreteValueWithSerializer<T>(value, serializer, T::class)
  }
}

@PublishedApi
internal data class ConcreteValueWithSerializer<T : Any>(
  override val value: T,
  override val serializer: KSerializer<T>,
  override val cls: KClass<T>
) : ValueWithSerializer<T>

interface ValuesWithSerializer<T : Any> {
  val values: List<T>
  val serializer: KSerializer<T>
  val cls: KClass<T>

  fun asParam(): ParamSerializer<T> = ParamSerializer.Custom(serializer, cls)

  companion object {
    operator inline fun <reified T : Any> invoke(
      values: List<T>,
      serializer: KSerializer<T>
    ): ValuesWithSerializer<T> =
      ConcreteValuesWithSerializer<T>(values, serializer, T::class)
  }
}

@PublishedApi
internal data class ConcreteValuesWithSerializer<T : Any>(
  override val values: List<T>,
  override val serializer: KSerializer<T>,
  override val cls: KClass<T>
) : ValuesWithSerializer<T>
