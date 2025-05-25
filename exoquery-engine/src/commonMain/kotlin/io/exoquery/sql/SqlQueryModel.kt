@file:Suppress("NAME_SHADOWING", "NAME_SHADOWING")

package io.exoquery.sql

import io.exoquery.sql.PostgresDialect
import io.exoquery.printing.PrintXR
import io.exoquery.sql.SqlQueryHelper.flattenDualHeadsIfPossible
import io.exoquery.util.Globals
import io.exoquery.util.TraceConfig
import io.exoquery.util.TraceType
import io.exoquery.util.Tracer
import io.exoquery.xr.*
import io.exoquery.xr.XR.FlatMap
import io.exoquery.xr.XR.Map
import io.exoquery.xr.XR.U.FlatUnit
import io.exoquery.xr.XR.FlatGroupBy
import io.exoquery.xr.XR.FlatSortBy
import io.exoquery.xr.XR.FlatFilter
import io.exoquery.xr.XR.FlatJoin
import io.exoquery.xr.XR.Ordering.PropertyOrdering
import io.exoquery.xrError
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import io.decomat.productComponentsOf as productOf
import io.decomat.HasProductClass as PC

@Serializable
sealed interface FromContext {
  @Transient
  val type: XRType

  fun transformXR(f: StatelessTransformer): FromContext =
    when (this) {
      is TableContext -> this
      is QueryContext -> QueryContext(query.transformXR(f), alias)
      is ExpressionContext -> ExpressionContext(f(infix), alias) //this.transformParamXRs { xr -> (xr as? XR.Query)?.let { f(it) } ?: xr }
      is FlatJoinContext -> FlatJoinContext(joinType, from.transformXR(f), f(on))
    }

// Scala:
//def mapAst(f: Ast => Ast): FromContext = this match {
//  case c: TableContext            => c
//    case QueryContext(query, alias) => QueryContext(query.mapAst(f), alias)
//  case c: InfixContext            => c.mapAsts(f)
//  case JoinContext(t, a, b, on)   => JoinContext(t, a.mapAst(f), b.mapAst(f), f(on))
//  case FlatJoinContext(t, a, on)  => FlatJoinContext(t, a.mapAst(f), f(on))
//}
}

@Serializable
final data class TableContext(val entity: XR.Entity, val alias: String) : FromContext {
  override val type: XRType = entity.type

  // TODO actually want the alias to be the identifier on which it is based, need to change that in SqlQuery
  fun aliasIdent() = XR.Ident(alias, entity.type, XR.Location.Synth)
}

@Serializable
final data class QueryContext(val query: SqlQueryModel, val alias: String) : FromContext {
  override val type: XRType = query.type
  fun aliasIdent() = XR.Ident(alias, query.type, XR.Location.Synth)
}

// A context for arbitrary XR expressions that can become queries
@Serializable
final data class ExpressionContext(val infix: XR.Expression, val alias: String) : FromContext {
  override val type: XRType = infix.type
  fun aliasIdent() = XR.Ident(alias, infix.type, XR.Location.Synth)
}

@Serializable
final data class FlatJoinContext(val joinType: XR.JoinType, val from: FromContext, val on: XR.Expression) : FromContext {
  override val type: XRType = from.type
}

@Serializable
sealed interface SqlQueryModel {
  val type: XRType

  fun transformType(f: (XRType) -> XRType): SqlQueryModel =
    when (this) {
      is FlattenSqlQuery -> copy(type = f(type))
      is SetOperationSqlQuery -> copy(type = f(type))
      is UnaryOperationSqlQuery -> copy(type = f(type))
      is TopLevelFree -> copy(type = f(type))
    }

  fun transformXR(f: StatelessTransformer): SqlQueryModel =
    when (this) {
      is FlattenSqlQuery -> this.transformXR(f) // call the transformXR on FlattenSqlQuery directly
      is SetOperationSqlQuery -> SetOperationSqlQuery(a.transformXR(f), op, b.transformXR(f), type)
      is UnaryOperationSqlQuery -> UnaryOperationSqlQuery(op, query.transformXR(f), type)
      is TopLevelFree -> TopLevelFree(f.invoke(value) as XR.Free, type)
    }

  fun show(pretty: Boolean = false): String {
    val str =
      with(PostgresDialect(Globals.traceConfig())) {
        this@SqlQueryModel.token.toString()
      }
    // TODO integrate formatter via `expect`
    //return if (pretty) SqlFormatter.format(str) else str
    return str
  }


