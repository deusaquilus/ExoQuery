package io.exoquery.xr

import io.exoquery.xr.id
import io.exoquery.BID
import io.exoquery.pprint.PPrinterConfig
import io.exoquery.printing.PrintXR
import io.exoquery.sql.MirrorIdiom
import io.exoquery.sql.Renderer
import io.exoquery.sql.Token
import io.exoquery.util.NumbersToWords
import io.exoquery.util.ShowTree
import io.exoquery.util.dropLastSegment
import io.exoquery.util.takeLastSegment
import io.exoquery.xr.XR.U.QueryOrExpression
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import io.decomat.Matchable as Mat
import io.decomat.Component as Slot
import io.decomat.MiddleComponent as MSlot
import io.decomat.ConstructorComponent as CS
import io.decomat.productComponentsOf as productOf
import io.decomat.HasProductClass as PC


// TODO everything now needs to use Decomat Ids
// TODO everything now needs to use Decomat Ids

/**
 * This is the core syntax tree there are essentially three primary concepts represented here:
 * 1. XR.Query - These are Entity, Map, FlatMap, etc... that represent the building blocks of the `from` clause
 *    of SQL statements.
 * 2. XR.Expression - These are values like Ident, Const, etc... as well as XR.Function (which are essentially lambdas
 *    that are invoked via FunctionApplly), operators and functions.
 * 3. Actions - These are Sql Insert, Delete, Update and possibly corresponding batch actions.
 *
 * This only exceptions to this rule are Free and Marker blocks that are used as any of the above four.
 * Free in particular is represented as so sql("any_${}_sql_${}_here").as[Query/Expression/Action]
 *
 * The other miscellaneous elements are Branch, Block, and Variable which are used in the `when` clause i.e. XR.When
 * The type Ordering is used in the `sortBy` clause i.e. XR.SortBy is technically not part of the XR but closely related
 * enough that it needs corresponding Lifters and transforms.
 *
 * The hardest thing for me to understand when I started work on Quill what kinds of IR/AST
 * elements can fit inside of what other kinds. This was particular problematic because
 * they read something like `FlatMap(a: Ast, b: Ident, c: Ast)` where both `a` and `c`
 * could be anything from an Ident("x") to a `ReturningAction`. This syntax tree
 * attempts to balance expressability with an approporiate of constraints via subtyping relations.
 */
@Serializable
sealed interface XR {
  // The primary types of XR are Query, Expression, Function, and Action
  // there are additional union-types like QueryOrExpression that are used in various things like beta-reduction
  object U {
    // Things that store their own XRType. Right now this is just an Ident but in the future
    // it will also be a lifted value.
    @Serializable
    sealed interface Call : XR {
      fun isPure(): Boolean
      fun isAggregation(): Boolean
    }

    @Serializable
    sealed interface CoreAction : Action, XR {
      val alias: Ident
      override fun coreAlias(): Ident
    }

    /**
     * This is used specifically for detecting situations where there is a FlatJoin
     * in both head and tail position. Something like:
     * ```
     *     capture {
     *       people.flatMap { p ->
     *         flatJoin(addresses, p.id = ...).map { a -> p to a }
     *           .flatMap { pa ->
     *             flatJoin(robots, pa.first.id = ...).map { r -> ... }
     *           }
     *       }
     *     }
     * ```
     */
    @Serializable
    sealed interface HasHead {
      val head: XR.Query
      fun replaceHead(newHead: XR.Query): XR.Query =
        when (this) {
          is XR.Filter -> XR.Filter(newHead, id, body)
          is XR.Map -> XR.Map(newHead, id, body)
          is XR.ConcatMap -> XR.ConcatMap(newHead, id, body)
          is XR.FlatMap -> XR.FlatMap(newHead, id, body)
        }
    }

    @Serializable
    sealed interface Terminal : Expression, XR

    @Serializable
    sealed interface FlatUnit : XR.Query

    @Serializable
    sealed interface QueryOrExpression : XR {
      fun asExpr(): XR.Expression =
        when (this) {
          is XR.Expression -> this
          is XR.Query -> XR.QueryToExpr(this)
        }

      fun asQuery(): XR.Query =
        when (this) {
          is XR.Expression -> XR.ExprToQuery(this)
          is XR.Query -> this
        }
    }
  }

  abstract val type: XRType
  abstract val loc: XR.Location

  fun show(pretty: Boolean = false, sanitzeIdents: Boolean = true, renderOptions: MirrorIdiom.RenderOptions = MirrorIdiom.RenderOptions()): String {
    val xr: XR =
      TransformXR({
        when (it) {
          is Ident -> it.copy(name = it.name.replace("$", "")); else -> null
        }
      }).invoke(this)
    return with(MirrorIdiom(renderOptions)) {
      xr.token.renderWith(Renderer())
    }
  }

  @Serializable
  sealed interface Location {
    @Serializable
    data class File(val path: String, val row: Int, val col: Int) : Location
    @Serializable
    data object Synth : Location
  }


  fun showRaw(color: Boolean = true, config: PPrinterConfig = PPrinterConfig()): String {
    val str = PrintXR(XR.serializer())(this)
    return if (color) str.toString() else str.plainText
  }

  //fun translateWith(idiom: SqlIdiom) = idiom.translate(this)


  @Serializable
  sealed interface JoinType {
    val simpleName: String

    @Serializable
    data object Inner : JoinType {
      override fun toString() = "Inner";
      override val simpleName = "join"
    }

    @Serializable
    data object Left : JoinType {
      override fun toString() = "Left";
      override val simpleName = "leftJoin"
    }
  }

  @Serializable
  sealed interface Expression : XR, U.QueryOrExpression {
    fun isBoolean() = type.isBooleanValue() || type.isBooleanExpression()
    fun isBooleanValue() = type.isBooleanValue()
    fun isBooleanExpression() = type.isBooleanExpression()
  }

  @Serializable
  sealed interface Query : XR, U.QueryOrExpression

  // *******************************************************************************************
  // ****************************************** Query ******************************************
  // *******************************************************************************************

