package io.exoquery.xr

import io.exoquery.xr.XR.*
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class StatefulTransformerSpec: FreeSpec({
  class Subject(override val state: List<XR>, vararg val replace: Pair<XR, XR>):
    StatefulTransformerSingleRoot<List<XR>> {
    override fun <X: XR> root(e: X): Pair<X, StatefulTransformerSingleRoot<List<XR>>> {
      @Suppress("UNCHECKED_CAST")

      fun assertIsTypeX(tree: XR) =
        // i.e. `tree` has to be an instance of the class of `e`
        if (!e::class.isInstance(tree))
          throw IllegalArgumentException("Cannot replace ${e}:${e::class.simpleName} with ${tree}:${tree::class.simpleName} since they have different types")
        else
          tree as X

      val rep = replace.toMap().get(e)
      return when {
        rep != null ->
          Pair(assertIsTypeX(rep), Subject(state + e, *replace))

        else ->
          Pair(e, this)
      }
    }
  }

  "transforms asts using a transformation state" - {
    "query" - {
      "entity" {
        val ast: XR = Entity("a")
        Subject(listOf())(ast).let { (at, att) ->
          at shouldBe ast
          att.state shouldBe listOf()
        }
      }
      "filter" {
        val ast: XR = Filter(Entity("a"), Ident("b"), Ident("c"))
        Subject(
          listOf(),
          Entity("a") to Entity("a'"),
          Ident("b") to Ident("b'"),
          Ident("c") to Ident("c'")
        )(ast).let { (at, att) ->
          at shouldBe Filter(Entity("a'"), Ident("b"), Ident("c'"))
          att.state shouldBe listOf(Entity("a"), Ident("c"))
        }
      }
      "map" {
        val ast: XR = Map(Entity("a"), Ident("b"), Ident("c"))
        Subject(
          listOf(),
          Entity("a") to Entity("a'"),
          Ident("b") to Ident("b'"),
          Ident("c") to Ident("c'")
        )(ast).let { (at, att) ->
          at shouldBe Map(Entity("a'"), Ident("b"), Ident("c'"))
          att.state shouldBe listOf(Entity("a"), Ident("c"))
        }
      }
//      "filter" in {
//        val ast: Ast = Filter(Ident("a"), Ident("b"), Ident("c"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"), Ident("c") -> Ident("c'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Filter(Ident("a'"), Ident("b"), Ident("c'"))
//            att.state mustEqual List(Ident("a"), Ident("c"))
//        }
//      }
//      "map" in {
//        val ast: Ast = Map(Ident("a"), Ident("b"), Ident("c"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"), Ident("c") -> Ident("c'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Map(Ident("a'"), Ident("b"), Ident("c'"))
//            att.state mustEqual List(Ident("a"), Ident("c"))
//        }
//      }

      "flatMap" {
        val ast: XR = FlatMap(Entity("a"), Ident("b"), Entity("c"))
        Subject(
          listOf(),
          Entity("a") to Entity("a'"),
          Ident("b") to Ident("b'"),
          Entity("c") to Entity("c'")
        )(ast).let { (at, att) ->
          at shouldBe FlatMap(Entity("a'"), Ident("b"), Entity("c'"))
          att.state shouldBe listOf(Entity("a"), Entity("c"))
        }
      }

//      "flatMap" in {
//        val ast: Ast = FlatMap(Ident("a"), Ident("b"), Ident("c"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"), Ident("c") -> Ident("c'"))(ast) match {
//          case (at, att) =>
//            at mustEqual FlatMap(Ident("a'"), Ident("b"), Ident("c'"))
//            att.state mustEqual List(Ident("a"), Ident("c"))
//        }
//      }

      "concatMap" {
        val ast: XR = ConcatMap(Entity("a"), Ident("b"), Ident("c"))
        Subject(
          listOf(),
          Entity("a") to Entity("a'"),
          Ident("b") to Ident("b'"),
          Ident("c") to Ident("c'")
        )(ast).let { (at, att) ->
          at shouldBe ConcatMap(Entity("a'"), Ident("b"), Ident("c'"))
          att.state shouldBe listOf(Entity("a"), Ident("c"))
        }
      }

//      "concatMap" in {
//        val ast: Ast = ConcatMap(Ident("a"), Ident("b"), Ident("c"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"), Ident("c") -> Ident("c'"))(ast) match {
//          case (at, att) =>
//            at mustEqual ConcatMap(Ident("a'"), Ident("b"), Ident("c'"))
//            att.state mustEqual List(Ident("a"), Ident("c"))
//        }
//      }

      "sortBy" {
        val ast: XR = SortBy(Entity("a"), Ident("b"), listOf(XR.OrderField.By(Ident("c"), Ordering.AscNullsFirst)))
        Subject(
          listOf(),
          Entity("a") to Entity("a'"),
          Ident("b") to Ident("b'"),
          Ident("c") to Ident("c'")
        )(ast).let { (at, att) ->
          at shouldBe SortBy(Entity("a'"), Ident("b"), listOf(XR.OrderField.By(Ident("c'"), Ordering.AscNullsFirst)))
          att.state shouldBe listOf(Entity("a"), Ident("c"))
        }
      }

      "sortBy - Implicit" {
        val ast: XR = SortBy(Entity("a"), Ident("b"), listOf(XR.OrderField.Implicit(Ident("c"))))
        Subject(
          listOf(),
          Entity("a") to Entity("a'"),
          Ident("b") to Ident("b'"),
          Ident("c") to Ident("c'")
        )(ast).let { (at, att) ->
          at shouldBe SortBy(Entity("a'"), Ident("b"), listOf(XR.OrderField.Implicit(Ident("c'"))))
          att.state shouldBe listOf(Entity("a"), Ident("c"))
        }
      }

//      "sortBy" in {
//        val ast: Ast = SortBy(Ident("a"), Ident("b"), Ident("c"), AscNullsFirst)
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"), Ident("c") -> Ident("c'"))(ast) match {
//          case (at, att) =>
//            at mustEqual SortBy(Ident("a'"), Ident("b"), Ident("c'"), AscNullsFirst)
//            att.state mustEqual List(Ident("a"), Ident("c"))
//        }
//      }

      //"aggregation" {
      //  val ast: XR = Aggregation(OP.`max`, Ident("a"))
      //  Subject(listOf(), Ident("a") to Ident("a'"))(ast).let { (at, att) ->
      //      at shouldBe Aggregation(OP.`max`, Ident("a'"))
      //      att.state shouldBe listOf(Ident("a"))
      //  }
      //}

      "globalCall" {
        val ast: XR = GlobalCall(FqName.Empty, listOf(Ident("a"), Ident("b")), CallType.PureFunction, false, XRType.Value)
        Subject(listOf(), Ident("a") to Ident("a'"), Ident("b") to Ident("b'"))(ast).let { (at, att) ->
          at shouldBe GlobalCall(FqName.Empty, listOf(Ident("a'"), Ident("b'")), CallType.PureFunction, false, XRType.Value)
          att.state shouldBe listOf(Ident("a"), Ident("b"))
        }
      }

      "methodCall" {
        val ast: XR =
          MethodCall(Ident("a"), "foo", listOf(Ident("b")), CallType.PureFunction, ClassId("a", "b"), false, XRType.Value)
        Subject(listOf(), Ident("a") to Ident("a'"), Ident("b") to Ident("b'"))(ast).let { (at, att) ->
          at shouldBe MethodCall(
            Ident("a'"),
            "foo",
            listOf(Ident("b'")),
            CallType.PureFunction,
            ClassId("a", "b"),
            false,
            XRType.Value
          )
          att.state shouldBe listOf(Ident("a"), Ident("b"))
        }
      }

//      "aggregation" in {
//        val ast: Ast = Aggregation(AggregationOperator.max, Ident("a"))
//        Subject(Nil, Ident("a") -> Ident("a'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Aggregation(AggregationOperator.max, Ident("a'"))
//            att.state mustEqual List(Ident("a"))
//        }
//      }

      "take" {
        val ast: XR = Take(Entity("a"), Ident("b"))
        Subject(listOf(), Entity("a") to Entity("a'"), Ident("b") to Ident("b'"))(ast).let { (at, att) ->
          at shouldBe Take(Entity("a'"), Ident("b'"))
          att.state shouldBe listOf(Entity("a"), Ident("b"))
        }
      }

//      "take" in {
//        val ast: Ast = Take(Ident("a"), Ident("b"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Take(Ident("a'"), Ident("b'"))
//            att.state mustEqual List(Ident("a"), Ident("b"))
//        }
//      }

      "drop" {
        val ast: XR = Drop(Entity("a"), Ident("b"))
        Subject(listOf(), Entity("a") to Entity("a'"), Ident("b") to Ident("b'"))(ast).let { (at, att) ->
          at shouldBe Drop(Entity("a'"), Ident("b'"))
          att.state shouldBe listOf(Entity("a"), Ident("b"))
        }
      }

//      "drop" in {
//        val ast: Ast = Drop(Ident("a"), Ident("b"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Drop(Ident("a'"), Ident("b'"))
//            att.state mustEqual List(Ident("a"), Ident("b"))
//        }
//      }

      "union" {
        val ast: XR = Union(Entity("a"), Entity("b"))
        Subject(listOf(), Entity("a") to Entity("a'"), Entity("b") to Entity("b'"))(ast).let { (at, att) ->
          at shouldBe Union(Entity("a'"), Entity("b'"))
          att.state shouldBe listOf(Entity("a"), Entity("b"))
        }
      }

//      "union" in {
//        val ast: Ast = Union(Ident("a"), Ident("b"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Union(Ident("a'"), Ident("b'"))
//            att.state mustEqual List(Ident("a"), Ident("b"))
//        }
//      }

      "unionAll" {
        val ast: XR = UnionAll(Entity("a"), Entity("b"))
        Subject(listOf(), Entity("a") to Entity("a'"), Entity("b") to Entity("b'"))(ast).let { (at, att) ->
          at shouldBe UnionAll(Entity("a'"), Entity("b'"))
          att.state shouldBe listOf(Entity("a"), Entity("b"))
        }
      }

//      "unionAll" in {
//        val ast: Ast = UnionAll(Ident("a"), Ident("b"))
//        Subject(Nil, Ident("a") -> Ident("a'"), Ident("b") -> Ident("b'"))(ast) match {
//          case (at, att) =>
//            at mustEqual UnionAll(Ident("a'"), Ident("b'"))
//            att.state mustEqual List(Ident("a"), Ident("b"))
//        }
//      }

      "flat join" {
        val ast: XR = FlatJoin(JoinType.Inner, Entity("a"), Ident("b"), Ident("c"))
        Subject(listOf(), Entity("a") to Entity("a'"), Ident("c") to Ident("c'"))(ast).let { (at, att) ->
          at shouldBe FlatJoin(JoinType.Inner, Entity("a'"), Ident("b"), Ident("c'"))
          att.state shouldBe listOf(Entity("a"), Ident("c"))
        }
      }

      "distinct" {
        val ast: XR = Distinct(Entity("a"))
        Subject(listOf(), Entity("a") to Entity("a'"))(ast).let { (at, att) ->
          at shouldBe Distinct(Entity("a'"))
          att.state shouldBe listOf(Entity("a"))
        }

      }

//      "distinct" in {
//        val ast: Ast = Distinct(Ident("a"))
//        Subject(Nil, Ident("a") -> Ident("a'"))(ast) match {
//          case (at, att) =>
//            at mustEqual Distinct(Ident("a'"))
//            att.state mustEqual List(Ident("a"))
//        }
//      }

    }
  }

})