  fun showRaw(color: Boolean = true): String =
    PrintXR(SqlQueryModel.serializer())(this).toString()
}

@Serializable
sealed interface SetOperation
@Serializable
object UnionOperation : SetOperation
@Serializable
object UnionAllOperation : SetOperation

@Serializable
sealed interface DistinctKind {
  val isDistinct: Boolean

  @Serializable
  object Distinct : DistinctKind {
    override val isDistinct: Boolean = true
  }

  @Serializable
  data class DistinctOn(val props: List<XR.Expression>) : DistinctKind {
    override val isDistinct: Boolean = true
  }

  @Serializable
  object None : DistinctKind {
    override val isDistinct: Boolean = false
  }
}

@Serializable
final data class SetOperationSqlQuery(
  val a: SqlQueryModel,
  val op: SetOperation,
  val b: SqlQueryModel,
  override val type: XRType
) : SqlQueryModel

@Serializable
final data class UnaryOperationSqlQuery(
  val op: XR.UnaryOp,
  val query: SqlQueryModel,
  override val type: XRType
) : SqlQueryModel

@Serializable
final data class TopLevelFree(
  val value: XR.Free, override val type: XRType
) : SqlQueryModel

/**
 * Something that was fundemental about the Ast/XR that was not realized in Quill was that the fundemental differentiation
 * between SQL-based things is whether it is a Query-typed thing or an Expression-typed thing. Queries are things
 * that can go into From clauses and Join clause. Expressions are things that go into conditions and
 * into select clauses. When the AST becomes transformed to the point of being able to become an SqlQuery, the
 * job of the SqlQueryApply and related sets of functionality is to convert the Ast into the SqlQuery hiearchy.
 * Within this hiearchy, the SelectValue represents `Select x,y,z` clauses. These are things that
 * fundementally should be Expressions and not Queries. Indeed if a case is found where a SelectValue
 * is actually a Query, additional sub-type of SqlQuery shuold be created for it. Instead what should
 * be inside of each SelectValue is a Expression type of some kind whether it be a a Propery(id, name)
 * construct e.g. Property(Id(person), name) or a constant, or perhaps like an expression (a + b).
 * The only exception to this is an Free which can both be a Query and Expression but in the case
 * of being inside a SelectValue, it should always be the latter.
 *
 * NOTE: Since SQL query expansion phases typically join property paths to process subqueries e.g.
 * `SELECT inner.firstid FROM (SELECT p.id AS idfirst FROM Person) inner`
 *
 * which is necessary because fields e.g. `id` could be repeated:
 * `SELECT inner.firstid, inner.firstid FROM (SELECT p.id AS idfirst, a.id AS idsecond FROM Person JOIN Address ...) inner`
 *
 * because all of these paths e.g. `[first,id]`, `[second, id]` ultimately are used as alises, it makes sense
 * for the `alias` parameter to be a list. If this list is empty the field is not considered to have an alias.
 */

@Serializable
final data class SelectValue(val expr: XR.Expression, val alias: List<String> = listOf(), val concat: Boolean = false) : PC<SelectValue> {
  @Transient
  override val productComponents = productOf(this, expr, alias)
  @Transient
  val type: XRType = expr.type

  companion object {}
}

@Serializable
final data class FlattenSqlQuery(
  val from: List<FromContext> = emptyList(),
  val where: XR.Expression? = null,
  val groupBy: XR.Expression? = null,
  val orderBy: List<XR.OrderField> = emptyList(),
  val limit: XR.Expression? = null,
  val offset: XR.Expression? = null,
  val select: List<SelectValue> = emptyList(),
  val distinct: DistinctKind = DistinctKind.None,
  override val type: XRType
) : SqlQueryModel {

  // Overriding so make this return a more-specific type
  override fun transformXR(f: StatelessTransformer): FlattenSqlQuery =
    this.transformClauses(f)

  fun transformClauses(f: StatelessTransformer): FlattenSqlQuery =
    copy(
      from = from.map { it.transformXR(f) },
      where = where?.let { f(it) },
      groupBy = groupBy?.let { f(it) },
      orderBy = orderBy.map { ord -> ord.transform { f(it) } },
      limit = limit?.let { f(it) },
      offset = offset?.let { f(it) },
      select = select.map { it.copy(expr = f(it.expr)) }
    )
}

class SqlQueryApply(val traceConfig: TraceConfig) {
  val trace: Tracer = Tracer(TraceType.SqlQueryConstruct, traceConfig, 1)

  fun of(query: XR.Query): SqlQueryModel =
    invoke(query, true)

