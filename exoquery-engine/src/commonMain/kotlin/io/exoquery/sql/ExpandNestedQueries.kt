package io.exoquery.sql

import io.decomat.*
import io.exoquery.xr.*
import io.exoquery.xr.XR.Visibility


class ExpandSelection(val from: List<FromContext>) {

  /** For external things to potentially use ExpandSelection */
  fun ofTop(values: List<SelectValue>, topLevelQuat: XRType): List<SelectValue> =
    invoke(values, QueryLevel.Top(topLevelQuat))

  fun ofSubselect(values: List<SelectValue>): List<SelectValue> =
    invoke(values, QueryLevel.Inner)

  internal operator fun invoke(values: List<SelectValue>, level: QueryLevel): List<SelectValue> =
    values.flatMap { invoke(it, level) }

  internal fun invoke(value: SelectValue, level: QueryLevel): List<SelectValue> {
    fun concatOr(concatA: List<String>, concatB: List<String>, or: String?) =
      if (!level.isTop)
        concatA + concatB
      else
        if (or != null) listOf(or) else listOf()

    // Assuming there's no case class or tuple buried inside or a property i.e. if there were,
    // the beta reduction would have unrolled them already
    return on(value).match(
      case(SelectValue[PropertyOrCore(), Is()]).thenThis { ast, alias ->
        // Whether or not we are in a concatMap-type of selection
        val concat = this.concat
        val alternateQuat =
          when (level) {
            is QueryLevel.Top -> level.topLevelQuat
            else -> null
          }
        val exp = SelectPropertyProtractor(from)(ast, alternateQuat)
        exp.map { (p, path) ->
          when {
            // If the quat-path is nothing and there is some preexisting alias (e.g. if we came from a case-class or quat)
            // the use that. Otherwise the selection is of an individual element so use the element name (before the rename)
            // as the alias.
            p is XR.Property && path.isEmpty() ->
              SelectValue(p, alias or p.name)
            // Append alias headers (i.e. _1,_2 from tuples and field names foo,bar from case classes) to the
            // value of the XRType path
            p is XR.Property && path.isNotEmpty() ->
              SelectValue(p, concatOr(alias, path, path.lastOrNull()), concat)
            else ->
              SelectValue(p, alias, concat)
          }
        }
      },
      case(SelectValue[XR.Product.Is, Is()]).thenThis { product, alias ->
        val concat = this.concat
        val fields = product.fields
        fields.flatMap { (name, ast) ->
          // Go into the select values, if the level is Top we need to go TopUnwrapped since the top-level
          // XRType doesn't count anymore. If level=Inner then it's the same.
          invoke(SelectValue(ast, concatOr(listOf(name), alias, name), concat), level.withoutTopQuat())
        }
      }


    ) // SelectValue(Direct) SelectValue(infix), etc...
      ?: listOf(value)
  }
}

infix fun List<String>.or(alternate: String) =
  if (this.isEmpty()) listOf(alternate) else this


/*
 * Much of what this does is documented in PRs here:
 * https://github.com/zio/zio-quill/pull/1920 and here:
 * https://github.com/zio/zio-quill/pull/2381 and here:
 * https://github.com/zio/zio-quill/pull/2420
 */
class ExpandNestedQueries(val pathJoinFunction: (List<String>) -> String) : StatelessQueryTransformer() {

  override fun invoke(q: SqlQueryModel, level: QueryLevel): SqlQueryModel =
    when (q) {
      is FlattenSqlQuery -> {
        val selection = ExpandSelection(q.from)(q.select, level)
        val out = expandNested(q.copy(select = selection, type = q.type), level)
        out
      }
      else ->
        super.invoke(q, level)
    }

  data class FlattenNestedProperty(val from: List<FromContext>, val pathJoinFunction: (List<String>) -> String) {
    val inContext = InContext(from)

