package io.exoquery.xr

import io.exoquery.xr.copy.*
import io.exoquery.xrError
import io.exoquery.xr.XR.Labels.QueryOrExpression

sealed interface TypeBehavior {
  object SubstituteSubtypes: TypeBehavior
  object ReplaceWithReduction: TypeBehavior
}

sealed interface EmptyProductTypeBehavior {
  object Fail: EmptyProductTypeBehavior
  object Ignore: EmptyProductTypeBehavior
}

// I think beta reduction should be used for QueryOrExpression in all the cases, shuold
// move forward and find out if this is actually the case. If it is not, can probably
// just add `case ast if map.contains(ast) =>` to the apply for Query, Function, etc...
// maybe should have separate maps for Query, Function, etc... for that reason if those cases even exist
data class BetaReduction(val map: Map<QueryOrExpression, QueryOrExpression>, val typeBehavior: TypeBehavior, val emptyBehavior: EmptyProductTypeBehavior):
  StatelessTransformer {

  private fun replaceWithReduction() = typeBehavior == TypeBehavior.ReplaceWithReduction

  private fun BetaReduce(map: Map<QueryOrExpression, QueryOrExpression>) =
    BetaReduction(map, typeBehavior, emptyBehavior)

  private fun correctTheTypeOfReplacement(orig: QueryOrExpression, rep: QueryOrExpression): QueryOrExpression =
    when {
      replaceWithReduction() -> rep
      rep is XR.Labels.Terminal && rep.isBottomTypedTerminal() -> rep.withType(orig.type)
      rep is XR.Labels.Terminal -> {
        val type = orig.type.leastUpperType(rep.type) ?: run {
          // TODO log warning about the invalid reduction
          XRType.Unknown
        }
        rep.withType(type)
      }
      else -> rep
    }

  override fun invoke(xr: XR.Expression): XR.Expression =
    replaceAtHead(xr)?.let {
      when(it) {
        is XR.Expression -> it
        is XR.Query -> XR.QueryToExpr(it)
      }
    } ?: xr

  fun replaceAtHead(xr: XR.Labels.QueryOrExpression): XR.Labels.QueryOrExpression? {
    val replacement = map[xr]
    return when {
      // I.e. we have an actual replacement for this element
      replacement != null -> {
        val rep = BetaReduce(map - xr - replacement).invoke(replacement)
        correctTheTypeOfReplacement(xr, rep)
      }

      // Represents: case Property(Product(_, tuples), name) =>
      // The rule:
      // Property(Product(foo->bar), foo) ->
      //   bar
      xr is XR.Property && xr.of is XR.Product -> {
        val fields = (xr.of as XR.Product).fields.toMap()
        invoke(fields[xr.name]!!)
      }

      // case FunctionApply(Function(params, body), values) =>
      xr is XR.FunctionApply && xr.function is XR.Labels.Function -> {
        val params = xr.function.params
        val body = xr.function.body
        val applyArgs = xr.args
        // 1. Collect all idents in the Apply.args e.g. Apply(foo('a, 'b, 'c){...}, [a.x, b.y, d....]) that are also
        // somewhere inside the input args of the function i.e. `a.x` and `b.y`. These are all the "conflicts."
        // Make a map `a` to `tmp_a`, `b` to `tmp_b` etc...
        val conflicts = applyArgs
          .flatMap { CollectXR.byType<XR.Ident>(it) }
          .map { i: XR.Ident ->
            i to XR.Ident("tmp_${i.name}", i.type, i.loc)
          }
          .toMap()
        // 2. Then get all args that are in the function body Apply(foo('a, 'b, 'c){...'a..., ...'b..., ...'c...}, [a.x, b.y, d...])
        // i.e. `'a`, and `'b` that must be replaced with the temps tmp_a, tmp_b that we previously created
        // and make a list of the conflict-keys to the "temps":
        //   List(temp_a, temp_b, c)
        val newParams =
          params.map { p -> conflicts.getOrElse(p, { p }) }
        // finally create the parameter map *back* to the FunctionApply variables i.e:
        //   Map(tmp_a->a, tmp_b->b, 'c->c etc...)
        //
        // The 'c->c part is important because if the function is generic
        // on the parameter 'c (e.g. foo[C](a,b,c:C) then the argument
        // that is applied to it will have a more specific type than the genric.
        // That is why it is important to take the more specific XRType from
        // c as opposed to 'c.
        val applicationMap =
          newParams.zip(applyArgs)

        // 3. Reduce Map('a->tmp_a and b->tmp_b in the foo-function body in:
        // Apply(foo('a,'b,'c) body={...a..., ...b...}, [a, b, d...]) -> (turning it into...)
        //   Apply(foo('a,'b,'c) body={...tmp_a..., ...tmp_b..., }, [a, b, d...])
        // This `body` variable is what becomes `bodyr`
        // Note that at this point we just need body of the FunctionApply(Function(body), ...)
        // we can already throw away the FunctionApply and Function nodes.
        val bodyr = BetaReduce(mapOf<XR.Ident, XR.Ident>() + conflicts).invoke(body)
        // 4. Finally, reduce tmp_a->a, tmp_b->b, 'c->c in the foo-function body in:
        // body={...tmp_a..., ...tmp_b...} -> (turning it into...)
        //   body={...a..., ...b..., }
        // Thus we have reduced FunctionApply(Function(params,body), args)
        // to just a beta-reduced body.
        invoke(BetaReduce(map + applicationMap).invoke(bodyr))
      }

      // Reduce a block and all variables inside to a single statement
      xr is XR.Block -> with (xr) {
        // Walk through the statements, last to the first (in this case 'output' is the last statement in the block)
        val output =
          stmts.reversed()
            // Important to go down to invoke(XR) recursively here since we are reducing
            //   XR.Block -> XR.Statement and that is only possible on the root-level
            .fold(Pair(mapOf<QueryOrExpression, QueryOrExpression>(), output)) { (map, stmt), line ->
              // Beta-reduce the statements from the end to the beginning
              val reduct: XR.Variable = BetaReduce(map)(line)
              // If the beta reduction is a some 'val x=t', add x->t to the beta reductions map
              val newMap = map + Pair(reduct.name, reduct.rhs)
              val newStmt = BetaReduce(newMap).invoke(stmt) // Need to widen for the beta-reduction to be right!
              Pair(newMap, newStmt)
            }.second
        invoke(output)
      }

      xr is XR.Labels.Function -> {
        fun mapParams(params: List<XR.Ident>) =
          params.map { p ->
            when (val v = map.get(p)) {
              // Not null and its an identifier
              is XR.Ident -> v
              else -> p
            }
          }
        return with(xr) {
          when(this) {
            is XR.FunctionN -> {
              val newParams = mapParams(params)
              FunctionN.cs(newParams, BetaReduce(map + params.zip(newParams))(body))
            }
            else -> throw IllegalStateException("Function must be Function1 or FunctionN")
          }
        }
      }

      else -> null
    }
  }

  override fun invoke(xr: XR.Query): XR.Query =
    replaceAtHead(xr)?.let {
      when(it) {
        is XR.Query -> it
        is XR.Expression -> XR.ExprToQuery(it)
      }
    } ?: run {
      with(xr) {
        when (this) {
          is XR.Filter -> Filter.cs(invoke(head), id, BetaReduce(map - id)(body))
          is XR.Map -> Map.cs(invoke(head), id, BetaReduce(map - id)(body))
          is XR.FlatMap -> FlatMap.cs(invoke(head), id, BetaReduce(map - id)(body))
          is XR.ConcatMap -> ConcatMap.cs(invoke(head), id, BetaReduce(map - id)(body))
          is XR.SortBy -> SortBy.cs(invoke(head), id, BetaReduce(map - id)(this.criteria), ordering)
          is XR.GroupByMap -> GroupByMap.cs(invoke(head), byAlias, BetaReduce(map - byAlias)(this.byBody), mapAlias, BetaReduce(map - mapAlias)(this.mapBody))
          is XR.FlatJoin -> FlatJoin.cs(invoke(head), id, BetaReduce(map - id)(on))
          is XR.DistinctOn -> DistinctOn.cs(invoke(head), id, BetaReduce(map - id)(by))
          // is XR.Take, is XR.Entity, is XR.Drop, is XR.Union, is XR.UnionAll, is XR.Aggregation, is XR.Distinct, is XR.Nested
          else -> super.invoke(this)
        }
      }
    }

  companion object {
    fun ofXR(ast: XR, vararg t: Pair<QueryOrExpression, QueryOrExpression>): XR =
      ofXR(ast, TypeBehavior.SubstituteSubtypes, EmptyProductTypeBehavior.Ignore, *t)

    fun ofXR(ast: XR, typeBehavior: TypeBehavior, emptyBehavior: EmptyProductTypeBehavior, vararg t: Pair<QueryOrExpression, QueryOrExpression>): XR =
      invokeTyped(ast, t.toMap(), typeBehavior, emptyBehavior, { be, ir -> be.invoke(ir) })

    operator fun invoke(ast: QueryOrExpression, vararg t: Pair<QueryOrExpression, QueryOrExpression>): QueryOrExpression =
      invoke(ast, TypeBehavior.SubstituteSubtypes, EmptyProductTypeBehavior.Ignore, *t)

    fun ReplaceWithReduction(ast: QueryOrExpression, vararg t: Pair<QueryOrExpression, QueryOrExpression>): QueryOrExpression =
      invoke(ast, TypeBehavior.ReplaceWithReduction, EmptyProductTypeBehavior.Ignore, *t)

    operator fun invoke(ast: QueryOrExpression, typeBehavior: TypeBehavior, vararg t: Pair<QueryOrExpression, QueryOrExpression>): QueryOrExpression =
      invokeTyped(ast, t.toMap(), typeBehavior, EmptyProductTypeBehavior.Ignore, { be, ir -> be.invoke(ir) })

    operator fun invoke(ast: QueryOrExpression, typeBehavior: TypeBehavior, emptyBehavior: EmptyProductTypeBehavior, vararg t: Pair<QueryOrExpression, QueryOrExpression>): QueryOrExpression =
      invokeTyped(ast, t.toMap(), typeBehavior, emptyBehavior, { be, ir -> be.invoke(ir) })

    fun ofQuery(ast: XR.Query, typeBehavior: TypeBehavior, emptyBehavior: EmptyProductTypeBehavior, vararg t: Pair<QueryOrExpression, QueryOrExpression>): XR.Query =
      invokeTyped(ast, t.toMap(), typeBehavior, emptyBehavior, { be, ir -> be.invoke(ir) })

    fun ofQuery(ast: XR.Query, typeBehavior: TypeBehavior, vararg t: Pair<QueryOrExpression, QueryOrExpression>): XR.Query =
      invokeTyped(ast, t.toMap(), typeBehavior, EmptyProductTypeBehavior.Ignore, { be, ir -> be.invoke(ir) })

    fun ofQuery(ast: XR.Query, vararg t: Pair<QueryOrExpression, QueryOrExpression>): XR.Query =
      invokeTyped(ast, t.toMap(), TypeBehavior.SubstituteSubtypes, EmptyProductTypeBehavior.Ignore, { be, ir -> be.invoke(ir) })


    internal fun <X: XR> invokeTyped(
      ast: X,
      replacements: Map<QueryOrExpression, QueryOrExpression>,
      typeBehavior: TypeBehavior,
      emptyBehavior: EmptyProductTypeBehavior,
      astLevelInvoker: (BetaReduction, X) -> X
    ): X {
      val reducedAst = astLevelInvoker(BetaReduction(replacements, typeBehavior, emptyBehavior), ast)
      if (typeBehavior == TypeBehavior.SubstituteSubtypes) checkTypes(ast, replacements.toList(), emptyBehavior)
      return when {
        // Since it is possible for the AST to match but the match not be exactly the same (e.g.
        // if a AST property not in the product cases comes up (e.g. Ident's quat.rename etc...) make
        // sure to return the actual AST that was matched as opposed to the one passed in.
        reducedAst == ast -> reducedAst
        // Perform an additional beta reduction on the reduced XR since it may not have been fully reduced yet
        else -> invokeTyped<X>(reducedAst, mapOf<QueryOrExpression, QueryOrExpression>(), typeBehavior, emptyBehavior, astLevelInvoker)
      }
    }

    private fun checkTypes(body: XR, replacements: List<Pair<XR, XR>>, emptyBehavior: EmptyProductTypeBehavior) =
      replacements.forEach { (orig, rep) ->
        val repType    = rep.type
        val origType   = orig.type
        val leastUpper = repType.leastUpperType(origType)
        if (emptyBehavior == EmptyProductTypeBehavior.Fail) {
          when(leastUpper) {
            is XRType.Product ->
              if (leastUpper.fields.isEmpty())
                throw IllegalArgumentException(
                  "Reduction of $origType and $repType yielded an empty Quat product!\n" +
                    "That means that they represent types that cannot be reduced!"
                )
            // Otherwise no error, do nothing
            else -> {}
          }
        }
        if (leastUpper == null)
          throw IllegalArgumentException(
            "Cannot beta reduce [this:$rep <- with:$orig] within [$body] because ${repType.shortString()} of [this:${rep}] is not a subtype of ${origType.shortString()} of [with:${orig}]"
          )
      }
  }

}