  @Serializable
  @Mat
  data class Entity(@Slot val name: String, override val type: XRType.Product, override val loc: Location = Location.Synth) : Query, PC<Entity> {
    @Transient
    override val productComponents = productOf(this, name)

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Entity && other.id() == cid
  }

  @Serializable
  @Mat
  data class Filter(@Slot override val head: XR.Query, @MSlot val id: XR.Ident, @Slot val body: XR.Expression, override val loc: Location = Location.Synth) : Query, U.HasHead, PC<Filter> {
    @Transient
    override val productComponents = productOf(this, head, id, body)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Filter && other.id() == cid
  }

  @Serializable
  @Mat
  data class Map(@Slot override val head: XR.Query, @MSlot val id: XR.Ident, @Slot val body: XR.Expression, override val loc: Location = Location.Synth) : Query, U.HasHead, PC<Map> {
    @Transient
    override val productComponents = productOf(this, head, id, body)
    override val type get() = body.type

    companion object {
    }

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Map && other.id() == cid
  }

  @Serializable
  @Mat
  data class ConcatMap(@Slot override val head: XR.Query, @MSlot val id: XR.Ident, @Slot val body: XR.Expression, override val loc: Location = Location.Synth) : Query, U.HasHead, PC<ConcatMap> {
    @Transient
    override val productComponents = productOf(this, head, id, body)
    override val type get() = body.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is ConcatMap && other.id() == cid
  }

  @Serializable
  data class FqName(val path: List<String>) {
    val name by lazy { path.last() }

    companion object {
      operator fun invoke(fullPath: String): FqName =
        FqName(fullPath.split('.'))

      val Empty = FqName(listOf())
      val Cast = FqName(listOf("kotlinCast"))
      val CountDistinct = FqName("io.exoquery.CapturedBlock.countDistinct")
    }

    private val str by lazy { path.joinToString(".") }
    override fun toString(): String = str
  }

  @Serializable
  data class ClassId(val packageFqName: FqName, val relativeClassName: FqName) {
    companion object {
      /**
       * Assumes the last in a path segment is the class name e.g. in foo.bar.baz assumes class is baz and package is foo.bar
       */
      operator fun invoke(str: String): ClassId {
        val split = str.split(".")
        return when {
          split.size == 0 -> ClassId(FqName.Empty, FqName.Empty)
          split.size == 1 -> ClassId(FqName.Empty, FqName(split))
          split.size > 1 -> ClassId(FqName(split.dropLast(1)), FqName(split.takeLast(1)))
          else -> error("Unreachable")
        }
      }

      /**
       * Use this to define classes for enums and other things that have an object in their path
       * e.g. foo.bar.Color.Red would be called with ClassId("foo.bar", "Color.Red")
       */
      operator fun invoke(packagePath: String, relativePath: String) =
        ClassId(FqName(packagePath), FqName(relativePath))

      val Empty = ClassId(FqName.Empty, FqName.Empty)
    }
  }

  /**
   * This is the primary to way to turn a query into an expression both for things like aggregations
   * and co-related subqueries. For example an aggregation Query<Int>.avg in something like `people.map(_.age).avg`
   * should actually be represented as `people.map(_.age).map(i -> sum(i)).value` whose tree is:
   * `QueryToExpr(Map(Map(people, x, x.age), i, sum(i)))`. In situations where GlobalCall/MethodCall are used perhaps
   * we shold use this as well to convert to expressions. To fully support that we have
   * the reverse of QueryToExpr i.e. ExprToQuery that converts a XR.Expression back into an XR.Query.
   */
  @Serializable
  @Mat
  data class QueryToExpr(@Slot val head: XR.Query, override val loc: Location = Location.Synth) : Expression, PC<QueryToExpr> {
    @Transient
    override val productComponents = productOf(this, head)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is QueryToExpr && other.id() == cid
  }

  @Serializable
  @Mat
  data class ExprToQuery(@Slot val head: XR.Expression, override val loc: Location = Location.Synth) : Query, PC<ExprToQuery> {
    @Transient
    override val productComponents = productOf(this, head)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is ExprToQuery && other.id() == cid
  }

  @Serializable
  @Mat
  data class SortBy(@Slot val head: XR.Query, @MSlot val id: XR.Ident, @Slot val criteria: List<OrderField>, override val loc: Location = Location.Synth) : Query, PC<SortBy> {
    @Transient
    override val productComponents = productOf(this, head, id, criteria)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is SortBy && other.id() == cid
  }

  // OrderField elements are technically not part of the XR but closely related
  @Serializable
  sealed interface OrderField {
    @Serializable data class By(override val field: XR.Expression, val ordering: XR.Ordering) : OrderField
    @Serializable data class Implicit(override val field: XR.Expression) : OrderField

    val orderingOpt get() = when (this) {
      is By -> ordering
      is Implicit -> null
    }
    val field: XR.Expression
    fun transform(transform: (XR.Expression) -> XR.Expression): OrderField =
      when (this) {
        is By -> By(transform(field), ordering)
        is Implicit -> Implicit(transform(field))
      }
  }

  // Ordering elements are technically not part of the XR but closely related
  // TODO Clean-Up. Only need PropertyOrdering now. Got rid of tuple-ordering
  @Serializable
  sealed interface Ordering {
    @Serializable
    sealed interface PropertyOrdering : Ordering
    @Serializable
    data object Asc : PropertyOrdering
    @Serializable
    data object Desc : PropertyOrdering
    @Serializable
    data object AscNullsFirst : PropertyOrdering
    @Serializable
    data object DescNullsFirst : PropertyOrdering
    @Serializable
    data object AscNullsLast : PropertyOrdering
    @Serializable
    data object DescNullsLast : PropertyOrdering