  private fun invoke(query: XR.Query, topLevel: Boolean = false): SqlQueryModel =
    with(query) {
      when (this) {
        // If at the top level of the query there's a Free, do NOT wrap it into a FlattenSqlQuery
        // because if you do things like 'SELECT ... FOR UPDATE' will become inoperable (as they need to be on the top level)
        is XR.Free if topLevel ->
          trace("Construct TopLevel from: Free") andReturn {
            TopLevelFree(this, type)
          }
        is XR.ExprToQuery if this.head is XR.Free && topLevel ->
          trace("Construct TopLevel from: ExprToQuery") andReturn {
            TopLevelFree(this.head, type)
          }

        is XR.Union ->
          trace("Construct SqlQuery from: Union") andReturn {
            SetOperationSqlQuery(invoke(a), UnionOperation, invoke(b), query.type)
          }
        is XR.UnionAll ->
          trace("Construct SqlQuery from: UnionAll") andReturn {
            SetOperationSqlQuery(invoke(a), UnionAllOperation, invoke(b), query.type)
          }

        // UnaryOp on query is currently now allowed for Query but perhaps we should have QueryUnaryOp
        //this is XR.UnaryOp && this.expr is XR.Query ->
        //  trace("Construct SqlQuery from: UnaryOp") andReturn {
        //    UnaryOperationSqlQuery(this, invoke(this.expr), query.type)
        //  }

        // e.g. if the XR is `Map(Ent(person), n, n)` then we just need the Ent(person) part
        is Map if id == body ->
          trace("Construct SqlQuery from: Map") andReturn {
            invoke(head)
          }
        // e.g. Take(people.flatMap(p => addresses:Query), 3) -> `select ... from people, addresses... limit 3`
        is XR.Take if head is FlatMap ->
          trace("Construct SqlQuery from: TakeDropFlatten") andReturn {
            flatten(head, head.id.copy(name = "x")).copy(limit = num, type = query.type)
          }
        // e.g. Drop(people.flatMap(p => addresses:Query), 3) -> `select ... from people, addresses... offset 3`
        is XR.Drop if head is FlatMap ->
          trace("Construct SqlQuery from: TakeDropFlatten") andReturn {
            flatten(head, head.id.copy(name = "x")).copy(offset = num, type = query.type)
          }
        is XR.Free ->
          trace("Construct SqlQuery from: Free") andReturn { flatten(this, XR.Ident("x", type, XR.Location.Synth)) }

        else ->
          trace("Construct SqlQuery from: Query").andReturn {
            // TODO need to parse interpolations
            flatten(this, XR.Ident("x", type, XR.Location.Synth))
          }
      }
    }

  sealed interface Layer {
    data class Context(val ctx: FromContext) : Layer
    data class Grouping(val groupBy: XR.Expression) : Layer
    data class Sorting(val criteria: List<XR.OrderField>) : Layer
    data class Filtering(val where: XR.Expression) : Layer
    companion object {
      fun fromFlatUnit(xr: XR.U.FlatUnit): Layer =
        when (xr) {
          is FlatFilter -> Layer.Filtering(xr.by)
          is FlatGroupBy -> Layer.Grouping(xr.by)
          is FlatSortBy -> Layer.Sorting(xr.criteria)
        }
    }
  }


