package io.exoquery.xr

import io.decomat.*
import io.exoquery.ActionKind
import io.exoquery.BID
import io.exoquery.xr.XR.BinaryOp

// Can't use || or && chars because they don't work with linuxX64
infix fun XR.Expression._Or_(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, OP.Or, other, this.loc)
infix fun XR.Expression._And_(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, OP.And, other, this.loc)
infix fun XR.Expression._EqEq_(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, OP.EqEq, other, this.loc)
infix fun XR.Expression._NotEq_(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, OP.NotEq, other, this.loc)
infix fun XR.Expression._StrPlus_(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, OP.StrPlus, other, this.loc)
infix fun XR.Expression._Plus_(other: XR.Expression): XR.BinaryOp = XR.BinaryOp(this, OP.Plus, other, this.loc)

inline fun <reified R> Is.Companion.of(vararg possibilities: R): Is<R> = Is.PredicateAs(io.decomat.Typed<R>(), { possibilities.contains(it) })


fun isOperatorOnExpressions(op: BinaryOperator) =
  when (op) {
    OP.Or, OP.And -> true
    else -> false
  }

fun isOperatorOnValues(op: BinaryOperator) =
  when (op) {
    OP.EqEq, OP.NotEq, OP.Gt, OP.GtEq, OP.Lt, OP.LtEq -> true
    else -> false
  }

fun BinaryOp.oneSideIs(value: XR.Expression): Boolean =
  this.a == value || this.b == value

fun XR.Query.swapTags(tagMap: Map<BID, BID>): XR.Query =
  SwapTagsTransformer(tagMap).invoke(this)

fun XR.Expression.swapTags(tagMap: Map<BID, BID>): XR.Expression =
  SwapTagsTransformer(tagMap).invoke(this)

fun XR.Action.swapTags(tagMap: Map<BID, BID>): XR.Action =
  SwapTagsTransformer(tagMap).invoke(this)

fun XR.Batching.swapTags(tagMap: Map<BID, BID>): XR.Batching =
  SwapTagsTransformer(tagMap).invoke(this)

internal class SwapTagsTransformer(tagMap: Map<BID, BID>) : TransformXR(
  transformExpression = {
    with(it) {
      when (this) {
        is XR.TagForParam ->
          tagMap.get(id)?.let { XR.TagForParam.csf(it)(this) }
        is XR.TagForSqlExpression -> tagMap.get(id)?.let { XR.TagForSqlExpression.csf(it)(this) }
        // Transform XR expects a "partial function" i.e. for the user to return 'null'
        // when there is no transform for the given tree. Then it knows to recurse further in.
        else -> null
      }
    }
  },
  transformQuery = {
    with(it) {
      when (this) {
        is XR.TagForSqlQuery -> tagMap.get(id)?.let { XR.TagForSqlQuery.csf(it)(this) }
        else -> null
      }
    }
  },
  transformAction = {
    with(it) {
      when (this) {
        is XR.TagForSqlAction -> tagMap.get(id)?.let { XR.TagForSqlAction.csf(it)(this) }
        else -> null
      }
    }
  }
)

fun XR.Map.containsImpurities(): Boolean =
  CollectXR(this) {
    with(it) {
      when {
        this is XR.GlobalCall && !this.isPure() -> this
        this is XR.MethodCall && !this.isPure() -> this
        this is XR.Free && !this.pure -> this
        else -> null
      }
    }
  }.isNotEmpty()


/*
def unapply(ast: Ast) =
CollectAst(ast) {
  case agg: Aggregation          => agg
    case inf: Free if (!inf.pure) => inf
}.nonEmpty
 */

// case(When.SingleBranch[Is(), Is()])

val XR.When.Companion.CondThen get() = CondThen()

class CondThen {
  operator fun <AP : Pattern<A>, A : XR.Expression, BP : Pattern<XR.Expression>> get(cond: AP, then: BP) =
    customPattern2("When.SingleBranch", cond, then) { it: XR.When ->
      with(it) {
        when {
          this.branches.size == 1 -> Components2(this.branches[0].cond, this.branches[0].then)
          else -> null
        }
      }
    }
}