    // TODO put this back once Dsl SortOrder is back
    //companion object {
    //  fun fromDslOrdering(orders: List<SortOrder>): XR.Ordering =
    //    if (orders.size == 1) {
    //      when (orders.first()) {
    //        SortOrder.Asc -> Ordering.Asc
    //        SortOrder.AscNullsFirst -> Ordering.AscNullsFirst
    //        SortOrder.AscNullsLast -> Ordering.AscNullsLast
    //        SortOrder.Desc -> Ordering.Desc
    //        SortOrder.DescNullsFirst -> Ordering.DescNullsFirst
    //        SortOrder.DescNullsLast -> Ordering.DescNullsLast
    //      }
    //    } else {
    //      // Repeat for N size=1 orders each one of which should give a single XR.Ordering
    //      Ordering.TupleOrdering(orders.map { fromDslOrdering(listOf(it)) })
    //    }
    //}
  }

  @Serializable
  @Mat
  data class Take(@Slot val head: XR.Query, @Slot val num: XR.Expression, override val loc: Location = Location.Synth) : Query, PC<Take> {
    @Transient
    override val productComponents = productOf(this, head, num)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Take && other.id() == cid
  }

  @Serializable
  @Mat
  data class Drop(@Slot val head: XR.Query, @Slot val num: XR.Expression, override val loc: Location = Location.Synth) : Query, PC<Drop> {
    @Transient
    override val productComponents = productOf(this, head, num)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Drop && other.id() == cid
  }

  // TODO refactor into UnionKind which will have Union, UnionAll, and other things like Intersect and Except
  @Serializable
  @Mat
  data class Union(@Slot val a: XR.Query, @Slot val b: XR.Query, override val loc: Location = Location.Synth) : Query, PC<Union> {
    @Transient
    override val productComponents = productOf(this, a, b)
    override val type get() = a.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Union && other.id() == cid
  }

  @Serializable
  @Mat
  data class UnionAll(@Slot val a: XR.Query, @Slot val b: XR.Query, override val loc: Location = Location.Synth) : Query, PC<UnionAll> {
    @Transient
    override val productComponents = productOf(this, a, b)
    override val type get() = a.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is UnionAll && other.id() == cid
  }

  @Serializable
  @Mat
  data class FlatMap(@Slot override val head: XR.Query, @MSlot val id: XR.Ident, @Slot val body: XR.Query, override val loc: Location = Location.Synth) : Query, U.HasHead, PC<FlatMap> {
    @Transient
    override val productComponents = productOf(this, head, id, body)
    override val type get() = body.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FlatMap && other.id() == cid
  }

  @Serializable
  @Mat
  data class FlatJoin(val joinType: JoinType, @Slot val head: XR.Query, @MSlot val id: XR.Ident, @Slot val on: XR.Expression, override val loc: Location = Location.Synth) : Query, PC<FlatJoin> {
    @Transient
    override val productComponents = productOf(this, head, id, on)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FlatJoin && other.id() == cid
  }

  @Serializable
  @Mat
  data class FlatGroupBy(@Slot val by: XR.Expression, override val loc: Location = Location.Synth) : Query, U.FlatUnit, PC<FlatGroupBy> {
    @Transient
    override val productComponents = productOf(this, by)
    override val type get() = by.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FlatGroupBy && other.id() == cid
  }

  @Serializable
  @Mat
  data class FlatSortBy(@Slot val criteria: List<XR.OrderField>, override val loc: Location = Location.Synth) : Query, U.FlatUnit, PC<FlatSortBy> {
    @Transient
    override val productComponents = productOf(this, criteria)
    override val type get() = XRType.Unknown

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FlatSortBy && other.id() == cid
  }

  @Serializable
  @Mat
  data class FlatFilter(@Slot val by: XR.Expression, override val loc: Location = Location.Synth) : Query, U.FlatUnit, PC<FlatFilter> {
    @Transient
    override val productComponents = productOf(this, by)
    override val type get() = by.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FlatFilter && other.id() == cid
  }

  @Serializable
  @Mat
  data class Distinct(@Slot val head: XR.Query, override val loc: Location = Location.Synth) : Query, PC<Distinct> {
    @Transient
    override val productComponents = productOf(this, head)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Distinct && other.id() == cid
  }

  @Serializable
  @Mat
  data class DistinctOn(@Slot val head: XR.Query, @MSlot val id: XR.Ident, @Slot val by: XR.Expression, override val loc: Location = Location.Synth) : Query, PC<DistinctOn> {
    @Transient
    override val productComponents = productOf(this, head, id, by)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is DistinctOn && other.id() == cid
  }

  @Serializable
  @Mat
  data class Nested(@Slot val head: XR.Query, override val loc: Location = Location.Synth) : XR.Query, PC<Nested> {
    @Transient
    override val productComponents = productOf(this, head)
    override val type get() = head.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Nested && other.id() == cid
  }

  // ************************************************************************************************
  // ****************************************** Free ********************************************
  // ************************************************************************************************

  @Serializable
  @Mat
  data class Free(@Slot val parts: List<String>, @Slot val params: List<XR>, val pure: Boolean, val transparent: Boolean, override val type: XRType, override val loc: Location = Location.Synth) : Query, Expression,
    Action, PC<Free> {
    @Transient
    override val productComponents = productOf(this, parts, params)

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Free && other.id() == cid

    // Tokenization of actions requires being able to extract the alias from the XR.Action. If this is a free of an action we need to be able to do that.
    val foundCoreAlias: Ident =
      params.flatMap { param -> CollectXR.byType<XR.U.CoreAction>(param) }.firstOrNull()?.coreAlias() ?: Ident.Unused

    override fun coreAlias(): Ident = foundCoreAlias

  }

  // ************************************************************************************************
  // ****************************************** Function ********************************************
  // ************************************************************************************************

  // I.e. a lambda function. It can be used as an expression in some cases but not a query (although it's body maybe a query or expression)
  @Serializable
  @Mat
  data class FunctionN(@Slot val params: List<Ident>, @Slot val body: XR.U.QueryOrExpression, override val loc: Location = Location.Synth) : Expression, PC<FunctionN> {
    @Transient
    override val productComponents = productOf(this, params, body)
    override val type get() = body.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FunctionN && other.id() == cid
  }

  // ************************************************************************************************
  // ****************************************** Expression ******************************************
  // ************************************************************************************************