  private fun flattenContexts(query: XR.Query): Pair<List<Layer>, XR.Query> =
    with(query) {
      when {
        // A flat-join query with no maps e.g: `qr1.flatMap(e1 => qr1.join(e2 => e1.i == e2.i))`
        this is XR.FlatMap && body is FlatJoin ->
          trace("Flattening FlatMap with FlatJoin") andReturn {
            val cc: XR.Product = XR.Product.fromProductIdent(body.id)
            flattenContexts(FlatMap.csf(head, id, Map(body, body.id, cc, loc))(this))
          }
        this is XR.FlatMap && body is XR.Free ->
          trace("[INVALID] Flattening Flatmap with Free") andReturn {
            xrError("Free can't be use as a `flatMap` body. $query")
          }
        // Conceptually (the actual DSL is different):
        // people.flatMap(p -> groupBy(expr).flatMap(rest)) is:
        //   FlatMap(people, p, FlatMap(GroupBy(expr), rest)))
        this is XR.FlatMap && (head is FlatUnit) ->
          trace("Flattening Flatmap with FlatUnit") andReturn {
            val (nestedContexts, finalFlatMapBody) = flattenContexts(body)
            listOf(Layer.fromFlatUnit(head)) + nestedContexts to finalFlatMapBody
          }

        this is XR.Map && head is FlatUnit ->
          trace("Flattening Flatmap with FlatGroupBy") andReturn {
            listOf(Layer.fromFlatUnit(head)) to XR.ExprToQuery(body)
          }

        this is XR.FlatMap &&
            head is XR.U.HasHead && head.head is XR.FlatJoin &&
            body is XR.U.HasHead && body.head is XR.FlatJoin ->
          trace("Flattening Flatmap((FlatJoin), (FlatJoin))") andReturn {
            this.flattenDualHeadsIfPossible()?.let { newQuery ->
              flattenContexts(newQuery)
            }
              ?: xrError("Cannot have FlatJoin in both head and body positions of a FlatMap.\n${this.show()}")
          }

        this is XR.FlatMap ->
          trace("Flattening Flatmap with Query") andReturn {
            val source = sourceSpecific(head, id.name) ?: QueryContext(invoke(head), id.name)
            val (nestedContexts, finalFlatMapBody) = flattenContexts(body)
            (listOf(Layer.Context(source)) + nestedContexts to finalFlatMapBody)
          }
        else ->
          trace("Flattening other") andReturn {
            (listOf<Layer>() to query)
          }
      }
    }


  private fun flatten(query: XR.Query, alias: XR.Ident): FlattenSqlQuery =
    trace("Flattening ${query}") andReturn {
      val (sources, finalFlatMapBody) = flattenContexts(query)
      // TODO Check if the 2nd-to-last source is a groupBy otherwise we're doing things
      //      between the groupBy and the final `select` which is illegal
      val contexts = sources.mapNotNull { if (it is Layer.Context) it.ctx else null }
      val (grouping, sorting, filtering) = sources.findComponentsOrNull()

      val queryRaw = flatten(contexts, finalFlatMapBody, alias, nestNextMap = false)
      val query =
        queryRaw
          .let { if (grouping != null) it.copy(groupBy = grouping.groupBy) else it }
          // TODO what if there is already an orderBy?
          .let { if (sorting != null) it.copy(orderBy = FlattenCriteria.flattenOrderFields(sorting.criteria, contexts)) else it }
          // Not sure if its possible to already have a where-clause but if it is combine them
          .let { if (filtering != null) it.copy(where = combineWhereClauses(it.where, filtering.where)) else it }

      query
    }

