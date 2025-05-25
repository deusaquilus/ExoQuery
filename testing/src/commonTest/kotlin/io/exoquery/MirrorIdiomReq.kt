package io.exoquery

import io.exoquery.xr.*

class MirrorIdiomReq: GoldenSpecDynamic(MirrorIdiomReqGoldenDynamic, Mode.ExoGoldenTest(), {
  val personEnt = XR.Entity("Person", XRType.Product.leaf("Person", "name", "age"))
  val addressEnt = XR.Entity("Address", XRType.Product.leaf("Address", "street", "city"))
  infix fun String.dot(other: String): XR.Property = XR.Property(this.toId, other)

  "XR.Expression" - {
    "XR.Ident" {
      shouldBeGolden(XR.Ident("foo"))
    }
    "XR.Const.Int" { shouldBeGolden(XR.Const.Int(42)) }
    "XR.Const.Long" { shouldBeGolden(XR.Const.Long(42L)) }
    "XR.Const.Short" { shouldBeGolden(XR.Const.Short(42.toShort())) }
    "XR.Const.String" { shouldBeGolden(XR.Const.String("foo")) }
    "XR.Const.Double" { shouldBeGolden(XR.Const.Double(42.0)) }
    "XR.Const.Boolean" { shouldBeGolden(XR.Const.Boolean(true)) }
    "XR.Const.Char" { shouldBeGolden(XR.Const.Char('a')) }
    "XR.Const.Null" { shouldBeGolden(XR.Const.Null()) }

    "XR.Property" {
      shouldBeGolden(XR.Property(XR.Ident("foo"), "bar"))
    }
    "XR.Property Nested" {
      shouldBeGolden(XR.Property(XR.Property(XR.Ident("foo"), "bar"), "baz"))
    }
    "XR.When 1-Branch" {
      shouldBeGolden(XR.When(listOf(XR.Branch(XR.Ident("foo"), XR.Ident("bar"))), XR.Ident("baz")))
    }
    "XR.When 1-Branch BinaryOp" {
      shouldBeGolden(XR.When(listOf(XR.Branch(XR.Ident("a") `+==+` XR.Ident("b"), XR.Ident("bar"))), XR.Ident("baz")))
    }
    "XR.When 2-Branch" {
      shouldBeGolden(
        XR.When(
          listOf(
            XR.Branch(XR.Ident("foo"), XR.Ident("bar")),
            XR.Branch(XR.Ident("baz"), XR.Ident("qux"))
          ), XR.Ident("quux")
        )
      )
    }
    "XR.FunctionN" {
      shouldBeGolden(XR.FunctionN(listOf(XR.Ident("foo"), XR.Ident("bar")), XR.Ident("baz")))
    }
    "XR.FunctionApply" {
      shouldBeGolden(XR.FunctionApply(XR.Ident("foo"), listOf(XR.Ident("bar"), XR.Ident("baz"))))
    }
    "XR.FunctionApply - FunctionN" {
      val myFun = XR.FunctionN(listOf(XR.Ident("a"), XR.Ident("b")), XR.Ident("c"))
      shouldBeGolden(XR.FunctionApply(myFun, listOf(XR.Ident("bar"), XR.Ident("baz"))))
    }
    "XR.FunctionApply - Scoped" {
      shouldBeGolden(XR.FunctionApply(XR.Ident("a") `+==+` XR.Ident("b"), listOf(XR.Ident("bar"), XR.Ident("baz"))))
    }
    "XR.UnaryOp" {
      shouldBeGolden(XR.UnaryOp(OP.Minus, XR.Ident("foo")))
    }
    "XR.BinaryOp" {
      shouldBeGolden(XR.BinaryOp(XR.Ident("a"), OP.EqEq, XR.Ident("b")))
    }
    "XR.Block" {
      val vars = listOf(XR.Variable("foo".toId, "bar".toId), XR.Variable("baz".toId, "qux".toId))
      shouldBeGolden(XR.Block(vars, XR.Ident("baz")))
    }
    "XR.Product" {
      shouldBeGolden(XR.Product("MyClass", listOf("foo" to XR.Ident("bar"), "baz" to XR.Ident("qux"))))
    }
    "XR.Free" {
      shouldBeGolden(
        XR.Free(
          listOf("a", "b", "c"),
          listOf(XR.Ident("foo"), XR.Ident("bar"), XR.Ident("baz")),
          true,
          true,
          XRType.Value
        )
      )
    }
    "XR.MethodCall" {
      shouldBeGolden(
        XR.MethodCall(
          XR.Ident("foo"),
          "method",
          listOf(XR.Ident("bar"), XR.Ident("baz")),
          XR.CallType.PureFunction,
          XR.ClassId("com.Stuff"),
          false,
          XRType.Value
        )
      )
    }
    "XR.GlobalCall" {
      shouldBeGolden(
        XR.GlobalCall(
          XR.FqName("method"),
          listOf(XR.Ident("bar"), XR.Ident("baz")),
          XR.CallType.PureFunction,
          false,
          XRType.Value
        )
      )
    }
    "XR.QueryToExpr" {
      shouldBeGolden(XR.QueryToExpr(personEnt))
    }
    "XR.TagForParam" {
      shouldBeGolden(XR.TagForParam(BID("foo"), XR.ParamType.Single, XRType.Value))
    }
    "XR.TagForSqlExpression" {
      shouldBeGolden(XR.TagForSqlExpression(BID("foo"), XRType.Value))
    }
  }
  "XR.Query" - {
    "XR.Map" {
      shouldBeGolden(XR.Map(personEnt, "p".toId, "p" dot "name"))
    }
    "XR.FlatMap" {
      shouldBeGolden(XR.FlatMap(personEnt, "p".toId, XR.Map(personEnt, "p".toId, "p" dot "name")))
    }
    "XR.Filter" {
      shouldBeGolden(XR.Filter(personEnt, "p".toId, "p" dot "age" `+==+` XR.Const.Int(42)))
    }
    "XR.Entity" {
      shouldBeGolden(personEnt)
    }
    "XR.Union" {
      shouldBeGolden(XR.Union("foo".toId, "bar".toId))
    }
    "XR.UnionAll" {
      shouldBeGolden(XR.UnionAll("foo".toId, "bar".toId))
    }
    "XR.Distinct" {
      shouldBeGolden(XR.Distinct(personEnt))
    }
    "XR.DistinctOn" {
      shouldBeGolden(XR.DistinctOn(personEnt, "p".toId, "p" dot "name"))
    }
    "XR.Drop" {
      shouldBeGolden(XR.Drop(personEnt, XR.Const.Int(2)))
    }
    "XR.Take" {
      shouldBeGolden(XR.Take(personEnt, XR.Const.Int(2)))
    }
    "XR.SortBy" {
      shouldBeGolden(XR.SortBy(personEnt, "p".toId, listOf(XR.OrderField.By("p" dot "name", XR.Ordering.Asc))))
    }
    "XR.SortBy - Implicit" {
      shouldBeGolden(XR.SortBy(personEnt, "p".toId, listOf(XR.OrderField.Implicit("p" dot "name"))))
    }
    "XR.FlatJoin" {
      shouldBeGolden(XR.FlatJoin(XR.JoinType.Inner, personEnt, "p".toId, ("p" dot "name") `+==+` ("o" dot "other")))
    }
    "XR.FlatGroupBy" {
      shouldBeGolden(XR.FlatGroupBy("p" dot "name"))
    }
    "XR.FlatSortBy" {
      shouldBeGolden(XR.FlatSortBy(listOf(XR.OrderField.By("p" dot "name", XR.Ordering.Asc))))
    }
    "XR.FlatFilter" {
      shouldBeGolden(XR.FlatFilter("p" dot "name" `+==+` ("o" dot "other")))
    }
    "XR.ConcatMap" {
      shouldBeGolden(XR.ConcatMap(personEnt, "p".toId, "p" dot "name"))
    }
    "XR.Nested" {
      shouldBeGolden(XR.Nested(personEnt))
    }
    "XR.ExprToQuery" {
      shouldBeGolden(XR.ExprToQuery(XR.Ident("foo")))
    }
    "XR.Free" {
      shouldBeGolden(
        XR.Free(
          listOf("a", "b", "c"),
          listOf(XR.Ident("foo"), XR.Ident("bar"), XR.Ident("baz")),
          true,
          true,
          XRType.Value
        )
      )
    }
    "XR.TagForSqlQuery" {
      shouldBeGolden(XR.TagForSqlQuery(BID("foo"), XRType.Value))
    }
    "XR.CustomQueryRef - SelectValue" {
      shouldBeGolden(
        XR.CustomQueryRef(
          SelectClause(
            listOf(
              SX.From("p".toId, personEnt),
              SX.Join(XR.JoinType.Inner, "p".toId, addressEnt, "a".toId, ("p" dot "name") `+==+` ("a" dot "street"))
            ),
            SX.Where("p" dot "age" `+==+` XR.Const.Int(42)),
            SX.GroupBy("p" dot "name"),
            SX.SortBy(listOf(XR.OrderField.By("p" dot "name", XR.Ordering.Asc))),
            "p" dot "name",
            XRType.Value
          )
        )
      )
    }
  }
})