  /**
   * A function applied to some arguments. The result can be a Expression or Query hence it extends both.
   * Note that the `function` slot can be an expression but in practice its almost always a function
   * the only other thing it can be is FunctionApply for the case where we have a function-apply, applied to a function-apply.
   * See the "nested function apply" case in BetaReductionSpec for more details.
   */
  @Serializable
  @Mat
  data class FunctionApply(@Slot val function: QueryOrExpression, @Slot val args: List<XR.U.QueryOrExpression>, override val loc: Location = Location.Synth) : Query, Expression, PC<FunctionApply> {
    @Transient
    override val productComponents = productOf(this, function, args)
    override val type get() = function.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is FunctionApply && other.id() == cid
  }

  @Serializable
  @Mat
  data class BinaryOp(@Slot val a: XR.Expression, @MSlot val op: BinaryOperator, @Slot val b: XR.Expression, override val loc: Location = Location.Synth) : Expression, PC<BinaryOp> {
    // TODO mark this @Transient in the PC class?
    @Transient
    override val productComponents = productOf(this, a, op, b)
    override val type: XRType by lazy {
      when (op) {
        is YieldsBool -> XRType.BooleanExpression
        else -> XRType.Value
      }
    }

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is BinaryOp && other.id() == cid
  }

  @Serializable
  @Mat
  data class UnaryOp(@CS val op: UnaryOperator, @Slot val expr: XR.Expression, override val loc: Location = Location.Synth) : Expression, PC<UnaryOp> {
    @Transient
    override val productComponents = productOf(this, expr)
    override val type: XRType by lazy {
      when (op) {
        is YieldsBool -> XRType.BooleanExpression
        else -> XRType.Value
      }
    }

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is UnaryOp && other.id() == cid
  }

  @Serializable
  sealed interface CallType {
    val isPure: Boolean

    @Serializable
    data object PureFunction : CallType {
      override val isPure = true
    }

    @Serializable
    data object ImpureFunction : CallType {
      override val isPure = false
    }

    @Serializable
    data object Aggregator : CallType {
      override val isPure = false
    }

    @Serializable
    data object QueryAggregator : CallType {
      override val isPure = false
    }

    companion object {
      val values: List<XR.CallType> = listOf(PureFunction, ImpureFunction, Aggregator, QueryAggregator)
      fun fromClassString(str: String): XR.CallType =
        values.first { it::class.simpleName == str }
    }
  }

  /**
   * Note that now instead of XR.Aggregation we are using MethodCall and GlobalCall to represent
   * both Query<T>.max (e.g. `people.map {p->p.age}.max` and `people.map {p->max(p.age)}`. Since in some
   * situations we might want to know the intended usage of the aggregation operator there is a
   * `callType` parameter that has the values QueryAggregator (to represent the former) and Aggregator
   * to represent the latter.
   *
   * In addition, it has the values PureFunction and ImpureFunction to represent operators that are known
   * to SQL like Query.isEmpty/Query.isNotEmpty which the SqlIdiom knows to interpret as `IS EMPTY (query)`
   * and `IS NOT EMPTY (query)` respectively. The difference between PureFunction and ImpureFunction is
   * that for the former, we know that various flattening in ApplyMap can be done but for the latter we it cannot.
   * This is a collorary to Impure-Infixes.
   */
  @Serializable // TODO originalResultType
  @Mat
  data class MethodCall(
    @Slot val head: XR.U.QueryOrExpression,
    @MSlot val name: String,
    @Slot val args: List<XR.U.QueryOrExpression>,
    val callType: CallType,
    val originalHostType: XR.ClassId,
    val isKotlinSynthetic: Boolean, // e.g. inserted .toDouble in places like (x:Int).toDouble() >= (x:Double)
    override val type: XRType,
    override val loc: Location = Location.Synth
  ) : Query, Expression, U.Call, PC<MethodCall> {
    @Transient
    override val productComponents = productOf(this, head, name, args)

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is MethodCall && other.id() == cid

    override fun isPure() = callType.isPure
    override fun isAggregation() = callType == CallType.Aggregator
  }

  // TODO originalResultType
  @Serializable
  @Mat
  data class GlobalCall(@Slot val name: XR.FqName, @Slot val args: List<XR.U.QueryOrExpression>, val callType: CallType, val isKotlinSynthetic: Boolean, override val type: XRType, override val loc: Location = Location.Synth) : Query, Expression,
    U.Call, PC<GlobalCall> {
    @Transient
    override val productComponents = productOf(this, name, args)

    companion object {
      // using this when translating from Query-level aggs to Expression-level aggs in the SqlQuery
      fun Agg(name: String, vararg args: XR.U.QueryOrExpression) = GlobalCall(FqName(name), args.toList(), CallType.Aggregator, false, XRType.Value)
    }

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is GlobalCall && other.id() == cid

    override fun isPure() = callType.isPure
    override fun isAggregation() = callType == CallType.Aggregator
  }


  @Serializable
  @Mat
  data class Window(@Slot val partitionBy: List<XR.Expression>, @MSlot val orderBy: List<XR.OrderField>, @Slot val over: XR.Expression, override val loc: Location = Location.Synth) : XR.Expression, PC<Window> {
    @Transient
    override val productComponents = productOf(this, partitionBy, orderBy, over)

    override val type: XRType = over.type

    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Window && other.id() == cid
  }


//  /**
//   * The element that unifies query and expression. For example
//   * `people.map(_.age).avg` should be represented as:
//   * `people.map(_.age).map(i -> sum(i)).value whose tree is:
//   * QueryToExpr(Map(Map(people, x, x.age), i, sum(i)))
//   */
//  data class QueryToExpr(val head: Query, override val loc: Location = Location.Synth): XR.Expression {
//    override val type: XRType = head.type
//  }


  // **********************************************************************************************
  // ****************************************** Terminal ******************************************
  // **********************************************************************************************

  // TODO should have some information defining a symbol as "Anonymous" e.g. from the .join clause so use knows that in warnings