  private fun flatten(
    sources: List<FromContext>,
    finalFlatMapBody: XR.Query,
    alias: XR.Ident,
    nestNextMap: Boolean
  ): FlattenSqlQuery {

    fun select(alias: String, type: XRType, loc: XR.Location): List<SelectValue> = listOf(SelectValue(XR.Ident(alias, type, loc), listOf()))

    fun base(query: XR.Query, alias: XR.Ident, nestNextMap: Boolean): FlattenSqlQuery =
      trace("Computing Base (nestingMaps=${nestNextMap}) for Query: $query") andReturn {
        fun nest(ctx: FromContext): FlattenSqlQuery = trace("Computing FlattenSqlQuery for: $ctx") andReturn {
          FlattenSqlQuery(from = sources + ctx, select = select(alias.name, query.type, alias.loc), type = query.type)
        }
        with(query) {
          when {
            // A map that contains mapped-to aggregations (i.e. MethodCalls that are typed as ImpureFunction or Aggregation) e.g.
            //   people.map(p=>max(p.name))
            // Could have more complex structures e.g: people.map(p=>SomeCaseClassOrTuple(max(p.name),min(p.age)))
            // so we need to search for the aggregations.
            // Also it could have impure-infixes inside e.g.
            //   people.map(p=>SomeCaseClassOrTuple(p.name, someStatefulSqlFunction(p.age)))
            // therefore we need to check if there are elements like this inside of the map-function and nest it
            this is XR.Map && containsImpurities() ->
              trace("base| Nesting Map(a=>ContainsImpurities(a)) $query") andReturn { nest(source(this, alias.name)) }

            this is XR.Nested -> trace("base| Nesting Nested $query") andReturn { nest(source(this, alias.name)) }

            this is XR.ConcatMap -> trace("base| Nesting ConcatMap $query") andReturn { nest(source(this, alias.name)) }

            this is XR.Filter -> trace("base| Flattening Filter $query") andReturn { flatten(sources, query, alias, nestNextMap) }
            this is XR.Entity -> trace("base| Flattening Entity $query") andReturn { flatten(sources, query, alias, nestNextMap) }

            this is XR.Map && nestNextMap ->
              trace("base| Map + nest $query") andReturn { nest(source(this, alias.name)) }
            this is XR.Map ->
              trace("base| Map $query") andReturn { flatten(sources, query, alias, nestNextMap) }

            sources.isEmpty() ->
              trace("base| Flattening Empty-Sources $query") andReturn { flatten(sources, query, alias, nestNextMap) }

            else -> trace("base| Nesting 'other' $query") andReturn {
              sourceSpecific(query, alias.name)?.let { nest(it) } ?: xrError("Cannot compute base for the query: $query")
            }
          }
        }
      }

    val type = finalFlatMapBody.type

    // ---------------------------------------- HANDLING OF CLAUSES DIRECTLY TRANSLATED INTO SQL ----------------------------------------
    return trace("Flattening (alias = $alias) sources $sources from $finalFlatMapBody") andReturn {
      with(finalFlatMapBody) {
        when (this) {
          is XR.ConcatMap ->
            trace("Flattening| ConcatMap") andReturn {
              FlattenSqlQuery(
                from = sources + QueryContext(invoke(head), alias.name),
                select = selectValues(body).map { it.copy(concat = true) },
                type = type
              )
            }

          is Map -> {
            val b = base(head, id, nestNextMap = false)
            // TODO do we really need another nesting level if select-values are aggregations? Haven't we invoked "containsImpurities" above to already do that? Need to look into it.
            val aggs = b.select.filter { it.expr is XR.MethodCall && it.expr.isAggregation() || it.expr is XR.GlobalCall && it.expr.isAggregation() }
            if (!b.distinct.isDistinct && aggs.isEmpty() && b.select.size == 1 && b.select.first().expr is XR.Ident)
              trace("Flattening| Map(Ident) [Simple]") andReturn {
                b.copy(select = selectValues(body), type = type)
              }
            else
              trace("Flattening| Map(Ident) [Complex]") andReturn {
                FlattenSqlQuery(
                  from = listOf(QueryContext(invoke(head), id.name)),
                  select = selectValues(body),
                  type = type
                )
              }
          }

          /*
          case Aggregation(op, q: Query) =>
            val b = flatten(q, alias)
            b.select match {
              case head :: Nil if !b.distinct.isDistinct =>
                trace"Flattening| Aggregation(Query) [Simple]" andReturn
                  b.copy(select = List(head.copy(ast = Aggregation(op, head.ast))))(quat)
              case other =>
                trace"Flattening| Aggregation(Query) [Complex]" andReturn
                  FlattenSqlQuery(
                    from = QueryContext(build(q), alias) :: Nil,
                    select = List(
                      SelectValue(Aggregation(op, Ident("*", quat)))
                    ) // Quat of a * aggregation is same as for the entire query
                  )(quat)
            }
           */

          // Handle Query-level aggregations e.g. `people.map(p => p.age).max`
          is XR.MethodCall if callType == XR.CallType.QueryAggregator -> {
            if (this.head !is XR.Query) xrError("QueryAggregator must have a query as the head but this condition was not met in: ${this.showRaw()}")
            if (this.args.size != 0) xrError("QueryAggregator must have zero arguments but this condition was not met in: ${this.showRaw()}")
            // Kotlin:
            //val b = flatten(q, alias)
            //b.select match {
            //  case head :: Nil if !b.distinct.isDistinct =>
            //    trace"Flattening| Aggregation(Query) [Simple]" andReturn
            //      b.copy(select = List(head.copy(ast = Aggregation(op, head.ast))))(quat)
            //  case other =>
            //    trace"Flattening| Aggregation(Query) [Complex]" andReturn
            //      FlattenSqlQuery(
            //        from = QueryContext(build(q), alias) :: Nil,
            //        select = List(
            //          SelectValue(Aggregation(op, Ident("*", quat)))
            //        ) // Quat of a * aggregation is same as for the entire query
            //      )(quat)
            val b = flatten(head, alias)
            when {
              b.select.size == 1 && !b.distinct.isDistinct ->
                trace("Flattening| Aggregation(Query) [Simple]") andReturn {
                  b.copy(select = listOf(b.select.first().copy(expr = XR.GlobalCall.Agg(name, b.select.first().expr))))
                }
              else ->
                trace("Flattening| Aggregation(Query) [Complex]") andReturn {
                  FlattenSqlQuery(
                    from = listOf(QueryContext(invoke(head), alias.name)),
                    select = listOf(SelectValue(XR.GlobalCall.Agg(name, XR.Ident("*", type, XR.Location.Synth)))),
                    type = type
                  )
                }
            }

          }

          is XR.Filter -> {
            // If it's a filter, pass on the value of nestNextMap in case there is a future map we need to nest
            val b = base(head, id, nestNextMap)
            // If the filter body uses the filter id, make sure it matches one of the aliases in the fromContexts
            if (b.where == null && (!CollectXR.byType<XR.Ident>(body).map { it.name }
                .contains(id.name) || collectAliases(b.from).contains(id.name)))
              trace("Flattening| Filter(Ident) [Simple]") andReturn {
                b.copy(where = body, type = type)
              }
            else
              trace("Flattening| Filter(Ident) [Complex]") andReturn {
                FlattenSqlQuery(
                  from = listOf(QueryContext(invoke(head), id.name)),
                  where = body,
                  select = select(id.name, type, id.loc),
                  type = type
                )
              }
          }

          is XR.SortBy -> {
            fun allIdentsIn(criteria: List<XR.OrderField>) =
              criteria.flatMap { CollectXR.byType<XR.Ident>(it.field).map { it.name } }

            val b = base(head, id, nestNextMap = false)
            // If the sortBy body uses the filter id, make sure it matches one of the aliases in the fromContexts
            if (b.where == null && (!allIdentsIn(criteria).contains(id.name) || collectAliases(b.from).contains(id.name)))
              trace("Flattening| SortBy(Ident) [Simple]") andReturn {
                b.copy(orderBy = criteria, type = type)
              }
            else
              trace("Flattening| SortBy(Ident) [Complex]") andReturn {
                FlattenSqlQuery(
                  from = listOf(QueryContext(invoke(head), id.name)),
                  orderBy = criteria,
                  select = select(id.name, type, id.loc),
                  type = type
                )
              }
          }

          is XR.Take -> {
            val b = base(head, alias, nestNextMap = false)
            if (b.limit == null)
              trace("Flattening| Take [Simple]") andReturn {
                b.copy(limit = num, type = type)
              }
            else
              trace("Flattening| Take [Complex]") andReturn {
                FlattenSqlQuery(
                  from = listOf(QueryContext(invoke(head), alias.name)),
                  limit = num,
                  select = select(alias.name, type, alias.loc),
                  type = type
                )
              }
          }

          is XR.Drop -> {
            val b = base(head, alias, nestNextMap = false)
            if (b.offset == null && b.limit == null) // not sure why `&& b.limit == null`. Need to look into why it was introduced to Quill.
              trace("Flattening| Drop [Simple]") andReturn {
                b.copy(offset = num, type = type)
              }
            else
              trace("Flattening| Drop [Complex]") andReturn {
                FlattenSqlQuery(
                  from = listOf(QueryContext(invoke(head), alias.name)),
                  offset = num,
                  select = select(alias.name, type, alias.loc),
                  type = type
                )
              }
          }

          // nest(source(this, alias.name)) back here
          is XR.Distinct -> {
            val b = base(head, alias, nestNextMap = false)
            trace("Flattening| Distinct") andReturn {
              b.copy(distinct = DistinctKind.Distinct, type = type)
              //FlattenSqlQuery(
              //  from = listOf(QueryContext(invoke(head), alias.name)),
              //  select = select(alias.name, type, alias.loc),
              //  type = type
              //)
            }
          }

          is XR.DistinctOn -> {
            val distinctList =
              when (by) {
                // Typically when you have something like `people.distinctOn(p -> tupleOf(p.name, p.age))`
                // and the tuple-of is some kind of product-type
                is XR.Product -> by.fields.map { it.second }
                else -> listOf(by)
              }

            when (head) {
              // Ideally we don't need to make an extra sub-query for every single case of
              // distinct-on but it only works when the parent AST is an entity. That's because DistinctOn
              // selects from an id of an outer clause. For example, query[Person].map(p => Name(p.firstName, p.lastName)).distinctOn(_.name)
              // (Let's say Person(firstName, lastName, age), Name(first, last)) will turn into
              // SELECT DISTINCT ON (p.name), p.firstName AS first, p.lastName AS last, p.age FROM Person
              // This doesn't work because `name` in `p.name` doesn't exist yet. Therefore we have to nest this in a subquery:
              // SELECT DISTINCT ON (p.name) FROM (SELECT p.firstName AS first, p.lastName AS last, p.age FROM Person p) AS p
              // The only exception to this is if we are directly selecting from an entity:
              // query[Person].distinctOn(_.firstName) which should be fine: SELECT (x.firstName), x.firstName, x.lastName, a.age FROM Person x
              // since all the fields inside the (...) of the DISTINCT ON must be contained in the entity.
              is XR.Entity -> {
                val b = base(head, id, nestNextMap = false)
                b.copy(distinct = DistinctKind.DistinctOn(distinctList), type = type)
              }

              else ->
                trace("Flattening| DistinctOn") andReturn {
                  FlattenSqlQuery(
                    from = listOf(QueryContext(invoke(head), id.name)),
                    select = select(id.name, type, id.loc),
                    distinct = DistinctKind.DistinctOn(distinctList),
                    type = type
                  )
                }
            }
          }

          is XR.Entity ->
            FlattenSqlQuery(
              from = sources + source(this, alias.name),
              select = select(alias.name, type, alias.loc),
              type = type
            )

          is FlatMap ->
            flatten(this, alias)

          // TODO introduce a CustomQueryContext that will take this. for now just throw an error.
          //is XR.CustomQueryRef if this.customQuery is XR.CustomQuery.Tokenizeable ->
          //  FlattenSqlQuery(
          //    from = sources + source(this, alias.name),
          //    select = select(alias.name, type, alias.loc),
          //    type = type
          //  )
          is XR.CustomQueryRef if this.customQuery is XR.CustomQuery.Tokenizeable ->
            xrError("Encountered a Tokenizeable CustomQueryRef, not supported yet. Need to build a CustomQueryContext): $this")

          is XR.CustomQueryRef if this.customQuery is XR.CustomQuery.Convertable ->
            xrError("Encountered a CustomQueryRef during flattening (All queries should have flattened previously): $this")

          //else ->
          //  xrError("Invalid flattening: ${this.showRaw()}")
          //trace("Flattening| Other") andReturn {
          //  FlattenSqlQuery(
          //    from = sources + source(this, alias),
          //    select = select(alias.name, type, alias.loc),
          //    type = type
          //  )
          //}

          is XR.CustomQueryRef -> xrError("already took care of this case")


          is FlatFilter -> xrError("FlatFilter (and all FlatUnit) functions should have already been handled in the `base` phase: ${this}")
          is FlatGroupBy -> xrError("FlatGroupBy (and all FlatUnit) functions should have already been handled in the `base` phase: ${this}")
          is FlatSortBy -> xrError("FlatSortBy (and all FlatUnit) functions should have already been handled in the `base` phase: ${this}")

          // ===== The following have special handling in source function =====
          // Constructs like Map(FlatFilter(...),id,output) rely on this because we treat `output` as ExprToQuery(output) in the
          // `flatten` function above. Also constructs situations like `capture.select { 1 }`.
          is XR.ExprToQuery -> FlattenSqlQuery(from = sources, select = listOf(SelectValue(head)), type = type)
          is XR.Free -> FlattenSqlQuery(from = sources + source(this, alias.name), select = select(alias.name, type, loc), type = type)
          is XR.Nested -> FlattenSqlQuery(from = sources + source(this, alias.name), select = select(alias.name, type, loc), type = type)
          is FlatJoin -> FlattenSqlQuery(from = sources + source(this, alias.name), select = select(alias.name, type, loc), type = type)

          //is XR.Ident -> FlattenSqlQuery(from = sources + ExpressionContext(this, alias.name), select = select(alias.name, type, loc), type = type)
          is XR.Ident -> xrError("Invalid flattening, should have been beta-reduced already: ${this.showRaw()}")

          is XR.TagForSqlQuery ->
            xrError("Invalid flattening, XR.TagForSqlQuery should have been reduced-out of the query construction already: ${this.showRaw()}")
          is XR.Union -> {
            val setOp = SetOperationSqlQuery(invoke(a), UnionOperation, invoke(b), type)
            FlattenSqlQuery(from = sources + QueryContext(setOp, alias.name), select = select(alias.name, type, loc), type = type)
          }
          is XR.UnionAll -> {
            val setOp = SetOperationSqlQuery(invoke(a), UnionAllOperation, invoke(b), type)
            FlattenSqlQuery(from = sources + QueryContext(setOp, alias.name), select = select(alias.name, type, loc), type = type)
          }

          is XR.FunctionApply ->
            xrError("Invalid flattening, should have been beta-reduced already: ${this.showRaw()}")

          // Need to think more about how these are handled
          is XR.GlobalCall -> FlattenSqlQuery(from = sources + ExpressionContext(this, alias.name), select = select(alias.name, type, loc), type = type)
          is XR.MethodCall -> FlattenSqlQuery(from = sources + ExpressionContext(this, alias.name), select = select(alias.name, type, loc), type = type)
        }
      }
    }
  }

