package io.getquill.context.sql

import io.getquill.ast._
import io.getquill.context.sql.norm.{ExpandSelection, FlattenGroupByAggregation}
import io.getquill.norm.BetaReduction
import io.getquill.quat.Quat
import io.getquill.util.{Interpolator, TraceConfig}
import io.getquill.util.Messages.{TraceType, fail}
import io.getquill.{Literal, PseudoAst, IdiomContext}
import io.getquill.sql.Common.ContainsImpurities

final data class OrderByCriteria(ast: Ast, ordering: PropertyOrdering)

sealed interface FromContext { def quat: Quat }
final data class TableContext(entity: Entity, alias: String) extends FromContext {
  override def quat: Quat = entity.quat
}
final data class QueryContext(query: SqlQuery, alias: String) extends FromContext {
  override def quat: Quat = query.quat
}
final data class InfixContext(infix: Free, alias: String) extends FromContext { override def quat: Quat = infix.quat }
final data class JoinContext(t: JoinType, a: FromContext, b: FromContext, on: Ast) extends FromContext {
  override def quat: Quat = Quat.Tuple(a.quat, b.quat)
}
final data class FlatJoinContext(t: JoinType, a: FromContext, on: Ast) extends FromContext {
  override def quat: Quat = a.quat
}

sealed interface SqlQuery {
  def quat: Quat

  override def toString: String = {
    import io.getquill.MirrorSqlDialect._
    import io.getquill.idiom.StatementInterpolator._
    implicit val idiomContext: IdiomContext = IdiomContext.Empty
    implicit val naming: Literal            = Literal
    implicit val tokenizer: Tokenizer[Ast]  = defaultTokenizer
    this.token.toString
  }
}

sealed interface SetOperation
case object UnionOperation    extends SetOperation
case object UnionAllOperation extends SetOperation

sealed interface DistinctKind { def isDistinct: Boolean }
case object DistinctKind {
  case object Distinct                          extends DistinctKind { override val isDistinct: Boolean = true  }
  final data class DistinctOn(props: List[Ast]) extends DistinctKind { override val isDistinct: Boolean = true  }
  case object None                              extends DistinctKind { override val isDistinct: Boolean = false }
}

final data class SetOperationSqlQuery(
  a: SqlQuery,
  op: SetOperation,
  b: SqlQuery
)(quatType: Quat)
    extends SqlQuery {
  override def quat: Quat = quatType
}

final data class UnaryOperationSqlQuery(
  op: UnaryOperator,
  q: SqlQuery
)(quatType: Quat)
    extends SqlQuery {
  override def quat: Quat = quatType
}

final data class SelectValue(ast: Ast, alias: Option[String] = None, concat: Boolean = false) extends PseudoAst {
  override def toString: String = s"${ast.toString}${alias.map("->" + _).getOrElse("")}"
}

final data class FlattenSqlQuery(
  from: List[FromContext] = List.empty,
  where: Option[Ast] = None,
  groupBy: Option[Ast] = None,
  orderBy: List[OrderByCriteria] = Nil,
  limit: Option[Ast] = None,
  offset: Option[Ast] = None,
  select: List[SelectValue],
  distinct: DistinctKind = DistinctKind.None
)(quatType: Quat)
    extends SqlQuery {
  override def quat: Quat = quatType
}

object TakeDropFlatten {
  def unapply(q: Query): Option[(Query, Option[Ast], Option[Ast])] = q match {
    case Take(q: FlatMap, n) => Some((q, Some(n), None))
    case Drop(q: FlatMap, n) => Some((q, None, Some(n)))
    case _                   => None
  }
}

object CaseClassMake {
  def fromQuat(quat: Quat)(idName: String): CaseClass =
    quat match {
      case p @ Quat.Product(fields) =>
        CaseClass(p.name, fields.toList.map { case (name, _) => (name, Property(Ident(idName, quat), name)) })
      // Figure out a way to test this case?
      case _ =>
        CaseClass(CaseClass.GeneratedName, List((idName, Ident(idName, quat))))
    }
}