  // A identifier. Can either represent an expression or query
  @Serializable
  @Mat
  data class Ident(@Slot val name: String, override val type: XRType, override val loc: Location = Location.Synth, val visibility: Visibility = Visibility.Visible) : XR, XR.Expression, XR.Query, U.Terminal,
    PC<XR.Ident> {

    @Transient
    override val productComponents = productOf(this, name)

    companion object {
      val Unused = XR.Ident("unused", XRType.Unknown, XR.Location.Synth)
      private val dol: Char = '$'
      val HiddenRefName = "${dol}this${dol}hidden"
      val HiddenRef = XR.Ident(HiddenRefName, XRType.Unknown, XR.Location.Synth)

      val HiddenOnConflictRefName = "${dol}this${dol}onconflict"

      // Can't use context recievers in phases since query-compiler needs to be
      // implemented in all platforms, not only java
      //context(Ident) fun fromThis(name: String) = copy(name = name)
      fun from(ident: Ident): Copy = Copy(ident)
      data class Copy(val host: Ident) {
        operator fun invoke(name: String) = host.copy(name = name)
      }

      //@Transient val Unused = XR.Ident("unused", XRType.Unknown, XR.Location.Synth)
    }

    private val dol: Char = '$'
    fun isThisRef() = name.startsWith("${dol}this")

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is Ident && other.id() == cid
  }

  // ConstType<Const.Boolean>: PC<Const.Boolean>

  @Serializable
  @Mat
  data class TagForSqlExpression(@Slot val id: BID, override val type: XRType, override val loc: Location = Location.Synth) : XR.Expression, PC<XR.TagForSqlExpression> {
    @Transient
    override val productComponents = productOf(this, id)
    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is TagForSqlExpression && other.id() == cid
  }

  @Serializable
  @Mat
  data class TagForSqlAction(@Slot val id: BID, override val type: XRType, override val loc: Location = Location.Synth) : XR.Action, PC<XR.TagForSqlAction> {
    @Transient
    override val productComponents = productOf(this, id)
    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is TagForSqlAction && other.id() == cid
    override fun coreAlias(): Ident? = null
  }

  @Serializable
  @Mat
  data class TagForSqlQuery(@Slot val id: BID, override val type: XRType, override val loc: Location = Location.Synth) : XR.Query, PC<XR.TagForSqlQuery> {
    @Transient
    override val productComponents = productOf(this, id)
    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is TagForSqlQuery && other.id() == cid
  }

  @Serializable
  sealed interface ParamType {
    @Serializable
    data object Single : ParamType
    @Serializable
    data object Multi : ParamType
    @Serializable
    data object Batch : ParamType
    // TODO Custom
    // TODO BatchMulti
  }

  @Serializable
  @Mat
  data class TagForParam(@Slot val id: BID, val paramType: ParamType, override val type: XRType, override val loc: Location = Location.Synth) : XR.Expression, PC<XR.TagForParam> {
    @Transient
    override val productComponents = productOf(this, id)

    companion object {
      fun valueTag(id: String) = TagForParam(BID(id), ParamType.Single, XRType.Value)
      fun valueTagMulti(id: String) = TagForParam(BID(id), ParamType.Multi, XRType.Value)
    }

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode(): Int = cid.hashCode()
    override fun equals(other: Any?): Boolean = other is TagForParam && other.id() == cid
  }

  @Serializable
  sealed class ConstType<T> : PC<ConstType<T>>, Const, XR.Expression {
    abstract val value: T
    override val productComponents by lazy { productOf(this, value) }
    @Transient
    override val type: XRType = XRType.Value
    override fun toString() = show()

    companion object {
      data class ConstTypeId<T>(val value: T)
    }

    val cid by lazy { ConstTypeId(value) }
    override fun hashCode() = cid.hashCode()
  }

  @Serializable
  sealed interface Const : Expression {
    companion object {
      val True = Const.Boolean(true)
      val False = Const.Boolean(false)
      operator fun invoke(value: kotlin.Boolean, loc: Location = Location.Synth) = Boolean(value, loc)
      operator fun invoke(value: kotlin.Char, loc: Location = Location.Synth) = Char(value, loc)
      operator fun invoke(value: kotlin.Short, loc: Location = Location.Synth) = Short(value, loc)
      operator fun invoke(value: kotlin.Int, loc: Location = Location.Synth) = Int(value, loc)
      operator fun invoke(value: kotlin.Long, loc: Location = Location.Synth) = Long(value, loc)
      operator fun invoke(value: kotlin.String, loc: Location = Location.Synth) = String(value, loc)
      operator fun invoke(value: kotlin.Float, loc: Location = Location.Synth) = Float(value, loc)
      operator fun invoke(value: kotlin.Double, loc: Location = Location.Synth) = Double(value, loc)
    }

    @Serializable
    data class Boolean(override val value: kotlin.Boolean, override val loc: Location = Location.Synth) : ConstType<kotlin.Boolean>(), Const {
      override fun equals(other: Any?) = other is Const.Boolean && other.value == value
      override val type: XRType.BooleanValue = XRType.BooleanValue
    }

    @Serializable
    data class Char(override val value: kotlin.Char, override val loc: Location = Location.Synth) : ConstType<kotlin.Char>(), Const {
      override fun equals(other: Any?) = other is Const.Char && other.value == value
    }

    @Serializable
    data class Byte(override val value: kotlin.Int, override val loc: Location = Location.Synth) : ConstType<kotlin.Int>(), Const {
      override fun equals(other: Any?) = other is Const.Byte && other.value == value
    }

    @Serializable
    data class Short(override val value: kotlin.Short, override val loc: Location = Location.Synth) : ConstType<kotlin.Short>(), Const {
      override fun equals(other: Any?) = other is Const.Short && other.value == value
    }

    @Serializable
    data class Int(override val value: kotlin.Int, override val loc: Location = Location.Synth) : ConstType<kotlin.Int>(), Const {
      override fun equals(other: Any?) = other is Const.Int && other.value == value
    }