object PairOf {
  operator fun <AP : Pattern<A>, BP : Pattern<B>, A, B> get(a: AP, b: BP) =
    customPattern2("PairOf", a, b) { it: Pair<A, B> ->
      Components2(it.first, it.second)
    }
}

val XR.When.Companion.CondThenElse get() = CondThenElse()

class CondThenElse {
  operator fun <AP : Pattern<A>, A : XR.Expression, BP : Pattern<Pair<XR.Expression, XR.Expression>>> get(cond: AP, thenElse: BP) =
    customPattern2("When.CondThenElse", cond, thenElse) { it: XR.When ->
      with(it) {
        when {
          this.branches.size == 1 -> Components2(this.branches[0].cond, this.branches[0].then to this.orElse)
          else -> null
        }
      }
    }
}

// TODO definitely want to early-exist this and make more efficient
fun XR.contains(other: XR) =
  CollectXR(this) {
    if (it == other) it else null
  }.isNotEmpty()

val XR.BinaryOp.Companion.ProductNullCheck get() = ProductNullCheck()

class ProductNullCheck {
  operator fun <AP : Pattern<XR.Expression>> get(x: AP) =
    customPattern1("When.ProductNullCheck", x) { it: XR.BinaryOp ->
      on(it).match(
        case(XR.BinaryOp.OneSideIs[IsTypeProduct(), Is.of(OP.EqEq, OP.NotEq), NullXR()]).thenThis { a, b ->
          Components1(a)
        }
      )
    }
}

val XR.When.Companion.IfNullThenNull get() = IfNullThenNull()

class IfNullThenNull {
  operator fun <AP : Pattern<XR.Expression>, BP : Pattern<XR.Expression>> get(notNull: AP, orElse: BP) =
    customPattern2("When.IfNullThenNull", notNull, orElse) { it: XR.When ->
      on(it).match(
        case(XR.When.IfNull[Is(), Is()]).thenThis { notNullSide, (then, orElse) ->
          if (then is XR.Const.Null)
            Components2(notNullSide, orElse)
          else
            null
        }
      )
    }
}

val XR.When.Companion.IfNull get() = IfNull()

class IfNull {
  operator fun <AP : Pattern<XR.Expression>, BP : Pattern<Pair<XR.Expression, XR.Expression>>> get(notNullSide: AP, thenElse: BP) =
    customPattern2("When.IfNull", notNullSide, thenElse) { it: XR.When ->
      on(it).match(
        case(XR.When.CondThen[XR.BinaryOp.OneSideIs[NullXR(), Is<OP.EqEq>(), Is()], Is()]).thenThis { (nullSide, notNullSide), thenSide ->
          Components2(notNullSide, thenSide to this.orElse)
        }
      )
    }
}


val XR.When.Companion.IfCondThenFalseOrTrue get() = IfCondThenFalseOrTrue()

class IfCondThenFalseOrTrue {
  operator fun <AP : Pattern<XR.Expression>> get(x: AP) =
    customPattern1("When.IfNullThenFalseOrTrue", x) { it: XR.When ->
      on(it).match(
        case(XR.When.CondThenElse[Is(), Is()]).thenThis { cond, (thenSide, elseSide) ->
          if (thenSide == XR.Const.False && elseSide == XR.Const.True)
            Components1(cond)
          else
            null
        }
      )
    }
}


val XR.When.Companion.IfCondThenTrueOrFalse get() = IfCondThenTrueOrFalse()

class IfCondThenTrueOrFalse {
  operator fun <AP : Pattern<XR.Expression>> get(x: AP) =
    customPattern1("When.IfNullThenTrueOrFalse", x) { it: XR.When ->
      on(it).match(
        case(XR.When.CondThenElse[Is(), Is()]).thenThis { cond, (thenSide, elseSide) ->
          if (thenSide == XR.Const.True && elseSide == XR.Const.False)
            Components1(cond)
          else
            null
        }
      )
    }
}

