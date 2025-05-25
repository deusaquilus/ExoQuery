package io.exoquery.xr

//import io.exoquery.util.DebugMsg
import io.exoquery.xr.XR.*
import io.exoquery.xr.XR.Map // Make sure to explicitly have this import or Scala will use Map the collection
import io.exoquery.xr.copy.*

/**
 * Normally being able to access all XR elements in a StatefulTransformer
 * requires overriding: invoke(Expression), invoke(Query), invoke(Branch),
 * and invoke(Variable) and invoke(XR) for the other possibilities.
 * This is because in the ExoQuery transformation model
 * invoke calls on a node e.g. invoke(mapQuery.a) directly go to the invoke
 * of that node type invoke(Query) as opposed to the root-level method
 * invoke(XR). This stateful transformer extension adds an additional method
 * `root(XR)` which all elements will go through before going to the corresponding
 * branch-level invoke functions.
 */
interface StatefulTransformerSingleRoot<T> : StatefulTransformer<T> {
  fun <X> root(xr: X): Pair<X, StatefulTransformerSingleRoot<T>> where X : XR

  private fun invokeSuper(xr: Expression): Pair<Expression, StatefulTransformer<T>> = super.invoke(xr)
  override fun invoke(xr: Expression): Pair<Expression, StatefulTransformer<T>> {
    val (a, stateA) = root(xr)
    return stateA.invokeSuper(a)
  }

  private fun invokeSuper(xr: Query): Pair<Query, StatefulTransformer<T>> = super.invoke(xr)
  override fun invoke(xr: Query): Pair<Query, StatefulTransformer<T>> {
    val (a, stateA) = root(xr)
    return stateA.invokeSuper(a)
  }

  private fun invokeSuper(xr: Branch): Pair<Branch, StatefulTransformer<T>> = super.invoke(xr)
  override fun invoke(xr: Branch): Pair<Branch, StatefulTransformer<T>> {
    val (a, stateA) = root(xr)
    return stateA.invokeSuper(a)
  }

  private fun invokeSuper(xr: Variable): Pair<Variable, StatefulTransformer<T>> = super.invoke(xr)
  override fun invoke(xr: Variable): Pair<Variable, StatefulTransformer<T>> {
    val (a, stateA) = root(xr)
    return stateA.invokeSuper(a)
  }

  private fun invokeSuper(xr: XR): Pair<XR, StatefulTransformer<T>> = super.invoke(xr)
  override fun invoke(xr: XR): Pair<XR, StatefulTransformer<T>> {
    val (a, stateA) = root(xr)
    return stateA.invokeSuper(a)
  }
}

//data class DebugDump(val info: MutableList<DebugMsg> = mutableListOf()){
//  fun dump(str: String) = info.add(DebugMsg.Fragment(str))
//  companion object {
//    operator fun invoke(vararg msg: DebugMsg) = DebugDump(msg.toMutableList())
//  }
//}

interface StatefulTransformer<T> {
  val state: T