class SqlQueryApply(traceConfig: TraceConfig) {

  val interp: Interpolator = new Interpolator(TraceType.SqlQueryConstruct, traceConfig, 1)
  import interp._

  def apply(query: Ast): SqlQuery =
    query match {
      case Union(a, b) =>
        trace("Construct SqlQuery from: Union") andReturn {
          SetOperationSqlQuery(apply(a), UnionOperation, apply(b))(query.quat)
        }
      case UnionAll(a, b) =>
        trace("Construct SqlQuery from: UnionAll") andReturn {
          SetOperationSqlQuery(apply(a), UnionAllOperation, apply(b))(query.quat)
        }
      case UnaryOperation(op, q: Query) =>
        trace("Construct SqlQuery from: UnaryOperation") andReturn {
          UnaryOperationSqlQuery(op, apply(q))(query.quat)
        }
      case _: Operation | _: Value =>
        trace("Construct SqlQuery from: Operation/Value") andReturn {
          FlattenSqlQuery(select = List(SelectValue(query)))(query.quat)
        }
      case Map(q, a, b) if a == b =>
        trace("Construct SqlQuery from: Map") andReturn {
          apply(q)
        }
      case TakeDropFlatten(q, limit, offset) =>
        trace("Construct SqlQuery from: TakeDropFlatten") andReturn {
          flatten(q, "x").copy(limit = limit, offset = offset)(q.quat)
        }
      case q: Query =>
        trace("Construct SqlQuery from: Query") andReturn {
          flatten(q, "x")
        }
      case infix: Free =>
        trace("Construct SqlQuery from: Free") andReturn {
          flatten(infix, "x")
        }
      case other =>
        trace("[INVALID] Construct SqlQuery from: other") andReturn {
          fail(s"Query not properly normalized. Please open a bug report. Ast: '$other'")
        }
    }

  private def flatten(query: Ast, alias: String): FlattenSqlQuery =
    trace("Flattening: ${query}") andReturn {
      val (sources, finalFlatMapBody) = flattenContexts(query)
      flatten(sources, finalFlatMapBody, alias, nestNextMap = false)
    }

  private def flattenContexts(query: Ast): (List[FromContext], Ast) =
    query match {
      // A flat-join query with no maps e.g: `qr1.flatMap(e1 => qr1.join(e2 => e1.i == e2.i))`
      case FlatMap(q @ (_: Query | _: Free), id: Ident, flatJoin @ FlatJoin(_, _, alias @ Ident(name, _), _)) =>
        trace("Flattening FlatMap with FlatJoin") andReturn {
          val cc = CaseClassMake.fromQuat(flatJoin.quat)(name)
          flattenContexts(FlatMap(q, id, Map(flatJoin, alias, cc)))
        }
      case FlatMap(q @ (_: Query | _: Free), Ident(alias, _), p: Query) =>
        trace("Flattening Flatmap with Query") andReturn {
          val source                             = this.source(q, alias)
          val (nestedContexts, finalFlatMapBody) = flattenContexts(p)
          (source +: nestedContexts, finalFlatMapBody)
        }
      // This needs to go before the case above it because in ExoQuery Free is a sub-type of Query
      case FlatMap(q @ (_: Query | _: Free), Ident(alias, _), p: Free) =>
        trace("Flattening Flatmap with Free") andReturn {
          fail(s"Free can't be use as a `flatMap` body. $query")
        }
      case other =>
        trace("Flattening other") andReturn {
          (List.empty, other)
        }
    }