val XR.When.Companion.NullIfNullOrX get() = NullIfNullOrX()

class NullIfNullOrX {
  operator fun <AP : Pattern<XR.Expression>, BP : Pattern<XR.Expression>> get(notNullSide: AP, elseClause: BP) =
    customPattern1("When.NullIfNullOrX", notNullSide) { it: XR.When ->
      // if (x == null) null else x
      on(it).match(
        case(XR.When.CondThen[XR.BinaryOp.OneSideIs[NullXR(), Is<OP.EqEq>(), Is()], NullXR()]).thenThis { (nullSide, notNullSide), _ ->
          if (notNullSide == this.orElse)
            Components1(notNullSide)
          else
            null
        }
      )
    }
}

operator fun <OP : Pattern<UnaryOperator>, AP : Pattern<A>, A : XR.Expression> XR.UnaryOp.Companion.get(op: OP, expr: AP) =
  customPattern1("When.UnaryOp", expr) { it: XR.UnaryOp ->
    if (op.matchesAny(it.op) && expr.matchesAny(it.expr)) {
      Components1(it.expr)
    } else {
      null
    }
  }

operator fun <AP : Pattern<XR.Expression>, MP : Pattern<OP>, BP : Pattern<XR.Expression>> XR.BinaryOp.Companion.get(a: AP, m: MP, b: BP) =
  customPattern2("BinaryOp3", a, b) { it: XR.BinaryOp ->
    if (m.matchesAny(it.op) && a.matchesAny(it.a) && b.matchesAny(it.b)) {
      Components2(it.a, it.b)
    } else {
      null
    }
  }


val XR.BinaryOp.Companion.OneSideIs get() = OneSideIs()

class OneSideIs {
  operator fun <OneSide : Pattern<XR.Expression>, OP : Pattern<BinaryOperator>, OtherSide : Pattern<XR.Expression>> get(oneSide: OneSide, op: OP, otherSide: OtherSide) =
    customPattern2("When.OneSideIs", oneSide, otherSide) { it: XR.BinaryOp ->
      if (op.matchesAny(it.op)) {
        if (oneSide.matchesAny(it.a)) {
          Components2(it.a, it.b)
        } else if (oneSide.matchesAny(it.b)) {
          Components2(it.b, it.a)
        } else {
          null
        }
      } else {
        null
      }
    }
}

val XR.When.Companion.XIsNotNullOrNull get() = XIsNotNullOrNull()

class XIsNotNullOrNull {
  operator fun <AP : Pattern<XR.Expression>> get(x: AP) =
    customPattern1("When.XIsNotNullOrNull", x) { it: XR.When ->
      on(it).match(
        // if (x != null) x else null -> x
        case(XR.When.CondThen[XR.BinaryOp.OneSideIs[NullXR(), Is<OP.NotEq>(), Is()], Is()]).thenIfThis { (isNull, otherSide), thenTerm -> NullXR().matchesAny(orElse) && thenTerm.contains(otherSide) }.thenThis { _, then ->
          Components1(then)
        }
      )
    }
}

fun IsTypeProduct() = Is<XR.Expression> { it.type is XRType.Product }
fun NullXR() = Is<XR.Expression> { it is XR.Const.Null }

val XR.BinaryOp.Companion.NotEq get() = BinaryOpNotEq()

class BinaryOpNotEq {
  operator fun <AP : Pattern<XR.Expression>, BP : Pattern<XR.Expression>> get(x: AP, y: BP) =
    customPattern2("When.BinaryOpNotEq", x, y) { it: XR.BinaryOp ->
      on(it).match(
        case(XR.BinaryOp[Is(), Is()]).thenThis { a, op, b ->
          if (op is OP.NotEq) Components2(a, b) else null
        }
      )
    }
}

