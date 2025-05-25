package io.exoquery.xr

import io.exoquery.BID
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.be
import io.kotest.matchers.should

infix fun <T: XR, U: T> T.shouldBeXR(expected: U): T {
  this should be(expected)
  // Normally XR equality doesn't care about the type but for serialization tests
  // we want to explicitly check it
  this.type should be(expected.type)
  // same with the xr location
  this.loc should be(expected.loc)
  return this
}

class EncodingSpecXR: FreeSpec({
  "XR.Expression" - {
    "Ident" {
      val xr = XR.Ident("foo", XRType.Value)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Const" - {
      "Int" { XR.Const.Boolean(true).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Boolean" { XR.Const.Int(1).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Char" { XR.Const.Char('a').let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Byte" { XR.Const.Byte(1).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Short" { XR.Const.Short(1).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Long" { XR.Const.Long(1).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "String" { XR.Const.String("foo").let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Float" { XR.Const.Float(1.0f).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Double" { XR.Const.Double(1.0).let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
      "Null" { XR.Const.Null().let { xr -> xr.encode().decodeXR() shouldBeXR xr } }
    }
    "Property" {
      val xr = XR.Property(XR.Ident("foo", XRType.Value), "bar")
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FunctionN" {
      val xr = XR.FunctionN(listOf(XR.Ident("foo", XRType.Value)), XR.Ident("bar", XRType.Value))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FunctionApply" {
      val xr = XR.FunctionApply(XR.Ident("foo", XRType.Value), listOf(XR.Ident("bar", XRType.Value)))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "BinaryOp" {
      val one = XR.Const.Int(1)
      val two = XR.Const.Int(2)
      XR.BinaryOp(one, OP.Plus, two).let { xr -> xr.encode().decodeXR() shouldBeXR xr }
      XR.BinaryOp(one, OP.Minus, two).let { xr -> xr.encode().decodeXR() shouldBeXR xr }
      XR.BinaryOp(one, OP.Mult, two).let { xr -> xr.encode().decodeXR() shouldBeXR xr }
      XR.BinaryOp(one, OP.Div, two).let { xr -> xr.encode().decodeXR() shouldBeXR xr }
    }
    "UnaryOp" {
      val one = XR.Const.Int(1)
      XR.UnaryOp(OP.Minus, one).let { xr -> xr.encode().decodeXR() shouldBeXR xr }
    }
    "TagForParam" {
      val xr = XR.TagForParam(BID.Companion.new(), XR.ParamType.Single, XRType.Value)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "TagForSqlExpression" {
      val xr = XR.TagForSqlExpression(BID.Companion.new(), XRType.Value)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "MethodCall" {
      val xr = XR.MethodCall(
        XR.Ident("one", XRType.Value),
        "foo",
        listOf(XR.Ident("two", XRType.Value)),
        XR.CallType.PureFunction,
        XR.ClassId("a", "b"),
        false,
        XRType.Value
      )
      xr.encode().decodeXR() shouldBeXR xr
    }
    "GlobalCall" {
      val xr = XR.GlobalCall(
        XR.FqName("foo.bar"),
        listOf(XR.Ident("baz", XRType.Value)),
        XR.CallType.PureFunction,
        false,
        XRType.Value
      )
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Block" {
      val xr = XR.Block(listOf(XR.Variable(XR.Ident("foo"), XR.Ident("bar"))), XR.Ident("baz", XRType.Value))
      xr.encode().decodeXR() shouldBeXR xr
    }
  }
  "XR.Query" - {
    val peopleType = XRType.Product("foo.bar.Product", listOf("name" to XRType.Value))
    val people = XR.Entity("People", peopleType)
    val specialPeople = XR.Entity("SpecialPeople", peopleType)
    val peopleVar = XR.Ident("p", peopleType)
    val peopleAreGreen = XR.Property(peopleVar, "name") `+==+` XR.Const.String("Green")
    val peopleToName = XR.Map(people, peopleVar, XR.Property(XR.Ident("p", peopleType), "name"))
    val addressType = XRType.Product("foo.bar.Product", listOf("street" to XRType.Value, "city" to XRType.Value))
    val addresses = XR.Entity("Addresses", addressType)
    val addressesVar = XR.Ident("a", addressType)
    val addressesJoinCond = XR.Property(peopleVar, "name") `+==+` XR.Property(addressesVar, "street")

    "FlatMap" {
      val xr = XR.FlatMap(people, XR.Ident("bar", XRType.Value), peopleToName)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Map" {
      peopleToName.encode().decodeXR() shouldBeXR peopleToName
    }
    "Entity" {
      people.encode().decodeXR() shouldBeXR people
    }
    "Filter" {
      val xr = XR.Filter(people, peopleVar, peopleAreGreen)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Union" {
      val xr = XR.Union(people, specialPeople)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FlatJoin" {
      val xr = XR.FlatJoin(XR.JoinType.Inner, people, peopleVar, XR.Ident("something"))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FlatGroupBy" {
      val xr = XR.FlatGroupBy(XR.Property(peopleVar, "name"))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FlatSortBy" {
      val xr = XR.FlatSortBy(listOf(XR.OrderField.By(XR.Property(peopleVar, "name"), XR.Ordering.Asc)))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FlatSortBy - Implicit" {
      val xr = XR.FlatSortBy(listOf(XR.OrderField.Implicit(XR.Property(peopleVar, "name"))))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "FlatFilter" {
      val xr = XR.FlatFilter(peopleAreGreen)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Distinct" {
      val xr = XR.Distinct(people)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "DistinctOn" {
      val xr = XR.DistinctOn(people, peopleVar, XR.Property(peopleVar, "name"))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Nested" {
      val xr = XR.Nested(people)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "CustomQueryRef" {
      val clause =
        SelectClause(
          listOf(
            SX.From(peopleVar, people),
            SX.Join(XR.JoinType.Inner, addressesVar, addresses, addressesVar, addressesJoinCond)
          ),
          SX.Where(peopleAreGreen),
          SX.GroupBy(peopleVar),
          SX.SortBy(listOf(XR.OrderField.Implicit(peopleVar), XR.OrderField.By(peopleVar, XR.Ordering.Asc))),
          peopleVar,
          peopleType
        )
      val xr = XR.CustomQueryRef(clause)
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Insert" {
      val xr = XR.Insert(
        people,
        XR.Ident("this"),
        listOf(XR.Assignment(XR.Property(XR.Ident("this"), "name"), XR.Const.String("Joe"))),
        listOf(XR.Property(XR.Ident("this"), "name"))
      )
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Update" {
      val xr = XR.Update(
        people,
        XR.Ident("this"),
        listOf(XR.Assignment(XR.Property(XR.Ident("this"), "name"), XR.Const.String("Joe"))),
        listOf()
      )
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Delete" {
      val xr = XR.Delete(XR.Entity("BadPeople", peopleType), XR.Ident("this"))
      xr.encode().decodeXR() shouldBeXR xr
    }
    "OnConflict - Update" {
      val insert = XR.Insert(
        people,
        XR.Ident("this"),
        listOf(XR.Assignment(XR.Property(XR.Ident("this"), "name"), XR.Const.String("Joe"))),
        listOf(XR.Property(XR.Ident("this"), "name"))
      )
      val xr = XR.OnConflict(
        insert,
        XR.OnConflict.Target.Properties(listOf(XR.Property(XR.Ident("this"), "name"))),
        XR.OnConflict.Resolution.Update(
          XR.Ident("exclude"),
          XR.Ident("include"),
          listOf(XR.Assignment(XR.Property(XR.Ident("this"), "name"), XR.Const.String("Joe")))
        )
      )
      xr.encode().decodeXR() shouldBeXR xr
    }
    "Batching" {
      val insert = XR.Insert(
        people,
        XR.Ident("this"),
        listOf(XR.Assignment(XR.Property(XR.Ident("this"), "name"), XR.Const.String("Joe"))),
        listOf(XR.Property(XR.Ident("this"), "name"))
      )
      val xr = XR.Batching(XR.Ident("p"), insert)
      //xr.encode().decodeXR() shouldBeXR xr // doesn't work since .encode is specific to Batching
      // Maybe in kotlin protobuf serialization, you can't have real elements mixed with abstract elements in the root of the hiearchy (i.e. everything else that inherits from XR is abstract)
      xr.encode().decodeBatching() shouldBeXR xr
    }
  }
})