  private fun selectValues(ast: XR.Expression) = listOf(SelectValue(ast))

  private fun source(ast: XR.Entity, alias: String): FromContext = TableContext(ast, alias)

  private fun source(ast: XR.Free, alias: String): FromContext = ExpressionContext(ast, alias)
  private fun source(ast: XR.ExprToQuery, alias: String): FromContext = ExpressionContext(ast.head, alias)
  private fun source(ast: XR.FlatJoin, alias: String): FromContext =
    FlatJoinContext(ast.joinType, sourceSpecific(ast.head, ast.id.name) ?: QueryContext(invoke(ast.head), ast.id.name), ast.on)

  private fun source(ast: XR.Nested, alias: String): FromContext = QueryContext(invoke(ast.head), alias)

  // These calls are safe because `invoke` calls `flatten.return` which has specific handling for XR.Map
  private fun source(ast: XR.Map, alias: String): FromContext = QueryContext(invoke(ast), alias) // safe because `flatten.return` directly handles Map
  private fun source(ast: XR.ConcatMap, alias: String): FromContext = QueryContext(invoke(ast), alias) // safe because `flatten.return` directly handles ConcatMap

  // DO NOT call QueryContext(invoke(ast), alias) from this function as it can potentially cause infinite loops
  // calling `invoke` which eventually calls `flatten.return`. Instead call QueryContext(invoke(ast.head), alias) manually if this fails
  // if it is safe (e.g. in the `invoke` function but NOT in `flatten.return`).
  private fun sourceSpecific(ast: XR.Query, alias: String): FromContext? =
    with(ast) {
      when (this) {
        is XR.Entity -> source(this, alias)
        is XR.Free -> source(this, alias)
        is XR.ExprToQuery -> source(this, alias)
        is XR.FlatJoin -> source(this, alias)
        is XR.Nested -> source(this, alias)
        is FlatUnit -> xrError("Source of a query cannot a flat-unit (e.g. where/groupBy/sortedBy)\n" + this.toString())
        else -> null
      }
    }