    fun invoke(p: XR.Expression): XR.Expression =
      on(p).match(
        case(PropertyMatryoshka[Is(), Is()]).thenThis { inner, propsPath ->
          val isSubselect = inContext.isSubselect(p)

          // If it is a sub-select do not apply the strategy to the property
          if (isSubselect)
            XR.Property(inner, pathJoinFunction(propsPath.map { it.name }), Visibility.Visible, propsPath.last().loc)
          else
            XR.Property(inner, propsPath.last().name, Visibility.Visible, propsPath.last().loc)
        }
      ) ?: p

    fun inside(ast: XR.Expression) =
      TransformXR.Expression(ast) {
        when (it) {
          is XR.Property -> invoke(it)
          else -> null
        }
      }
  }


  override fun expandNested(q: FlattenSqlQuery, level: QueryLevel): FlattenSqlQuery =
    with(q) {
      val flattenNestedProperty = FlattenNestedProperty(from, pathJoinFunction)
      val newFroms = q.from.map { expandContextFlattenOns(it, flattenNestedProperty) }

      fun distinctIfNotTopLevel(values: List<SelectValue>) =
        if (level.isTop)
          values
        else
          values.distinct()

      /*
       * In sub-queries, need to make sure that the same field/alias pair is not selected twice
       * which is possible when aliases are used. For example, something like this:
       *
       * case class Emb(id: Int, name: String)
       * case class Parent(id: Int, name: String, emb: Emb)
       * case class GrandParent(id: Int, par: Parent)
       * val q = quote { query<GrandParent>.map(g => g.par).distinct.map(p => (p.name, p.emb, p.id, p.emb.id)).distinct.map(tup => (tup._1, tup._2, tup._3, tup._4)).distinct }
       * Will cause double-select inside the innermost subselect:
       * SELECT DISTINCT theParentName AS theParentName, id AS embid, theName AS embtheName, id AS id, id AS embid FROM GrandParent g
       * Note how embid occurs twice? That's because (p.emb.id, p.emb) are expanded into (p.emb.id, p.emb.id, e.emb.name).
       *
       * On the other hand if the query is top level we need to make sure not to do this deduping or else the encoders won't work since they rely on clause positions
       * For example, something like this:
       * val q = quote { query<GrandParent>.map(g => g.par).distinct.map(p => (p.name, p.emb, p.id, p.emb.id)) }
       * Would normally expand to this:
       * SELECT p.theParentName, p.embid, p.embtheName, p.id, p.embid FROM ...
       * Note now embed occurs twice? We need to maintain this because the second element of the output tuple
       * (p.name, p.emb, p.id, p.emb.id) needs the fields p.embid, p.embtheName in that precise order in the selection
       * or they cannot be encoded.
       */
      val distinctSelects =
        distinctIfNotTopLevel(select)

      val distinctKind =
        when (val distinct = q.distinct) {
          is DistinctKind.DistinctOn ->
            DistinctKind.DistinctOn(distinct.props.map { flattenNestedProperty.inside(it) })
          else ->
            distinct
        }

      q.copy(
        select = distinctSelects.map { sv -> sv.copy(expr = flattenNestedProperty.inside(sv.expr)) },
        from = newFroms,
        where = where?.let { flattenNestedProperty.inside(it) },
        groupBy = groupBy?.let { flattenNestedProperty.inside(it) },
        orderBy = orderBy.map { ord -> ord.transform { flattenNestedProperty.inside(it) } },
        limit = limit?.let { flattenNestedProperty.inside(it) },
        offset = offset?.let { flattenNestedProperty.inside(it) },
        distinct = distinctKind,
        type = type
      )
    }

  fun expandContextFlattenOns(s: FromContext, flattenNested: FlattenNestedProperty): FromContext {
    fun expandContextRec(s: FromContext): FromContext =
      when (s) {
        is QueryContext -> QueryContext(invoke(s.query, QueryLevel.Inner), s.alias)
        is FlatJoinContext -> FlatJoinContext(s.joinType, expandContextRec(s.from), flattenNested.inside(s.on))
        is TableContext, is ExpressionContext -> s
      }

    return expandContextRec(s)
  }
}