  operator fun invoke(xr: XR): Pair<XR, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is Expression -> invoke(this)
        is Query -> invoke(this)
        is Branch -> invoke(this)
        is Variable -> invoke(this)
        is Action -> invoke(this)
        is Assignment -> invoke(this)
        is Batching -> invoke(this)
      }
    }

  operator fun invoke(xr: XR.Expression): Pair<XR.Expression, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is BinaryOp -> {
          val (a, stateA) = invoke(a)
          val (b, stateB) = stateA.invoke(b)
          BinaryOp.cs(a, op, b) to stateB
        }
        is FunctionN -> {
          val (bodyA, stateBody) = invoke(body)
          FunctionN.cs(params, bodyA) to stateBody
        }
        is FunctionApply -> {
          val (functionA, stateA) = invoke(function)
          val (argsA, stateB) = stateA.applyList(args) { t, v -> t.invoke(v) }
          FunctionApply.cs(functionA, argsA) to stateB
        }
        is Property ->
          invoke(this)
        is UnaryOp -> {
          val (exprA, stateA) = invoke(expr)
          UnaryOp.cs(op, exprA) to stateA
        }
        is When -> {
          val (branchesA, stateA) = applyList(branches) { t, v -> t.invoke(v) }
          val (orElseA, stateB) = stateA.invoke(orElse)
          When.cs(branchesA, orElseA) to stateB
        }
        is Product -> {
          val (keys, values) = fields.unzip()
          val (valuesA, stateA) = applyList(values) { t, v -> t.invoke(v) }
          Product.cs(keys zip valuesA) to stateA
        }
        // Free can be both a Query and Expression
        is Free -> {
          val (paramsA, stateA) = applyList(params) { t, v -> t.invoke(v) }
          Free.cs(parts, paramsA) to stateA
        }
        is MethodCall -> invoke(this)
        is GlobalCall -> invoke(this)
        is QueryToExpr -> {
          val (headA, stateA) = invoke(head)
          QueryToExpr.cs(headA) to stateA
        }
        is Window -> invoke(this)
        is Block -> invoke(this)
        is Const -> this to this@StatefulTransformer
        is Ident -> invokeIdent(this)
        is Const.Null -> this to this@StatefulTransformer
        is TagForParam -> this to this@StatefulTransformer
        is TagForSqlExpression -> this to this@StatefulTransformer
      }
    }

  // Need to have a specific property-invoke because Assignment uses it directly
  operator fun invoke(xr: XR.Property): Pair<XR.Property, StatefulTransformer<T>> =
    with(xr) {
      val (ofA, stateA) = invoke(of)
      Property.cs(ofA, name) to stateA
    }

  operator fun invoke(xr: XR.Entity): Pair<XR.Entity, StatefulTransformer<T>> =
    with(xr) {
      this to this@StatefulTransformer
    }

  operator fun invoke(xr: XR.Query): Pair<XR.Query, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is FlatMap -> {
          val (aA, stateA) = invoke(head)
          val (bA, stateB) = stateA.invoke(body)
          FlatMap.cs(aA, id, bA) to stateB
        }
        is Map -> {
          val (aA, stateA) = invoke(head)
          val (bA, stateB) = stateA.invoke(body)
          Map.cs(aA, id, bA) to stateB
        }
        is Entity -> invoke(this)
        is Filter -> {
          val (aA, stateA) = invoke(head)
          val (bA, stateB) = stateA.invoke(body)
          Filter.cs(aA, id, bA) to stateB
        }
        is Union -> {
          val (aA, stateA) = invoke(a)
          val (bA, stateB) = stateA.invoke(b)
          Union.cs(aA, bA) to stateB
        }
        is UnionAll -> {
          val (aA, stateA) = invoke(a)
          val (bA, stateB) = stateA.invoke(b)
          UnionAll.cs(aA, bA) to stateB
        }
        is Distinct -> {
          val (queryA, stateA) = invoke(head)
          Distinct.cs(queryA) to stateA
        }
        is DistinctOn -> {
          val (queryA, stateA) = invoke(head)
          val (byA, stateB) = stateA.invoke(by)
          DistinctOn.cs(queryA, id, byA) to stateB
        }
        is Drop -> {
          val (queryA, stateA) = invoke(head)
          val (numB, stateB) = stateA.invoke(num)
          Drop.cs(queryA, numB) to stateB
        }
        is SortBy -> {
          val (queryA, stateA) = invoke(head)
          val (criteriaA, stateB) = stateA.applyList(criteria) { t, v -> t.invoke(v) }
          SortBy.cs(queryA, id, criteriaA) to stateB
        }
        is Take -> {
          val (queryA, stateA) = invoke(head)
          val (numB, stateB) = stateA.invoke(num)
          Take.cs(queryA, numB) to stateB
        }
        is FlatJoin -> {
          val (aA, stateA) = invoke(head)
          val (onA, stateB) = stateA.invoke(on)
          FlatJoin.cs(aA, id, onA) to stateB
        }
        is FlatGroupBy -> {
          val (aA, stateA) = invoke(by)
          FlatGroupBy.cs(aA) to stateA
        }
        is FlatSortBy -> {
          val (aA, stateA) = applyList(criteria) { t, v -> t.invoke(v) }
          FlatSortBy.cs(aA) to stateA
        }
        is FlatFilter -> {
          val (aA, stateA) = invoke(by)
          FlatFilter.cs(aA) to stateA
        }
        is ConcatMap -> {
          val (aA, stateA) = invoke(head)
          val (bA, stateB) = stateA.invoke(body)
          ConcatMap.cs(aA, id, bA) to stateB
        }
        is Nested -> {
          val (queryA, stateA) = invoke(head)
          Nested.cs(queryA) to stateA
        }
        // Free can be both a Query and Expression
        is Free -> {
          val (paramsA, stateA) = applyList(params) { t, v -> t.invoke(v) }
          Free(parts, paramsA, pure, transparent, type, loc) to stateA
        }
        is ExprToQuery -> {
          val (headA, stateA) = invoke(head)
          ExprToQuery.cs(headA) to stateA
        }
        is TagForSqlQuery -> this to this@StatefulTransformer
        is CustomQueryRef -> {
          val (cust, state) = customQuery.handleStatefulTransformer(this@StatefulTransformer)
          CustomQueryRef.cs(cust) to state
        }
        is FunctionApply -> invoke(this)
        is Ident -> invokeIdent(this)
        is GlobalCall -> invoke(this)
        is MethodCall -> invoke(this)
      }
    }

  fun invokeIdent(xr: Ident): Pair<Ident, StatefulTransformer<T>> =
    with(xr) {
      this to this@StatefulTransformer
    }

  operator fun invoke(xr: XR.OrderField): Pair<XR.OrderField, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is XR.OrderField.By -> {
          val (fieldA, stateA) = invoke(field)
          XR.OrderField.By(fieldA, ordering) to stateA
        }
        is XR.OrderField.Implicit -> {
          val (fieldA, stateA) = invoke(field)
          XR.OrderField.Implicit(fieldA) to stateA
        }
      }
    }

  operator fun invoke(xr: XR.Window): Pair<XR.Window, StatefulTransformer<T>> =
    with(xr) {
      val (partitionByA, stateA) = applyList(partitionBy) { t, v -> t.invoke(v) }
      val (orderByA, stateB) = stateA.applyList(orderBy) { t, v -> t.invoke(v) }
      val (overA, stateC) = stateB.invoke(over)
      Window.cs(partitionByA, orderByA, overA) to stateC
    }

  operator fun invoke(xr: XR.U.QueryOrExpression): Pair<XR.U.QueryOrExpression, StatefulTransformer<T>> =
    when (xr) {
      is XR.Query -> invoke(xr)
      is XR.Expression -> invoke(xr)
    }

  operator fun invoke(xr: XR.FunctionN): Pair<XR.FunctionN, StatefulTransformer<T>> =
    with(xr) {
      val (bodyA, stateA) = invoke(body)
      FunctionN.cs(params, bodyA) to stateA
    }

  operator fun invoke(xr: XR.GlobalCall): Pair<XR.GlobalCall, StatefulTransformer<T>> =
    with(xr) {
      val (argsA, stateA) = applyList(args) { t, v -> t.invoke(v) }
      GlobalCall.cs(argsA) to stateA
    }

  operator fun invoke(xr: XR.MethodCall): Pair<XR.MethodCall, StatefulTransformer<T>> =
    with(xr) {
      val (headA, stateA) = invoke(head)
      val (argsA, stateB) = stateA.applyList(args) { t, v -> t.invoke(v) }
      MethodCall.cs(headA, argsA) to stateB
    }

  operator fun invoke(xr: XR.FunctionApply): Pair<XR.FunctionApply, StatefulTransformer<T>> =
    with(xr) {
      val (functionA, stateA) = invoke(function)
      val (argsA, stateB) = applyList(args) { t, v -> t.invoke(v) }
      FunctionApply.cs(functionA, argsA) to stateB
    }

  operator fun invoke(xr: XR.Block): Pair<XR.Block, StatefulTransformer<T>> =
    with(xr) {
      val (stmtsA, stateA) = applyList(stmts) { t, v -> t.invoke(v) }
      val (outputA, stateB) = stateA.invoke(output)
      Block.cs(stmtsA, outputA) to stateB
    }

  operator fun invoke(xr: XR.Branch): Pair<XR.Branch, StatefulTransformer<T>> =
    with(xr) {
      val (condA, stateA) = invoke(cond)
      val (thenA, stateB) = stateA.invoke(then)
      Branch.cs(condA, thenA) to stateB
    }

  operator fun invoke(xr: XR.Variable): Pair<XR.Variable, StatefulTransformer<T>> =
    with(xr) {
      val (rhsA, stateA) = invoke(rhs)
      Variable.cs(name, rhsA) to stateA
    }

  // Need to have a specifc insert-invoke because OnConflict uses it directly
  operator fun invoke(xr: XR.Insert): Pair<XR.Insert, StatefulTransformer<T>> =
    with(xr) {
      val (at, att) = invoke(query)
      val (bt, btt) = att.applyList(assignments) { t, v -> t.invoke(v) }
      val (ct, ctt) = btt.applyList(exclusions) { t, v -> t.invoke(v) }
      Insert.cs(at, alias, bt, ct) to btt
    }

  operator fun invoke(xr: XR.OnConflict.Target): Pair<XR.OnConflict.Target, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is XR.OnConflict.Target.NoTarget -> this to this@StatefulTransformer
        is XR.OnConflict.Target.Properties -> {
          val (propsA, stateA) = applyList(props) { t, v -> t.invoke(v) }
          XR.OnConflict.Target.Properties(propsA) to stateA
        }
      }
    }

  operator fun invoke(xr: XR.OnConflict.Resolution): Pair<XR.OnConflict.Resolution, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is XR.OnConflict.Resolution.Ignore -> this to this@StatefulTransformer
        is XR.OnConflict.Resolution.Update -> {
          val (a, stateA) = applyList(assignments) { t, v -> t.invoke(v) }
          XR.OnConflict.Resolution.Update(excludedId, existingParamIdent, a) to stateA
        }
      }
    }

  operator fun invoke(xr: XR.Batching): Pair<XR.Batching, StatefulTransformer<T>> =
    with(xr) {
      val (at, att) = invoke(action)
      Batching.cs(xr.alias, at) to att
    }

  operator fun invoke(xr: XR.Action): Pair<XR.Action, StatefulTransformer<T>> =
    with(xr) {
      when (this) {
        is Insert -> invoke(this)
        is Update -> {
          val (at, att) = invoke(query)
          val (bt, btt) = att.applyList(assignments) { t, v -> t.invoke(v) }
          val (ct, ctt) = btt.applyList(exclusions) { t, v -> t.invoke(v) }
          Update.cs(at, alias, bt, ct) to btt
        }
        is Delete -> {
          val (at, att) = invoke(query)
          Delete.cs(at, alias) to att
        }
        is OnConflict -> {
          val (at, att) = invoke(insert)
          val (bt, btt) = att.invoke(target)
          val (ct, ctt) = btt.invoke(resolution)
          OnConflict.cs(at, bt, ct) to ctt
        }
        is FilteredAction -> {
          val (at, att) = invoke(action)
          val (bt, btt) = att.invoke(filter)
          // TODO need to think about consequences of casting here
          FilteredAction.cs(at as XR.U.CoreAction, alias, bt) to btt
        }
        is Returning -> {
          val (at, att) = invoke(action)
          val (bt, btt) = invoke(kind)
          Returning.cs(at, bt) to btt
        }
        is Free -> {
          val (paramsA, stateA) = applyList(params) { t, v -> t.invoke(v) }
          Free.cs(parts, paramsA) to stateA
        }
        is TagForSqlAction -> this to this@StatefulTransformer
      }
    }

  operator fun invoke(xr: XR.Returning.Kind): Pair<XR.Returning.Kind, StatefulTransformer<T>> =
    when (xr) {
      is Returning.Kind.Expression -> {
        val (at, att) = invokeIdent(xr.alias)
        val (bt, btt) = invoke(xr.expr)
        Returning.Kind.Expression(at, bt) to btt
      }
      is Returning.Kind.Keys -> {
        val (at, att) = applyList(xr.keys) { t, v -> t.invoke(v) }
        Returning.Kind.Keys(xr.alias, at) to att
      }
    }

  operator fun invoke(xr: XR.Assignment): Pair<XR.Assignment, StatefulTransformer<T>> =
    with(xr) {
      val (bt, btt) = invoke(property)
      val (ct, ctt) = btt.invoke(value)
      Assignment.cs(bt, ct) to ctt
    }

  fun <U, R> applyList(list: List<U>, f: (StatefulTransformer<T>, U) -> Pair<R, StatefulTransformer<T>>): Pair<List<R>, StatefulTransformer<T>> {
    val (newList, transformer) =
      list.fold(Pair(mutableListOf<R>(), this)) { (values, t), v ->
        val (vt, vtt) = f(t, v)
        values += vt
        Pair(values, vtt)
      }

    return Pair(newList.toList(), transformer)
  }
}