    @Serializable
    data class Long(override val value: kotlin.Long, override val loc: Location = Location.Synth) : ConstType<kotlin.Long>(), Const {
      override fun equals(other: Any?) = other is Const.Long && other.value == value
    }

    @Serializable
    data class String(override val value: kotlin.String, override val loc: Location = Location.Synth) : ConstType<kotlin.String>(), Const {
      override fun equals(other: Any?) = other is Const.String && other.value == value
    }

    @Serializable
    data class Float(override val value: kotlin.Float, override val loc: Location = Location.Synth) : ConstType<kotlin.Float>(), Const {
      override fun equals(other: Any?) = other is Const.Float && other.value == value
    }

    @Serializable
    data class Double(override val value: kotlin.Double, override val loc: Location = Location.Synth) : ConstType<kotlin.Double>(), Const {
      override fun equals(other: Any?) = other is Const.Double && other.value == value
    }

    @Serializable
    data class Null(override val loc: Location = Location.Synth) : Const {
      override fun toString(): kotlin.String = "Null"

      object Id

      override fun hashCode() = Id.hashCode()
      override fun equals(other: Any?) = other is Null
      @Transient
      override val type = XRType.Value
    }
  }

  @Serializable
  @Mat
  data class Product(val name: String, @Slot val fields: List<Pair<String, XR.Expression>>, override val loc: Location = Location.Synth) : Expression, PC<Product> {
    @Transient
    override val productComponents = productOf(this, fields)
    override val type by lazy { XRType.Product(name, fields.map { it.first to it.second.type }) }

    companion object {
      // helper function for creation during testing
      operator fun invoke(name: String, vararg fields: Pair<String, Expression>) = Product(name, fields.toList(), Location.Synth)

      // helper function for creation during testing
      fun Tuple(first: XR.Expression, second: XR.Expression, loc: Location = Location.Synth) =
        XR.Product("Tuple", "first" to first, "second" to second).copy(loc = loc)

      // helper function for creation during testing
      fun Triple(first: XR.Expression, second: XR.Expression, third: XR.Expression, loc: Location = Location.Synth) =
        XR.Product("Tuple", "first" to first, "second" to second, "third" to third).copy(loc = loc)

      fun TupleSmartN(vararg values: XR.Expression, loc: Location = Location.Synth) =
        TupleSmartN(values.toList(), loc)

      fun TupleSmartN(values: List<XR.Expression>, loc: Location = Location.Synth) =
        if (values.size >= 20)
          TupleNumeric(values.toList(), loc)
        else
          TupleAlphabetic(values, loc = loc)


      fun TupleAlphabetic(values: List<XR.Expression>, loc: Location = Location.Synth) =
        Product("TupleA${values.size}", values.withIndex().map { (idx, v) -> NumbersToWords(idx + 1) to v }, loc)

      // WARNING: Use these only when you don't care about the property-values because kotlin doesn't
      // actually have _X tuples.
      fun TupleNumeric(vararg values: XR.Expression, loc: Location = Location.Synth) =
        TupleNumeric(values.toList(), loc)

      fun TupleNumeric(values: List<XR.Expression>, loc: Location = Location.Synth) =
        Product("Tuple${values.size}", values.withIndex().map { (idx, v) -> "_${idx + 1}" to v }, loc)

      // Used by the SqlQuery clauses to wrap identifiers into a row-class
      fun fromProductIdent(id: XR.Ident): XR.Product {
        val identName = id.name
        val type = id.type
        return when (type) {
          // tpe: XRType.Prod(a->V,b->V), id: Id("foo",tpe) ->
          //   XR.Prod(id, a->Prop(id,"a"), b->Prop(id,"b"))
          is XRType.Product ->
            Product(identName, type.fields.map { (fieldName, _) -> fieldName to XR.Property(id, fieldName, Visibility.Visible, Location.Synth) }, Location.Synth)
          else ->
            // Not sure if this case is possible
            Product("<Generated>", listOf(identName to id), Location.Synth)
        }
      }
    }

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Product && other.id() == cid
  }

  @Serializable
  sealed interface Visibility {
    @Serializable
    object Hidden : Visibility {
      override fun toString() = "Hidden"
    }

    @Serializable
    object Visible : Visibility {
      override fun toString() = "Visible"
    }
  }

  // Every single custom extension to the XR.Query needs to provide for the capability to either
  // A. Convert to a regular XR before the query compilation transformations start (this will be invoked in the NormalizeCustomQueries phase
  // B. Provide a tokenizer function which will allow it to be propagated throughout the phases all the way to the tokenization phase where this will be invoked
  // NOTE that either way, the custom extnesions need to be registered need to be serializeable by Kotlin and registered in the
  // EncodingXR.module
  // (Note extending ShowTree also ensures that the implementor must implement a way to show the CustomQuery tree via the printer)
  // Althought theoretically it should be possible to just call handleStateless/StaefulTransformer on the CustomQuery
  // and pipe it down the transformations that way, in situations involving beta reduction it doesn't work because
  // beta-reduction needs to take into account shadowing of variables that occors in the nested clauses e.g. when reducing
  // FlatMap(foo, id, bar(... id ...)) and we have a Map(id->id') in our map when we it that FlatMap we need to remove the id->id' mapping
  // in the case of custom queries however, this is harder to do because their encoding may not be such that finding out potential shadowing
  // is simple. For example if doing a beta-reduction of SelectClause, we would need to go through Variable-by-Variable line and remove
  // each successive definition from the beta-reduction map. For this reason, the Monadic encoding is clearly easier for this kind of transformation.
  // This is why for now, the preferable thing is to convert the CustomQuery to a regular XR.Query before the beta-reduction phase implementing CustomQuery.Convertable.
  sealed interface CustomQuery : ShowTree {
    val type: XRType
    val loc: XR.Location

    fun handleStatelessTransform(transformer: StatelessTransformer): CustomQuery
    fun <S> handleStatefulTransformer(transformer: StatefulTransformer<S>): Pair<CustomQuery, StatefulTransformer<S>>