  private def flatten(
    sources: List[FromContext],
    finalFlatMapBody: Ast,
    alias: String,
    nestNextMap: Boolean
  ): FlattenSqlQuery = {

    def select(alias: String, quat: Quat): List[SelectValue] = SelectValue(Ident(alias, quat), None) :: Nil

    def base(q: Ast, alias: String, nestNextMap: Boolean): FlattenSqlQuery =
      trace("Computing Base (nestingMaps=${nestNextMap}) for Query: $q") andReturn {
        def nest(ctx: FromContext): FlattenSqlQuery = trace("Computing FlattenSqlQuery for: $ctx") andReturn {
          FlattenSqlQuery(from = sources :+ ctx, select = select(alias, q.quat))(q.quat)
        }
        q match {
          case _: GroupByMap => trace("base| Nesting GroupByMap $q") andReturn nest(source(q, alias))

          case Map(_, _, ContainsImpurities()) =>
            trace("base| Nesting Map(a=>ContainsImpurities(a)) $q") andReturn nest(source(q, alias))

          case Nested(q)    => trace("base| Nesting Nested $q") andReturn nest(QueryContext(apply(q), alias))
          case q: ConcatMap => trace("base| Nesting ConcatMap $q") andReturn nest(QueryContext(apply(q), alias))

          case q @ (_: Filter | _: Entity) =>
            trace("base| Flattening Filter/Entity $q") andReturn { flatten(sources, q, alias, nestNextMap) }
          case q @ (_: Map) if (nestNextMap) => trace("base| Map + nest $q") andReturn { nest(source(q, alias)) }
          case q @ (_: Map)                  => trace("base| Map $q") andReturn { flatten(sources, q, alias, nestNextMap) }
          case q if (sources == Nil) =>
            trace("base| Flattening Empty-Sources $q") andReturn { flatten(sources, q, alias, nestNextMap) }
          case other => trace("base| Nesting 'other' $q") andReturn { nest(source(q, alias)) }
        }
      }

    val quat = finalFlatMapBody.quat
    trace("Flattening (alias = $alias) sources $sources from $finalFlatMapBody") andReturn {
      finalFlatMapBody match {

        case ConcatMap(q, Ident(alias, _), p) =>
          trace("Flattening| ConcatMap") andReturn {
            FlattenSqlQuery(
              from = source(q, alias) :: Nil,
              select = selectValues(p).map(_.copy(concat = true))
            )(quat)
          }

        // Given a clause that looks like:
        // people.groupByMap(p=>p.name)(a => (a.name,a.age.max))
        // In the AST it's more like:
        // GroupByMap(people,p=>p.name)(a:Person => p:(a.name,MAX(a.age)))
        // more concretely:
        // GroupBy(q:people,x:p,g:p.name)(a:Person, p:(a.name,MAX(a.age)))
        case GroupByMap(q, x @ Ident(alias, _), g, a, p) =>
          trace("Flattening| GroupByMap") andReturn {
            val b = base(q, alias, nestNextMap = true)
            // Same as ExpandSelection in Map(GroupBy)
            val flatGroupByAsts = new ExpandSelection(b.from).ofSubselect(List(SelectValue(g))).map(_.ast)
            val groupByClause =
              if (flatGroupByAsts.length > 1) Tuple(flatGroupByAsts)
              else flatGroupByAsts.head

            // We need to change the `a` var in:
            //   people.groupByMap(p=>p.name)(a => (a.name,a.age.max))
            // to same alias as 1st clause:
            //   p => (p.name,p.age.max)
            // since these become select-clauses:
            //   SelectValue(p.name,p.age.max)
            // since the `p` variable is in the `from` part of the query
            val realiasedSelect = BetaReduction(p, a -> x)
            b.copy(groupBy = Some(groupByClause), select = this.selectValues(realiasedSelect))(quat)
          }

        case Map(q, Ident(alias, _), p) =>
          val b = base(q, alias, nestNextMap = false)
          val agg = b.select.collect { case s @ SelectValue(_: Aggregation, _, _) =>
            s
          }
          if (!b.distinct.isDistinct && agg.isEmpty)
            trace("Flattening| Map(Ident) [Simple]") andReturn
              b.copy(select = selectValues(p))(quat)
          else
            trace("Flattening| Map(Ident) [Complex]") andReturn
              FlattenSqlQuery(
                from = QueryContext(apply(q), alias) :: Nil,
                select = selectValues(p)
              )(quat)

        case Filter(q, Ident(alias, _), p) =>
          // If it's a filter, pass on the value of nestNextMap in case there is a future map we need to nest
          val b = base(q, alias, nestNextMap)
          // If the filter body uses the filter alias, make sure it matches one of the aliases in the fromContexts
          if (
            b.where.isEmpty && (!CollectAst.byType[Ident](p).map(_.name).contains(alias) || collectAliases(b.from)
              .contains(alias))
          )
            trace("Flattening| Filter(Ident) [Simple]") andReturn
              b.copy(where = Some(p))(quat)
          else
            trace("Flattening| Filter(Ident) [Complex]") andReturn
              FlattenSqlQuery(
                from = QueryContext(apply(q), alias) :: Nil,
                where = Some(p),
                select = select(alias, quat)
              )(quat)

        case SortBy(q, Ident(alias, _), p, o) =>
          val b        = base(q, alias, nestNextMap = false)
          val criteria = orderByCriteria(p, o, b.from)
          // If the sortBy body uses the filter alias, make sure it matches one of the aliases in the fromContexts
          if (
            b.orderBy.isEmpty && (!CollectAst.byType[Ident](p).map(_.name).contains(alias) || collectAliases(b.from)
              .contains(alias))
          )
            trace("Flattening| SortBy(Ident) [Simple]") andReturn
              b.copy(orderBy = criteria)(quat)
          else
            trace("Flattening| SortBy(Ident) [Complex]") andReturn
              FlattenSqlQuery(
                from = QueryContext(apply(q), alias) :: Nil,
                orderBy = criteria,
                select = select(alias, quat)
              )(quat)

        // TODO Finish describing
        // Happens when you either have an aggregation in the middle of a query
        // ...
        // Or as the result of a map
        case Aggregation(op, q: Query) =>
          val b = flatten(q, alias)
          b.select match {
            case head :: Nil if !b.distinct.isDistinct =>
              trace("Flattening| Aggregation(Query) [Simple]") andReturn
                b.copy(select = List(head.copy(ast = Aggregation(op, head.ast))))(quat)
            case other =>
              trace("Flattening| Aggregation(Query) [Complex]") andReturn
                FlattenSqlQuery(
                  from = QueryContext(apply(q), alias) :: Nil,
                  select = List(
                    SelectValue(Aggregation(op, Ident("*", quat)))
                  ) // Quat of a * aggregation is same as for the entire query
                )(quat)
          }

        case agg @ Aggregation(_, _) =>
          trace("Flattening| Aggregation(Invalid)") andReturn {
            fail(
              s"Found the aggregation `${agg}` in an invalid place. An SQL aggregation (e.g. min/max/etc...) cannot be used in the body of an SQL statement e.g. in the WHERE clause."
            )
          }

        case Take(q, n) =>
          val b = base(q, alias, nestNextMap = false)
          if (b.limit.isEmpty)
            trace("Flattening| Take [Simple]") andReturn
              b.copy(limit = Some(n))(quat)
          else
            trace("Flattening| Take [Complex]") andReturn
              FlattenSqlQuery(
                from = QueryContext(apply(q), alias) :: Nil,
                limit = Some(n),
                select = select(alias, quat)
              )(quat)

        case Drop(q, n) =>
          val b = base(q, alias, nestNextMap = false)
          if (b.offset.isEmpty && b.limit.isEmpty)
            trace("Flattening| Drop [Simple]") andReturn
              b.copy(offset = Some(n))(quat)
          else
            trace("Flattening| Drop [Complex]") andReturn
              FlattenSqlQuery(
                from = QueryContext(apply(q), alias) :: Nil,
                offset = Some(n),
                select = select(alias, quat)
              )(quat)

        case Distinct(q) =>
          val b = base(q, alias, nestNextMap = false)
          trace("Flattening| Distinct") andReturn
            b.copy(distinct = DistinctKind.Distinct)(quat)

        case DistinctOn(q, Ident(alias, _), fields) =>
          val distinctList =
            fields match {
              case Tuple(values) => values
              case other         => List(other)
            }

          q match {
            // Ideally we don't need to make an extra sub-query for every single case of
            // distinct-on but it only works when the parent AST is an entity. That's because DistinctOn
            // selects from an alias of an outer clause. For example, query[Person].map(p => Name(p.firstName, p.lastName)).distinctOn(_.name)
            // (Let's say Person(firstName, lastName, age), Name(first, last)) will turn into
            // SELECT DISTINCT ON (p.name), p.firstName AS first, p.lastName AS last, p.age FROM Person
            // This doesn't work because `name` in `p.name` doesn't exist yet. Therefore we have to nest this in a subquery:
            // SELECT DISTINCT ON (p.name) FROM (SELECT p.firstName AS first, p.lastName AS last, p.age FROM Person p) AS p
            // The only exception to this is if we are directly selecting from an entity:
            // query[Person].distinctOn(_.firstName) which should be fine: SELECT (x.firstName), x.firstName, x.lastName, a.age FROM Person x
            // since all the fields inside the (...) of the DISTINCT ON must be contained in the entity.
            case _: Entity =>
              val b = base(q, alias, nestNextMap = false)
              b.copy(distinct = DistinctKind.DistinctOn(distinctList))(quat)
            case _ =>
              trace("Flattening| DistinctOn") andReturn
                FlattenSqlQuery(
                  from = QueryContext(apply(q), alias) :: Nil,
                  select = select(alias, quat),
                  distinct = DistinctKind.DistinctOn(distinctList)
                )(quat)
          }

        case other =>
          trace("Flattening| Other") andReturn
            FlattenSqlQuery(from = sources :+ source(other, alias), select = select(alias, quat))(quat)
      }
    }
  }