  private fun collectAliases(contexts: List<FromContext>): List<String> =
    contexts.flatMap {
      with(it) {
        when (this) {
          is TableContext -> listOf(alias)
          is QueryContext -> listOf(alias)
          is ExpressionContext -> listOf(alias)
          is FlatJoinContext -> collectAliases(listOf(from))
        }
      }
    }

  private fun collectTableAliases(contexts: List<FromContext>): List<String> =
    contexts.flatMap {
      with(it) {
        when (this) {
          is TableContext -> listOf(alias)
          is QueryContext -> emptyList()
          is ExpressionContext -> emptyList()
          is FlatJoinContext -> collectAliases(listOf(from))
        }
      }
    }
}



object FlattenCriteria {

  fun flattenOrderFields(fields: List<XR.OrderField>, from: List<FromContext>): List<XR.OrderField> =
    fields.flatMap { flattenOrderField(it, from) }

  fun flattenOrderField(ord: XR.OrderField, from: List<FromContext>) =
    flattenBase(ord.field, ord.orderingOpt, from)

  fun flattenBase(ast: XR.Expression, ord: XR.Ordering?, from: List<FromContext>): List<XR.OrderField> =
    when {
      // This is when you've got a single Asc/Desc and a tuple of sortBys e.g. `people.sortBy(p -> tupleOf(p.name, p.age))(Asc)`
      // in this case apply the Asc to each one recursively.
      ast is XR.Product && ord is PropertyOrdering ->
        ast.fields.flatMap { (_, value) -> flattenBase(value, ord, from) }

      // if its a quat product, use ExpandSelection to break it down into its component fields and apply the ordering to all of them
      // This is when you've got a single Asc/Desc and a ident in the sortBy e.g. `people.sortBy(p -> p)(Asc)`
      // that means we want to sort by every single field as Asc so apply the sorting recurisvely.
      ast is XR.Ident && ord is PropertyOrdering ->
        ExpandSelection(from).ofSubselect(listOf(SelectValue(ast))).map { it.expr }.flatMap { flattenBase(it, ord, from) }

      ord is PropertyOrdering -> listOf(XR.OrderField.By(ast, ord))
      else -> xrError("Invalid order by criteria $ast")
    }
}