    interface Convertable : CustomQuery {
      override fun handleStatelessTransform(transformer: StatelessTransformer): Convertable
      override fun <S> handleStatefulTransformer(transformer: StatefulTransformer<S>): Pair<Convertable, StatefulTransformer<S>>
      fun toQueryXR(): XR.Query
    }

    interface Tokenizeable : CustomQuery {
      override fun handleStatelessTransform(transformer: StatelessTransformer): Tokenizeable
      override fun <S> handleStatefulTransformer(transformer: StatefulTransformer<S>): Pair<Tokenizeable, StatefulTransformer<S>>
      fun tokenize(): Token
    }
  }

  @Serializable
  @Mat
  data class CustomQueryRef(@Slot val customQuery: XR.CustomQuery) : XR.Query, PC<CustomQueryRef> {
    @Transient
    override val productComponents = productOf(this, customQuery)

    companion object {}

    override fun toString() = show()
    override val type get() = customQuery.type
    override val loc: Location = customQuery.loc
  }

  // NOTE: No renameable because in ExoQuery properties will be renamed when created on the parent object i.e.
  // (before any phases happen) from the field annotation on the entity object.
  @Serializable
  @Mat
  data class Property(@Slot val of: XR.Expression, @Slot val name: String, val visibility: Visibility = Visibility.Visible, override val loc: Location = Location.Synth) : XR.Expression, PC<Property> {
    @Transient
    override val productComponents = productOf(this, of, name)
    override val type: XRType by lazy {
      when (val tpe = of.type) {
        is XRType.Product -> tpe.getField(name) ?: XRType.Unknown
        else -> XRType.Unknown
      }
    }

    fun core(): XR.Expression =
      if (of is Property) of.core() else of

    companion object {
      fun fromCoreAndPaths(core: XR.Expression, paths: List<String>, loc: Location = Location.Synth): XR.Expression =
        paths.fold(core) { acc, path -> Property(acc, path, Visibility.Visible, loc) }
    }

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Property && other.id() == cid
  }

  @Serializable
  @Mat
  data class Block(@Slot val stmts: List<Variable>, @Slot val output: XR.Expression, override val loc: Location = Location.Synth) : XR.Expression, PC<Block> {
    @Transient
    override val productComponents = productOf(this, stmts, output)
    override val type: XRType by lazy { output.type }

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Block && other.id() == cid
  }

  @Serializable
  @Mat
  data class When(@Slot val branches: List<Branch>, @Slot val orElse: XR.Expression, override val loc: Location = Location.Synth) : Expression, PC<When> {
    @Transient
    override val productComponents = productOf(this, branches, orElse)
    override val type: XRType by lazy { branches.lastOrNull()?.type ?: XRType.Unknown }

    companion object {
      fun makeIf(cond: XR.Expression, then: XR.Expression, orElse: XR.Expression, loc: Location = Location.Synth) =
        When(listOf(Branch(cond, then, loc)), orElse, loc)
    }

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is When && other.id() == cid
  }

  @Serializable
  @Mat
  data class Branch(@Slot val cond: XR.Expression, @Slot val then: XR.Expression, override val loc: Location = Location.Synth) : XR, PC<Branch> {
    @Transient
    override val productComponents = productOf(this, cond, then)
    override val type: XRType by lazy { then.type }

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Branch && other.id() == cid
  }

  @Serializable
  @Mat
  data class Variable(@Slot val name: XR.Ident, @Slot val rhs: XR.Expression, override val loc: Location = Location.Synth) : XR, PC<Variable> {
    @Transient
    override val productComponents = productOf(this, name, rhs)
    override val type: XRType by lazy { rhs.type }

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Variable && other.id() == cid
  }

  // ***********************************************************************************************************
  // ********************************************** Action *****************************************************
  // ***********************************************************************************************************

  @Serializable
  sealed interface Action : XR {
    fun coreAlias(): XR.Ident?
  }

  @Serializable
  @Mat
  data class Update(@Slot val query: XR.Entity, @CS override val alias: XR.Ident, @Slot val assignments: List<Assignment>, @CS val exclusions: List<Property>, override val loc: Location = Location.Synth) : Action,
    U.CoreAction, PC<Update> {
    @Transient
    override val productComponents = productOf(this, query, assignments)
    override val type: XRType get() = query.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Update && other.id() == cid
    override fun coreAlias(): XR.Ident = alias
  }

// Scala:
//sealed trait Action extends Ast
//
//// Note, technically return type of Actions for most Actions is a Int value but Quat here is used for Returning Quat types
//final case class Update(query: Ast, assignments: List[Assignment]) extends Action {
//  override def quat: Quat = query.quat; override def bestQuat: Quat = query.bestQuat
//}

  // TODO add 'exlucsions: List<Property>' for excluding columns (the extra action.exclude(col1, col2) should do a .copy on the inner parsed action and add the data as opposed to creating yet another clause for it)
  // Note that XR.Query will always be a XR.Entity here
  @Serializable
  @Mat
  data class Insert(@Slot val query: XR.Entity, @CS override val alias: XR.Ident, @Slot val assignments: List<Assignment>, @CS val exclusions: List<Property>, override val loc: Location = Location.Synth) : Action,
    U.CoreAction, PC<Insert> {
    @Transient
    override val productComponents = productOf(this, query, assignments)
    override val type: XRType get() = query.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Insert && other.id() == cid
    override fun coreAlias(): XR.Ident = alias
  }


//final case class Insert(query: Ast, assignments: List[Assignment]) extends Action {
//  override def quat: Quat = query.quat; override def bestQuat: Quat = query.bestQuat
//}

  @Serializable
  @Mat
  data class FilteredAction(@Slot val action: XR.U.CoreAction, @MSlot val alias: Ident, @Slot val filter: XR.Expression, override val loc: Location = Location.Synth) : XR.Action, PC<FilteredAction> {
    @Transient
    override val productComponents = productOf(this, action, alias, filter)
    override val type: XRType = action.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is FilteredAction && other.id() == cid
    override fun coreAlias(): XR.Ident = action.coreAlias()
  }

