package io.exoquery.xr

import io.exoquery.xr.XR.*
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class StatelessTransformerSpec: FreeSpec({
  class Subject(vararg val replace: Pair<XR, XR>): StatelessTransformerSingleRoot {
    override fun <X: XR> root(xr: X): X {
      val rep = replace.toMap().getOrElse(xr, { xr })
      return when {
        // Is the thing we're replacing the element with the same type as the thing we were trying to replace
        !xr::class.isInstance(rep) ->
          throw IllegalArgumentException("Cannot replace ${xr}:${xr::class.simpleName} with ${rep}:${rep::class.simpleName} since they have different types")

        else -> rep as X
      }
    }
  }

  "transforms asts" - {
    "query" - {
      "filter" {
        val ast: XR = Filter(Entity("a"), Ident("b"), Ident("c"))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
            Filter(Entity("a'"), Ident("b"), Ident("c'"))
      }
      "map" {
        val ast: XR = Map(Entity("a"), Ident("b"), Ident("c"))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
            Map(Entity("a'"), Ident("b"), Ident("c'"))
      }
      "flatMap" {
        val ast: XR = FlatMap(Entity("a"), Ident("b"), Entity("c"))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"), Entity("c") to Entity("c'"))(ast) shouldBe
            FlatMap(Entity("a'"), Ident("b"), Entity("c'"))
      }
      "concatMap" {
        val ast: XR = ConcatMap(Entity("a"), Ident("b"), Ident("c"))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
            ConcatMap(Entity("a'"), Ident("b"), Ident("c'"))
      }
      "sortBy" {
        val ast: XR = SortBy(Entity("a"), Ident("b"), listOf(XR.OrderField.By(Ident("c"), Ordering.AscNullsFirst)))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
            SortBy(Entity("a'"), Ident("b"), listOf(XR.OrderField.By(Ident("c'"), Ordering.AscNullsFirst)))
      }
      "sortBy - Implicit" {
        val ast: XR = SortBy(Entity("a"), Ident("b"), listOf(XR.OrderField.Implicit(Ident("c"))))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
            SortBy(Entity("a'"), Ident("b"), listOf(XR.OrderField.Implicit(Ident("c'"))))
      }
      //"aggregation" {
      //  val ast: XR = Aggregation(OP.`max`, Ident("a"))
      //  Subject(Ident("a") to Ident("a'"))(ast) shouldBe
      //    Aggregation(OP.`max`, Ident("a'"))
      //}
      "globalCall" {
        val ast: XR = GlobalCall(FqName.Empty, listOf(Ident("a"), Ident("b")), CallType.PureFunction, false, XRType.Value)
        Subject(Ident("a") to Ident("a'"), Ident("b") to Ident("b'"))(ast) shouldBe
            GlobalCall(FqName.Empty, listOf(Ident("a'"), Ident("b'")), CallType.PureFunction, false, XRType.Value)
      }
      "methodCall" {
        val ast: XR = MethodCall(
          Ident("a"),
          "b",
          listOf(Ident("c"), Ident("d")),
          CallType.PureFunction,
          ClassId("a", "b"),
          false,
          XRType.Value
        )
        Subject(
          Ident("a") to Ident("a'"),
          Ident("c") to Ident("c'", XRType.Value),
          Ident("d") to Ident("d'", XRType.Value)
        )(ast) shouldBe
            MethodCall(
              Ident("a'"),
              "b",
              listOf(Ident("c'", XRType.Value), Ident("d'", XRType.Value)),
              CallType.PureFunction,
              ClassId("a", "b"),
              false,
              XRType.Value
            )
      }
      "take" {
        val ast: XR = Take(Entity("a"), Ident("b"))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"))(ast) shouldBe
            Take(Entity("a'"), Ident("b'"))
      }
      "drop" {
        val ast: XR = Drop(Entity("a"), Ident("b"))
        Subject(Entity("a") to Entity("a'"), Ident("b") to Ident("b'"))(ast) shouldBe
            Drop(Entity("a'"), Ident("b'"))
      }
      "union" {
        val ast: XR = Union(Entity("a"), Entity("b"))
        Subject(Entity("a") to Entity("a'"), Entity("b") to Entity("b'"))(ast) shouldBe
            Union(Entity("a'"), Entity("b'"))
      }
      "unionAll" {
        val ast: XR = UnionAll(Entity("a"), Entity("b"))
        Subject(Entity("a") to Entity("a'"), Entity("b") to Entity("b'"))(ast) shouldBe
            UnionAll(Entity("a'"), Entity("b'"))
      }
      "flat join" {
        val ast: XR = FlatJoin(JoinType.Inner, Entity("a"), Ident("b"), Ident("c"))
        Subject(Entity("a") to Entity("a'"), Ident("c") to Ident("c'"))(ast) shouldBe
            FlatJoin(JoinType.Inner, Entity("a'"), Ident("b"), Ident("c'"))
      }
      "distinct" {
        val ast: XR = Distinct(Entity("a"))
        Subject(Entity("a") to Entity("a'"))(ast) shouldBe Distinct(Entity("a'"))
      }
    }

    "expression" - {
      "unary" {
        val ast: XR = UnaryOp(OP.Not, Ident("a"))
        Subject(Ident("a") to Ident("a'"))(ast) shouldBe
            UnaryOp(OP.Not, Ident("a'"))
      }
      "binary" {
        val ast: XR = BinaryOp(Ident("a"), OP.And, Ident("b"))
        Subject(Ident("a") to Ident("a'"), Ident("b") to Ident("b'"))(ast) shouldBe
            BinaryOp(Ident("a'"), OP.And, Ident("b'"))
      }
      //"function apply" {
      //  val fun1 = XR.Function1(Ident("a"), Ident("a"))
      //  val fun1Prime = XR.Function1(Ident("'a"), Ident("'a"))
      //  val ast: XR = FunctionApply(fun1, listOf(Ident("b"), Ident("c")))
      //  Subject(fun1 to fun1Prime, Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
      //    FunctionApply(fun1Prime, listOf(Ident("b'"), Ident("c'")))
      //}
      "functionN apply" {
        val funN = XR.FunctionN(listOf(Ident("a"), Ident("b")), Ident("a"))
        val funNPrime = XR.FunctionN(listOf(Ident("'a"), Ident("'b")), Ident("'a"))
        val ast: XR = FunctionApply(funN, listOf(Ident("c"), Ident("d")))
        Subject(funN to funNPrime, Ident("c") to Ident("c'"), Ident("d") to Ident("d'"))(ast) shouldBe
            FunctionApply(funNPrime, listOf(Ident("c'"), Ident("d'")))
      }
      "constant" {
        val ast: XR = XR.Const.String("a")
        Subject()(ast) shouldBe ast
      }
      "null" {
        val ast: XR = XR.Const.Null()
        Subject()(ast) shouldBe ast
      }
      "product" {
        val ast: XR = Product("CC", listOf("foo" to Ident("a"), "bar" to Ident("b"), "baz" to Ident("c")))
        Subject(Ident("a") to Ident("a'"), Ident("b") to Ident("b'"), Ident("c") to Ident("c'"))(ast) shouldBe
            Product("CC", listOf("foo" to Ident("a'"), "bar" to Ident("b'"), "baz" to Ident("c'")))
      }
      "ident" {
        val ast: XR = Ident("a")
        Subject(Ident("a") to Ident("a'"))(ast) shouldBe
            Ident("a'")
      }
      "property" {
        val ast: XR = Property(Ident("a"), "b")
        Subject(Ident("a") to Ident("a'"))(ast) shouldBe
            Property(Ident("a'"), "b")
      }
      "when" {
        val ast: XR = When(
          listOf(
            Branch(Ident("a"), Ident("b")),
            Branch(Ident("c"), Ident("d"))
          ), Ident("e")
        )
        Subject(
          Ident("a") to Ident("a'"),
          Ident("b") to Ident("b'"),
          Ident("c") to Ident("c'"),
          Ident("d") to Ident("d'"),
          Ident("e") to Ident("e'")
        )(ast) shouldBe
            When(
              listOf(
                Branch(Ident("a'"), Ident("b'")),
                Branch(Ident("c'"), Ident("d'"))
              ), Ident("e'")
            )
      }
    }

    // When a variable declartion is reduced the actual variable should not change, only the right-hand-side
    "variable" {
      val ast: XR = XR.Variable(Ident("x"), Ident("a"))
      Subject(Ident("a") to Ident("a'"), Ident("x") to Ident("x'"))(ast) shouldBe
          XR.Variable(Ident("x"), Ident("a'"))
    }
    // Same thing with functions
    "functionN" {
      val ast: XR = XR.FunctionN(listOf(Ident("a"), Ident("b")), Ident("a"))
      Subject(Ident("a") to Ident("a'"), Ident("b") to Ident("b'"))(ast) shouldBe
          XR.FunctionN(listOf(Ident("a"), Ident("b")), Ident("a'"))
    }
    // Same thing with blocks, only the value should be replaced, not the variable
    "block" {
      val ast: XR = Block(
        listOf(
          Variable(Ident("a"), Ident("a")),
          Variable(Ident("b"), Ident("b"))
        ),
        Ident("a") `+==+` Ident("b")
      )
      Subject(Ident("a") to Ident("a'"), Ident("b") to Ident("b'"))(ast) shouldBe
          Block(
            listOf(
              Variable(Ident("a"), Ident("a'")),
              Variable(Ident("b"), Ident("b'"))
            ),
            Ident("a'") `+==+` Ident("b'")
          )
    }
  }
})