  private def selectValues(ast: Ast) =
    ast match {
      // case Tuple(values) => values.map(SelectValue(_))
      case _ => SelectValue(ast) :: Nil
    }

  private def source(ast: Ast, alias: String): FromContext =
    ast match {
      case entity: Entity            => TableContext(entity, alias)
      case infix: Free              => InfixContext(infix, alias)
      case Join(t, a, b, ia, ib, on) => JoinContext(t, source(a, ia.name), source(b, ib.name), on)
      case FlatJoin(t, a, ia, on)    => FlatJoinContext(t, source(a, ia.name), on)
      case Nested(q)                 => QueryContext(apply(q), alias)
      case other                     => QueryContext(apply(other), alias)
    }

  private def orderByCriteria(ast: Ast, ordering: Ast, from: List[FromContext]): List[OrderByCriteria] =
    (ast, ordering) match {
      case (Tuple(properties), ord: PropertyOrdering) => properties.flatMap(orderByCriteria(_, ord, from))
      case (Tuple(properties), TupleOrdering(ord)) =>
        properties.zip(ord).flatMap { case (a, o) => orderByCriteria(a, o, from) }
      // if its a quat product, use ExpandSelection to break it down into its component fields and apply the ordering to all of them
      case (Ident(_, _: Quat.Product), ord) =>
        new ExpandSelection(from).ofSubselect(List(SelectValue(ast))).map(_.ast).flatMap(orderByCriteria(_, ord, from))
      case (a, o: PropertyOrdering) => List(OrderByCriteria(a, o))
      case _                        => fail(s"Invalid order by criteria $ast")
    }

  private def collectAliases(contexts: List[FromContext]): List[String] =
    contexts.flatMap {
      case c: TableContext             => List(c.alias)
      case c: QueryContext             => List(c.alias)
      case c: InfixContext             => List(c.alias)
      case JoinContext(_, a, b, _)     => collectAliases(List(a)) ++ collectAliases(List(b))
      case FlatJoinContext(_, from, _) => collectAliases(List(from))
    }

  private def collectTableAliases(contexts: List[FromContext]): List[String] =
    contexts.flatMap {
      case c: TableContext             => List(c.alias)
      case _: QueryContext             => List.empty
      case _: InfixContext             => List.empty
      case JoinContext(_, a, b, _)     => collectAliases(List(a)) ++ collectAliases(List(b))
      case FlatJoinContext(_, from, _) => collectAliases(List(from))
    }

}