  @Serializable
  @Mat
  data class Delete(@Slot val query: XR.Entity, @CS override val alias: XR.Ident, override val loc: Location = Location.Synth) : Action, U.CoreAction, PC<Delete> {
    @Transient
    override val productComponents = productOf(this, query)
    override val type: XRType get() = query.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Delete && other.id() == cid
    override fun coreAlias(): XR.Ident = alias
  }


//sealed trait ReturningAction extends Action {
//  def action: Ast
//  def alias: Ident
//  def property: Ast
//}

  /**
   * For Postgres and SqlServer `property` can actually be an expression
   */
  @Serializable
  @Mat
  data class Returning(@Slot val action: XR.Action, @Slot val kind: Kind, override val loc: Location = Location.Synth) : Action, PC<Returning> {
    @Transient
    override val productComponents = productOf(this, action, kind)
    override val type: XRType get() = kind.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Returning && other.id() == cid
    override fun coreAlias(): XR.Ident? = action.coreAlias()


    @Serializable
    sealed interface Kind {
      val type: XRType

      // the .returning { col -> stuff } clause
      @Serializable
      data class Expression(val alias: XR.Ident, val expr: XR.Expression) : Kind {
        override val type get() = expr.type
      }

      // Specifically when APIs that IMPLICITLY return columns are used e.g. PreparedStatement.generatedKeys
      @Serializable
      data class Keys(val alias: XR.Ident, val keys: List<XR.Property>) : Kind {
        override val type by lazy { XR.Product.TupleSmartN(keys).type }
      }
    }
  }


//final case class Foreach(query: Ast, alias: Ident, body: Ast) extends Action {
//  override def quat: Quat = body.quat; override def bestQuat: Quat = body.bestQuat
//}

// TODO I don't think we need a batch action. We can just do SqlBatchInsert/Update/Delete and store the inner action and batchIdentifier directly (as well as the batch-parameter)
//  @Serializable
//  @Mat
//  data class Foreach(@Slot val alias: XR.Ident, @Slot val body: XR.Action, override val loc: Location = Location.Synth): Action, PC<Foreach> {
//    @Transient override val productComponents = productOf(this, alias, body)
//    override val type: XRType get() = body.type
//    companion object {}
//    override fun toString() = show()
//    @Transient private val cid = id()
//    override fun hashCode() = cid.hashCode()
//    override fun equals(other: Any?) = other is Foreach && other.id() == cid
//  }


//  final case class Assignment(alias: Ident, property: Ast, value: Ast) extends Ast {
//    override def quat: Quat = Quat.Value; override def bestQuat: Quat = quat
//  }

  // The 'core' of every property should be <this> pointer coming from the insert<Person> { this:Person ... }/ update<Person> { this:Person  ... } clause
  @Serializable
  @Mat
  data class Assignment(@Slot val property: XR.Property, @Slot val value: XR.Expression, override val loc: Location = Location.Synth) : XR, PC<Assignment> {
    @Transient
    override val productComponents = productOf(this, property, value)
    override val type: XRType get() = value.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Assignment && other.id() == cid
  }

//  final case class AssignmentDual(alias1: Ident, alias2: Ident, property: Ast, value: Ast) extends Ast {
//    override def quat: Quat = Quat.Value; override def bestQuat: Quat = quat
//  }

// Scala
//final case class OnConflict(
//  insert: Ast,
//  target: OnConflict.Target,
//  action: OnConflict.Action
//) extends Action { override def quat: Quat = insert.quat; override def bestQuat: Quat = insert.bestQuat }
//

  @Serializable
  @Mat
  data class OnConflict(@Slot val insert: XR.Insert, @CS val target: XR.OnConflict.Target, @CS val resolution: XR.OnConflict.Resolution, override val loc: Location = Location.Synth) : XR.Action, PC<OnConflict> {
    @Transient
    override val productComponents = productOf(this, insert)
    override val type: XRType get() = insert.type
    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is OnConflict && other.id() == cid

    override fun coreAlias(): XR.Ident = insert.alias

    // I don't think these to extend the XR and if we need to transform them we can transform based on the OnConflict
    // if they do need to be parts of XR the should have a special sub-IR clause so that XR doesn't have too many unrelated things at the top level.

    // These will be embedded directly in the Assignment/AssignmentDual value clause
    // e.g. `insert<Person> { set(...).onConflict(name) { excluded -> set(something to something + excluded.something) } }`
    // In this case it will be something like OnConflict(..., OnConflict.Update(excludedId = Id(excluded), assignments = [Assignment(Id(something), Id(something)])
    @Serializable
    sealed interface Target {
      @Serializable
      object NoTarget : Target
      @Serializable
      data class Properties(val props: List<XR.Property>) : Target
    }


    @Serializable
    sealed interface Resolution {
      @Serializable
      object Ignore : Resolution
      @Serializable
      data class Update(val excludedId: Ident, val existingParamIdent: Ident, val assignments: List<XR.Assignment>) : Resolution
    }
  }

  // Similar to Quill's Foreach, the Batching parameter defines a batch-alias that can be used in actions defined inside
  @Serializable
  @Mat
  data class Batching(@Slot val alias: XR.Ident, @Slot val action: XR.Action, override val loc: Location = Location.Synth) : XR, PC<Batching> {
    @Transient
    override val productComponents = productOf(this, alias, action)
    override val type: XRType get() = action.type

    companion object {}

    override fun toString() = show()
    @Transient
    private val cid = id()
    override fun hashCode() = cid.hashCode()
    override fun equals(other: Any?) = other is Batching && other.id() == cid
  }
}

fun XR.isBottomTypedTerminal() =
  this is XR.U.Terminal && (this.type is XRType.Null || this.type is XRType.Generic || this.type is XRType.Unknown)

fun XR.isTerminal() =
  this is XR.U.Terminal

fun XR.U.Terminal.withType(type: XRType): XR.Expression =
  when (this) {
    is XR.Ident -> XR.Ident(name, type, loc)
  }

fun XR.Action.isCoreAction() =
  this is XR.U.CoreAction
