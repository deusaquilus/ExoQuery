package io.exoquery.xr

import io.exoquery.tupleOf
import io.exoquery.xr.XR.*
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class BetaReductionSpec: FreeSpec({
  // TODO do properties need visible/fixed
  "simplifies the ast by applying functions" - {
    "caseclass field" {
      val ast: XR = Property(Product("CC", listOf(("foo" to Ident("a")))), "foo")
      BetaReduction.ofXR(ast) shouldBe Ident("a")
    }
    "functionN apply" {
      val function = FunctionN(listOf(Ident("a")), Ident("a"))
      val ast: XR = FunctionApply(function, listOf(Ident("b")))
      BetaReduction.ofXR(ast) shouldBe Ident("b")
    }
  }
  "replaces identifiers by actuals" - {
    "ident" {
      val ast: XR = Ident("a")
      BetaReduction.ofXR(ast, Ident("a") to Ident("a'")) shouldBe
          Ident("a'")
    }
  }
  "with inline" - {
    val entity = Entity("a")
    val root = Ident("root")
    // Entity Idents
    val (aE, bE, cE, dE) = tupleOf(Ident("a"), Ident("b"), Ident("c"), Ident("d"))
    // Value Idents
    val (a, b, c, d) = tupleOf(Ident("a"), Ident("b"), Ident("c"), Ident("d"))
    val (c1, c2, c3) = tupleOf(Const.Int(1), Const.Int(2), Const.Int(3))

    "top level block" {
      val block = Block(
        listOf(
          Variable(aE, root),
          Variable(bE, aE)
        ),
        Property(bE, "foo")
      )
      BetaReduction.invoke(block) shouldBe Property(root, "foo")
    }

    "nested blocks" {
      val inner = Block(
        listOf(
          Variable(aE, root),
          Variable(b, c2),
          Variable(c, c3)
        ),
        XR.Product.TupleN(listOf(aE, bE, cE))
      )
      val outer = Block(
        listOf(
          Variable(aE, inner),
          Variable(bE, aE),
          Variable(cE, bE)
        ),
        cE
      )
      BetaReduction.invoke(outer).show() shouldBe XR.Product.TupleN(listOf(root, c2, c3)).show()
    }

    "nested blocks product" {
      val inner = Block(
        listOf(
          Variable(aE, root),
          Variable(b, c2),
          Variable(c, c3)
        ),
        XR.Product("CC", listOf("foo" to aE, "bar" to bE, "baz" to cE))
      )
      val outer = Block(
        listOf(
          Variable(aE, inner),
          Variable(bE, aE),
          Variable(cE, bE)
        ),
        cE
      )
      BetaReduction.invoke(outer) shouldBe XR.Product("CC", listOf("foo" to root, "bar" to c2, "baz" to c3))
    }
  }

  "replaces idents unless in outer scope" - {
    "filter" {
      val ast: XR = Filter(Entity("a"), Ident("b"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "filter - replace" {
      val ast: XR = Filter(Entity("a"), Ident("x"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe Filter(Entity("a"), Ident("x"), Ident("b'"))
    }
    "map" {
      val ast: XR = XR.Map(Entity("a"), Ident("b"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "map - replace" {
      val ast: XR = XR.Map(Entity("a"), Ident("x"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe XR.Map(Entity("a"), Ident("x"), Ident("b'"))
    }
    "flatMap" {
      val ast: XR = FlatMap(Entity("a"), Ident("b"), Entity("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "concatMap" {
      val ast: XR = ConcatMap(Entity("a"), Ident("b"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "concatMap - replace" {
      val ast: XR = ConcatMap(Entity("a"), Ident("x"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ConcatMap(Entity("a"), Ident("x"), Ident("b'"))
    }
    "sortBy" {
      val ast: XR = SortBy(Entity("a"), Ident("b"), listOf(XR.OrderField.By(Ident("b"), XR.Ordering.Asc)))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "sortBy - Implicit" {
      val ast: XR = SortBy(Entity("a"), Ident("b"), listOf(XR.OrderField.Implicit(Ident("b"))))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "outer join" {
      val ast: XR =
        FlatJoin(JoinType.Inner, Entity("a"), Ident("b"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe ast
    }
    "outer join - replae" {
      val ast: XR =
        FlatJoin(JoinType.Inner, Entity("a"), Ident("x"), Ident("b"))
      BetaReduction.ofXR(ast, Ident("b") to Ident("b'")) shouldBe FlatJoin(
        JoinType.Inner,
        Entity("a"),
        Ident("x"),
        Ident("b'")
      )
    }
  }

  "function reduction" - {
    "functionN" {
      val ast: XR =
        FunctionN(listOf(Ident("a"), Ident("b")), BinaryOp(Ident("a"), OP.Plus, Ident("b")))

      BetaReduction.ofXR(ast, Ident("a") to Ident("aa")) shouldBe FunctionN(
        listOf(Ident("aa"), Ident("b")),
        BinaryOp(Ident("aa"), OP.Plus, Ident("b"))
      )
    }
  }

  "window reduction" - {
    "window function" {
      val ast: XR = Window(
        listOf(Ident("a"), Ident("b")),
        listOf(XR.OrderField.By(Ident("c"), Ordering.Asc), XR.OrderField.Implicit(Ident("d"))),
        Ident("e")
      )
      BetaReduction.ofXR(
        ast,
        Ident("a") to Ident("a'"),
        Ident("b") to Ident("b'"),
        Ident("c") to Ident("c'"),
        Ident("d") to Ident("d'"),
        Ident("e") to Ident("e'")
      ) shouldBe Window(
        listOf(Ident("a'"), Ident("b'")),
        listOf(XR.OrderField.By(Ident("c'"), Ordering.Asc), XR.OrderField.Implicit(Ident("d'"))),
        Ident("e'")
      )
    }

  }

  "doesn't shadow identifiers" - {
    "function apply" {
      val ast: XR = FunctionApply(
        FunctionN(listOf(Ident("a"), Ident("b")), BinaryOp(Ident("a"), OP.Div, Ident("b"))),
        listOf(Ident("b"), Ident("a"))
      )
      BetaReduction.ofXR(ast) shouldBe BinaryOp(Ident("b"), OP.Div, Ident("a"))
    }
    "nested function apply" {
      // (b) => a/b
      val f1 = FunctionN(listOf(Ident("b")), BinaryOp(Ident("a"), OP.Div, Ident("b")))
      // (a) => (b) => a/b
      val f2 = FunctionN(listOf(Ident("a")), f1)
      // ((a) => (b) => a/b).apply(b).apply(a) ->
      //   (tmp_b) => a/tmp_b ->
      //       b/tmp_b ->
      //         b/a
      val ast: XR = FunctionApply(FunctionApply(f2, listOf(Ident("b"))), listOf(Ident("a")))
      BetaReduction.ofXR(ast) shouldBe BinaryOp(Ident("b"), OP.Div, Ident("a"))
    }
  }

  "treats duplicate aliases normally" {
    val property: XR = Property(Product.TupleN(listOf(Ident("a"), Ident("a"))), "first")
    BetaReduction.ofXR(property, Ident("a") to Ident("a'")) shouldBe
        Ident("a'")
  }

  "treats duplicate aliases normally - (no property redunction)" {
    val property: XR = Product.TupleN(listOf(Ident("a"), Ident("a")))
    BetaReduction.ofXR(property, Ident("a") to Ident("a'")) shouldBe
        Product.TupleN(listOf(Ident("a'"), Ident("a'")))
  }

  "reapplies the beta reduction if the structure changes" {
    val quat = XRType.LeafTuple(1)
    val ast: XR = Property(Ident("a", quat), "first")
    BetaReduction.ofXR(ast, Ident("a", quat) to Product.TupleN(listOf(Ident("a'")))) shouldBe
        Ident("a'")
  }

  "reapplies the beta reduction if the structure changes caseclass" {
    val quat = XRType.LeafProduct("foo")
    val ast: XR = Property(Ident("a", quat), "foo")
    BetaReduction.ofXR(ast, Ident("a", quat) to Product("CC", listOf(("foo" to Ident("a'"))))) shouldBe
        Ident("a'")
  }

  "applies reduction only once" {
    val ast: XR = Ident("a")
    BetaReduction.ofXR(ast, Ident("a") to Ident("b"), Ident("b") to Ident("c")) shouldBe
        Ident("b")
  }

})