val XR.BinaryOp.Companion.EqEq get() = BinaryOpEqEq()

class BinaryOpEqEq {
  operator fun <AP : Pattern<XR.Expression>, BP : Pattern<XR.Expression>> get(x: AP, y: BP) =
    customPattern2("When.BinaryOpEqEq", x, y) { it: XR.BinaryOp ->
      on(it).match(
        case(XR.BinaryOp[Is(), Is()]).thenThis { a, op, b ->
          if (op is OP.EqEq) Components2(a, b) else null
        }
      )
    }
}


val XR.Map.Companion.DistinctHead get() = DistinctHeadMap()

class DistinctHeadMap() {
  operator fun <AP : Pattern<Q>, Q : XR.Query, BP : Pattern<XR.Expression>> get(x: AP, y: BP) =
    customPattern2M("XR.Map.DistinctHead", x, y) { it: XR.Map ->
      with(it) {
        when {
          this.head is XR.Distinct -> Components2M(this.head.head, this.id, this.body)
          else -> null
        }
      }
    }
}


val XR.SortBy.Companion.DistinctHead get() = DistinctHeadMatchSortBy()

class DistinctHeadMatchSortBy() {
  operator fun <AP : Pattern<Q>, Q : XR.Query, BP : Pattern<List<XR.OrderField>>> get(x: AP, y: BP) =
    customPattern2M("XR.SortBy.DistinctHead", x, y) { it: XR.SortBy ->
      with(it) {
        when {
          this.head is XR.Distinct -> Components2M(this.head.head, this.id, this.criteria)
          else -> null
        }
      }
    }
}


fun XR.When.nestProperty(propertyName: String): XR.When =
  XR.When(this.branches.map { it.copy(then = XR.Property(it.then, propertyName)) }, XR.Property(this.orElse, propertyName))


object CID {
  val kotlin_String = XR.ClassId("kotlin.String")
  val kotlin_Int = XR.ClassId("kotlin.Int")
  val kotlin_Long = XR.ClassId("kotlin.Long")
  val kotlin_Short = XR.ClassId("kotlin.Short")
  val kotlin_Double = XR.ClassId("kotlin.Double")
  val kotlin_Float = XR.ClassId("kotlin.Float")
  val kotlin_Boolean = XR.ClassId("kotlin.Boolean")
}

fun XR.ClassId.isWholeNumber(): Boolean =
  when (this) {
    CID.kotlin_Int, CID.kotlin_Long, CID.kotlin_Short -> true
    else -> false
  }

fun XR.ClassId.isFloatingPoint(): Boolean =
  when (this) {
    CID.kotlin_Double, CID.kotlin_Float -> true
    else -> false
  }

fun XR.ClassId.isNumeric(): Boolean =
  when (this) {
    CID.kotlin_Int, CID.kotlin_Long, CID.kotlin_Short, CID.kotlin_Double, CID.kotlin_Float -> true
    else -> false
  }

fun String.isConverterFunction(): Boolean =
  when (this) {
    "toLong", "toInt", "toShort", "toDouble", "toFloat", "toBoolean", "toString" -> true
    else -> false
  }

fun XR.Action.toActionKind(): ActionKind = when (this) {
  is XR.Insert -> ActionKind.Insert
  is XR.Update -> ActionKind.Update
  is XR.Delete -> ActionKind.Delete
  // The following contain nested actions inside, go into them and get the data
  is XR.Returning -> this.action.toActionKind()
  is XR.FilteredAction -> this.action.toActionKind()
  is XR.OnConflict -> this.insert.toActionKind()
  is XR.Free ->
    // TODO This is not exactly the best implementaiton. We should probably introduce a FreeKind to the XR
    //      that we will capture by doing free("...").asInsert/Update/Delete
    CollectXR.byType<XR.U.CoreAction>(this).firstOrNull()?.let { it.toActionKind() } ?: ActionKind.Unknown
  is XR.TagForSqlAction -> ActionKind.Unknown
}
