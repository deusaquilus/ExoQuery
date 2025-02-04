@file:Suppress("NAME_SHADOWING", "NAME_SHADOWING")

package io.exoquery.sql

import io.exoquery.PostgresDialect
import io.exoquery.sql.FlattenSqlQuery
import io.exoquery.util.Globals
import io.exoquery.util.TraceConfig
import io.exoquery.util.TraceType
import io.exoquery.util.Tracer
import io.exoquery.xr.*
import io.exoquery.xr.XR.FlatMap
import io.exoquery.xr.XR.Map
import io.exoquery.xr.XR.Labels.FlatUnit
import io.exoquery.xr.XR.FlatGroupBy
import io.exoquery.xr.XR.FlatSortBy
import io.exoquery.xr.XR.FlatFilter
import io.exoquery.xr.XR.FlatJoin
import io.exoquery.xr.XR.Ordering.PropertyOrdering
import io.exoquery.xr.XR.Ordering.TupleOrdering
import io.exoquery.xrError
import io.decomat.productComponentsOf as productOf
import io.decomat.HasProductClass as PC

final data class OrderByCriteria(val ast: XR.Expression, val ordering: PropertyOrdering)

sealed interface FromContext {
  val type: XRType

  fun transformXR(f: StatelessTransformer): FromContext =
    when (this) {
      is TableContext -> this
      is QueryContext -> QueryContext(query.transformXR(f), alias)
      is InfixContext -> this.transformParamXRs { xr -> (xr as? XR.Query)?.let { f(it) } ?: xr }
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

final data class TableContext(val entity: XR.Entity, val alias: String) : FromContext {
  override val type: XRType = entity.type
  // TODO actually want the alias to be the identifier on which it is based, need to change that in SqlQuery
  fun aliasIdent() = XR.Ident(alias, entity.type, XR.Location.Synth)
}

final data class QueryContext(val query: SqlQuery, val alias: String) : FromContext {
  override val type: XRType = query.type
  fun aliasIdent() = XR.Ident(alias, query.type, XR.Location.Synth)
}

final data class InfixContext(val infix: XR.Infix, val alias: String) : FromContext {
  override val type: XRType = infix.type

  fun transformParamXRs(f: (XR) -> XR): InfixContext =
    copy(infix = infix.copy(params = infix.params.map(f)))

  fun aliasIdent() = XR.Ident(alias, infix.type, XR.Location.Synth)
}

final data class FlatJoinContext(val joinType: XR.JoinType, val from: FromContext, val on: XR.Expression) : FromContext {
  override val type: XRType = from.type
}

sealed interface SqlQuery {
  val type: XRType

  fun transformType(f: (XRType) -> XRType): SqlQuery =
    when (this) {
      is FlattenSqlQuery -> copy(type = f(type))
      is SetOperationSqlQuery -> copy(type = f(type))
      is UnaryOperationSqlQuery -> copy(type = f(type))
    }

  fun transformXR(f: StatelessTransformer): SqlQuery =
    when (this) {
      is FlattenSqlQuery -> this.transformXR(f) // call the transformXR on FlattenSqlQuery directly
      is SetOperationSqlQuery -> SetOperationSqlQuery(a.transformXR(f), op, b.transformXR(f), type)
      is UnaryOperationSqlQuery -> UnaryOperationSqlQuery(op, query.transformXR(f), type)
    }

  fun show(pretty: Boolean = false): String {
    val str =
      with(PostgresDialect(Globals.traceConfig())) {
        this@SqlQuery.token.toString()
      }
    // TODO integrate formatter via `expect`
    //return if (pretty) SqlFormatter.format(str) else str
    return str
  }


  fun showRaw(color: Boolean = true): String {
    // TODO need to instrument serialization of this in order to be able to pretty-print in KMP
    //val str = PrintXR()(this)
    //return if (color) str.toString() else str.plainText
    return this.toString()
  }
}

sealed interface SetOperation
object UnionOperation : SetOperation
object UnionAllOperation : SetOperation

sealed interface DistinctKind {
  val isDistinct: Boolean

  object Distinct : DistinctKind { override val isDistinct: Boolean = true }
  data class DistinctOn(val props: List<XR.Expression>) : DistinctKind { override val isDistinct: Boolean = true }
  object None : DistinctKind { override val isDistinct: Boolean = false }
}

final data class SetOperationSqlQuery(
  val a: SqlQuery,
  val op: SetOperation,
  val b: SqlQuery,
  override val type: XRType
): SqlQuery

final data class UnaryOperationSqlQuery(
  val op: XR.UnaryOp,
  val query: SqlQuery,
  override val type: XRType
): SqlQuery


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
 * The only exception to this is an Infix which can both be a Query and Expression but in the case
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
final data class SelectValue(val expr: XR.Expression, val alias: List<String> = listOf(), val concat: Boolean = false): PC<SelectValue> {
  override val productComponents = productOf(this, expr, alias)
  val type: XRType = expr.type
  companion object {}
}

final data class FlattenSqlQuery(
  val from: List<FromContext> = emptyList(),
  val where: XR.Expression? = null,
  val groupBy: XR.Expression? = null,
  val orderBy: List<OrderByCriteria> = emptyList(),
  val limit: XR.Expression? = null,
  val offset: XR.Expression? = null,
  val select: List<SelectValue> = emptyList(),
  val distinct: DistinctKind = DistinctKind.None,
  override val type: XRType
): SqlQuery {

  // Overriding so make this return a more-specific type
  override fun transformXR(f: StatelessTransformer): FlattenSqlQuery =
    this.transformClauses(f)

  fun transformClauses(f: StatelessTransformer): FlattenSqlQuery =
    copy(
      from = from.map { it.transformXR(f) },
      where = where?.let { f(it) },
      groupBy = groupBy?.let { f(it) },
      orderBy = orderBy.map { it.copy(ast = f(it.ast)) },
      limit = limit?.let { f(it) },
      offset = offset?.let { f(it) },
      select = select.map { it.copy(expr = f(it.expr)) }
    )

  /*
    def mapAsts(f: Ast => Ast): FlattenSqlQuery =
    copy(
      where = where.map(f),
      groupBy = groupBy.map(f),
      orderBy = orderBy.map(o => o.copy(ast = f(o.ast))),
      limit = limit.map(f),
      offset = offset.map(f),
      select = select.map(s => s.copy(ast = f(s.ast)))
    )(quatType)
   */
}

class SqlQueryApply(val traceConfig: TraceConfig) {
  val trace: Tracer = Tracer(TraceType.SqlQueryConstruct, traceConfig, 1)

  operator fun invoke(query: XR.Query): SqlQuery =
    with(query) {
      when(this) {
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
        is XR.Infix ->
          trace("Construct SqlQuery from: Infix") andReturn { flatten(this, XR.Ident("x", type, XR.Location.Synth)) }

        // TODO This not ever happen here, need to examine situations where this is possible
        is XR.CustomQueryRef if customQuery is SelectClause ->
          flatten(customQuery.toXrTransform(), XR.Ident("x", type, XR.Location.Synth))

        else ->
          trace("Construct SqlQuery from: Query").andReturn {
            flatten(this, XR.Ident("x", type, XR.Location.Synth))
          }
      }
    }

  sealed interface Layer {
    data class Context(val ctx: FromContext): Layer
    data class Grouping(val groupBy: XR.Expression): Layer
    data class Sorting(val sortedBy: XR.Expression, val ordering: XR.Ordering): Layer
    data class Filtering(val where: XR.Expression): Layer
    companion object {
      fun fromFlatUnit(xr: XR.Labels.FlatUnit): Layer =
        when (xr) {
          is FlatFilter -> Layer.Filtering(xr.by)
          is FlatGroupBy -> Layer.Grouping(xr.by)
          is FlatSortBy -> Layer.Sorting(xr.by, xr.ordering)
        }
    }
  }


  private fun flattenContexts(query: XR.Query): Pair<List<Layer>, XR.Labels.QueryOrExpression> =
    with(query) {
      when {
        // A flat-join query with no maps e.g: `qr1.flatMap(e1 => qr1.join(e2 => e1.i == e2.i))`
        this is XR.FlatMap && body is FlatJoin ->
          trace("Flattening FlatMap with FlatJoin") andReturn {
            val cc: XR.Product = XR.Product.fromProductIdent(body.id)
            flattenContexts(FlatMap.csf(head, id, Map(body, body.id, cc, loc))(this))
          }
        this is XR.FlatMap && body is XR.Infix ->
          trace("[INVALID] Flattening Flatmap with Infix") andReturn {
            xrError("Infix can't be use as a `flatMap` body. $query")
          }
        // Conceptually (the actual DSL is different):
        // people.flatMap(p -> groupBy(expr).flatMap(rest)) is:
        //   FlatMap(people, p, FlatMap(GroupBy(expr), rest)))
        this is XR.FlatMap && (head is FlatUnit) ->
          trace("Flattening Flatmap with FlatGroupBy") andReturn {
            val (nestedContexts, finalFlatMapBody) = flattenContexts(body)
            listOf(Layer.fromFlatUnit(head)) + nestedContexts to finalFlatMapBody
          }

        this is XR.Map && head is FlatUnit ->
          trace("Flattening Flatmap with FlatGroupBy") andReturn {
            listOf(Layer.fromFlatUnit(head)) to body
          }

        this is XR.FlatMap ->
          trace("Flattening Flatmap with Query") andReturn {
            val source                             = source(head, id.name)
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

      val queryRaw =
        when (finalFlatMapBody) {
          is XR.Expression ->
            FlattenSqlQuery(from = contexts, select = selectValues(finalFlatMapBody), type = query.type)
          is XR.Query ->
            flatten(contexts, finalFlatMapBody, alias, nestNextMap = false)
        }
      val query =
        queryRaw
          .let { if (grouping != null)  it.copy(groupBy = grouping.groupBy) else it }
          // TODO what if there is already an orderBy?
          .let { if (sorting != null)  it.copy(orderBy = orderByCriteria(sorting.sortedBy, sorting.ordering, contexts)) else it }
          // Not sure if its possible to already have a where-clause but if it is combine them
          .let { if (filtering != null)  it.copy(where = combineWhereClauses(it.where, filtering.where)) else it }

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
            this is XR.GroupByMap -> trace("base| Nesting GroupByMap $query") andReturn { nest(source(query, alias.name)) }

            // A map that contains mapped-to aggregations e.g.
            //   people.map(p=>max(p.name))
            // Could have more complex structures e.g: people.map(p=>SomeCaseClassOrTuple(max(p.name),min(p.age)))
            // so we need to search for the aggregations.
            // Also it could have impure-infixes inside e.g.
            //   people.map(p=>SomeCaseClassOrTuple(p.name, someStatefulSqlFunction(p.age)))
            // therefore we need to check if there are elements like this inside of the map-function and nest it
            this is XR.Map && containsImpurities() ->
              trace("base| Nesting Map(a=>ContainsImpurities(a)) $query") andReturn { nest(source(query, alias.name)) }

            this is XR.Nested -> trace("base| Nesting Nested $query") andReturn { nest(source(head, alias.name)) }

            this is XR.ConcatMap -> trace("base| Nesting ConcatMap $query") andReturn { nest(source(this, alias)) }

            this is XR.Filter -> trace("base| Flattening Filter $query") andReturn { flatten(sources, query, alias, nestNextMap) }
            this is XR.Entity -> trace("base| Flattening Entity $query") andReturn { flatten(sources, query, alias, nestNextMap) }

            this is XR.Map && nestNextMap ->
              trace("base| Map + nest $query") andReturn { nest(source(query, alias)) }
            this is XR.Map ->
              trace("base| Map $query") andReturn { flatten(sources, query, alias, nestNextMap) }

            sources.isEmpty() ->
              trace("base| Flattening Empty-Sources $query") andReturn { flatten(sources, query, alias, nestNextMap) }

            else -> trace("base| Nesting 'other' $query") andReturn { nest(source(query, alias)) }
          }
        }
      }

      val type = finalFlatMapBody.type

      fun flattenStandard(q: XR.Query) =
        trace("Flattening| Other").andReturn {
          FlattenSqlQuery(from = sources + source(q, alias), select = select(alias.name, type, q.loc), type = type)
        }

      return trace("Flattening (alias = $alias) sources $sources from $finalFlatMapBody") andReturn {
        with (finalFlatMapBody) {
          when (this) {
            is XR.ConcatMap ->
              trace("Flattening| ConcatMap") andReturn {
                FlattenSqlQuery(
                  from = sources + source(head, alias),
                  select = selectValues(body).map { it.copy(concat = true) },
                  type = type
                )
              }

            is XR.GroupByMap -> {
              trace("Flattening| GroupByMap") andReturn {
                val b = base(head, alias = byAlias, nestNextMap = true)
                val flatGroupByAsts = ExpandSelection(b.from).ofSubselect(listOf(SelectValue(byBody))).map { it.expr }
                val groupByClause: XR.Expression =
                  // Can use TupleNumeric because we don't actually care about the field names
                  if (flatGroupByAsts.size > 1) XR.Product.TupleNumeric(flatGroupByAsts, XR.Location.Synth)
                  else flatGroupByAsts.first()

                // We need to change the `a` var in:
                //   people.groupByMap(p=>p.name)(a => (a.name,a.age.max))
                // to same alias as 1st clause:
                //   p => (p.name,p.age.max)
                // since these become select-clauses:
                //   SelectValue(p.name,p.age.max)
                // since the `p` variable is in the `from` part of the query
                // (also need to make sure that the `a` variable does not have p's type)
                val realiasedSelect = BetaReduction(mapBody, TypeBehavior.ReplaceWithReduction, mapAlias to byAlias)
                b.copy(groupBy = groupByClause, select = selectValues(realiasedSelect), type = type)
              }
            }

            is Map -> {
              val b = base(head, id, nestNextMap = false)
              val aggs = b.select.filter { it.expr is XR.Aggregation }
              if (!b.distinct.isDistinct && aggs.isEmpty())
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
              fun allIdentsIn(criteria: List<OrderByCriteria>) =
                criteria.flatMap { CollectXR.byType<XR.Ident>(it.ast).map { it.name } }

              val b = base(head, id, nestNextMap = false)
              val criteria = orderByCriteria(criteria, ordering, b.from)
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
              if (b.offset != null && b.limit != null)
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

            is XR.Distinct -> {
              val b = base(head, alias, nestNextMap = false)
              trace("Flattening| Distinct") andReturn {
                b.copy(distinct = DistinctKind.Distinct, type = type)
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
                from = sources + source(this, alias),
                select = select(alias.name, type, alias.loc),
                type = type
              )

            // TODO This not ever happen here, need to examine situations where this is possible
            is XR.CustomQueryRef if this.customQuery is SelectClause ->
              FlattenSqlQuery(
                from = sources + source(this.customQuery.toXrTransform(), alias),
                select = select(alias.name, type, alias.loc),
                type = type
              )

            //else ->
            //  xrError("Invalid flattening: ${this.showRaw()}")
              //trace("Flattening| Other") andReturn {
              //  FlattenSqlQuery(
              //    from = sources + source(this, alias),
              //    select = select(alias.name, type, alias.loc),
              //    type = type
              //  )
              //}

            is XR.CustomQueryRef -> flattenStandard(this)
            is FlatFilter -> flattenStandard(this)
            is FlatGroupBy -> flattenStandard(this)
            is FlatJoin -> flattenStandard(this)
            is FlatMap -> flattenStandard(this)
            is FlatSortBy -> flattenStandard(this)
            is XR.Infix -> flattenStandard(this)
            is XR.Nested -> flattenStandard(this)
            is XR.QueryOf -> flattenStandard(this)
            is XR.TagForSqlQuery -> flattenStandard(this)
            is XR.Union -> flattenStandard(this)
            is XR.UnionAll -> flattenStandard(this)
          }
        }
      }
    }

  private fun orderByCriteria(ast: XR.Expression, ord: XR.Ordering, from: List<FromContext>): List<OrderByCriteria> =
    when {
      // Typically when you have something like `people.sortBy(p -> tupleOf(p.name, p.age))(tupleOf(Asc, Desc))`
      // the tuple of Asc/Descs needs to have the same size as the sortBy tuple
      ast is XR.Product && ord is TupleOrdering -> {
        if (ord.elems.size != ast.fields.size) xrError("TODO error msg")
        ord.elems.zip(ast.fields).flatMap { (ordElem, field) -> orderByCriteria(field.second, ordElem, from) }
      }
      // This is when you've got a single Asc/Desc and a tuple of sortBys e.g. `people.sortBy(p -> tupleOf(p.name, p.age))(Asc)`
      // in this case apply the Asc to each one recursively.
      ast is XR.Product && ord is PropertyOrdering ->
        ast.fields.flatMap { (_, value) -> orderByCriteria(value, ord, from) }

      // if its a quat product, use ExpandSelection to break it down into its component fields and apply the ordering to all of them
      // This is when you've got a single Asc/Desc and a ident in the sortBy e.g. `people.sortBy(p -> p)(Asc)`
      // that means we want to sort by every single field as Asc so apply the sorting recurisvely.
      ast is XR.Ident && ord is PropertyOrdering ->
        ExpandSelection(from).ofSubselect(listOf(SelectValue(ast))).map { it.expr }.flatMap { orderByCriteria(it, ord, from) }

      ord is PropertyOrdering -> listOf(OrderByCriteria(ast, ord))
      else -> xrError("Invalid order by criteria $ast")
    }

  private fun selectValues(ast: XR.Expression) = listOf(SelectValue(ast))

  private fun source(ast: XR.Query, alias: XR.Ident): FromContext =
    source(ast, alias.name)
  private fun source(ast: XR.Query, alias: String): FromContext =
    with (ast) {
      when (this) {
        is XR.Entity            -> TableContext(this, alias)
        is XR.Infix             -> InfixContext(this, alias)
        // people.flatMap(p -> join(addresses, on).flatMap(rest)) is:
        //   FlatMap(people, p, FlatMap(FlatJoin(people, on), rest))
        is XR.FlatJoin          -> FlatJoinContext(joinType, source(head, id.name), on)
        is XR.Nested            -> QueryContext(invoke(head), alias)
        is FlatUnit             -> xrError("Source of a query cannot a flat-unit (e.g. where/groupBy/sortedBy)\n" + this.toString())
        else                    -> QueryContext(invoke(ast), alias)
      }
    }

  private fun collectAliases(contexts: List<FromContext>): List<String> =
    contexts.flatMap {
      with (it) {
        when (this) {
          is TableContext             -> listOf(alias)
          is QueryContext             -> listOf(alias)
          is InfixContext             -> listOf(alias)
          is FlatJoinContext -> collectAliases(listOf(from))
        }
      }
    }

  private fun collectTableAliases(contexts: List<FromContext>): List<String> =
    contexts.flatMap {
      with (it) {
        when (this) {
          is TableContext             -> listOf(alias)
          is QueryContext             -> emptyList()
          is InfixContext             -> emptyList()
          is FlatJoinContext -> collectAliases(listOf(from))
        }
      }
    }
}
