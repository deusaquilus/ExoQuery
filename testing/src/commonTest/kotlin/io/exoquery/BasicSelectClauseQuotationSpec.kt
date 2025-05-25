@file:io.exoquery.annotation.ExoGoldenTest

package io.exoquery

import io.exoquery.Ord.Asc
import io.exoquery.Ord.Desc
import io.exoquery.sql.PostgresDialect
import io.exoquery.xr.*
import io.exoquery.xr.XR.Expression
import io.exoquery.xr.XR.Product.Companion.TupleSmartN
import io.kotest.matchers.equals.shouldBeEqual

class BasicSelectClauseQuotationSpec: GoldenSpec(BasicSelectClauseQuotationSpecGolden, {
  data class Person(val id: Int, val name: String, val age: Int)
  data class Robot(val ownerId: Int, val model: String)
  data class Address(val ownerId: Int, val street: String, val zip: Int)

  val personTpe = XRType.Product("Person", listOf("id" to XRType.Value, "name" to XRType.Value, "age" to XRType.Value))
  val robotTpe = XRType.Product("Robot", listOf("ownerId" to XRType.Value, "model" to XRType.Value))
  val addressTpe =
    XRType.Product("Address", listOf("ownerId" to XRType.Value, "street" to XRType.Value, "zip" to XRType.Value))
  val personEnt = XR.Entity("Person", personTpe)
  val robotEnt = XR.Entity("Robot", robotTpe)
  val addressEnt = XR.Entity("Address", addressTpe)
  val pIdent = XR.Ident("p", personTpe)
  val rIdent = XR.Ident("r", robotTpe)
  val aIdent = XR.Ident("a", addressTpe)
  val joinRobot = XR.BinaryOp(XR.Property(pIdent, "id"), OP.EqEq, XR.Property(rIdent, "ownerId"))
  val joinAddress = XR.BinaryOp(XR.Property(pIdent, "id"), OP.EqEq, XR.Property(aIdent, "ownerId"))

  infix fun Expression.prop(propName: String): Expression = XR.Property(this, propName)

  "parsing features spec" - {
    val people = capture { Table<Person>() }

    data class Custom(val person: Person, val robot: Robot?)

    "from + join -> (p, r)" {
      val people =
        capture.select {
          val p = from(people)
          val r = join(Table<Robot>()) { r -> p.id == r.ownerId }
          p to r
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(SX.From(pIdent, personEnt), SX.Join(XR.JoinType.Inner, rIdent, robotEnt, rIdent, joinRobot)),
        select = XR.Product.TupleSmartN(XR.Ident("p", personTpe), XR.Ident("r", robotTpe)),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + join -> (p, r)").shouldBeGolden()
    }

    "from + join + leftJoin -> Custom(p, r)" {
      val people =
        capture.select {
          val p = from(people)
          val r = join(Table<Robot>()) { r -> p.id == r.ownerId }
          val a = joinLeft(Table<Address>()) { a -> p.id == a.ownerId }
          Custom(p, r)
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(
          SX.From(pIdent, personEnt),
          SX.Join(XR.JoinType.Inner, rIdent, robotEnt, rIdent, joinRobot),
          SX.Join(XR.JoinType.Left, aIdent, addressEnt, aIdent, joinAddress)
        ),
        select = XR.Product("Custom", "person" to XR.Ident("p", personTpe), "robot" to XR.Ident("r", robotTpe)),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + join + leftJoin -> Custom(p, r)").shouldBeGolden()
    }

    "from + leftJoin -> Custom(p, r)" {
      val people =
        capture.select {
          val p = from(people)
          val r = joinLeft(Table<Robot>()) { r -> p.id == r.ownerId }
          Custom(p, r)
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(SX.From(pIdent, personEnt), SX.Join(XR.JoinType.Left, rIdent, robotEnt, rIdent, joinRobot)),
        select = XR.Product("Custom", "person" to XR.Ident("p", personTpe), "robot" to XR.Ident("r", robotTpe)),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + leftJoin -> Custom(p, r)").shouldBeGolden()
    }

    "from + join + where" {
      val people =
        capture.select {
          val p = from(people)
          val r = join(Table<Robot>()) { r -> p.id == r.ownerId }
          where { p.name == "Joe" }
          p.name
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(SX.From(pIdent, personEnt), SX.Join(XR.JoinType.Inner, rIdent, robotEnt, rIdent, joinRobot)),
        where = SX.Where(XR.BinaryOp(XR.Property(pIdent, "name"), OP.EqEq, XR.Const("Joe"))),
        select = XR.Property(pIdent, "name"),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + join + where").shouldBeGolden()
    }
    "from + sort(Asc,Desc)" {
      val people =
        capture.select {
          val p = from(people)
          sortBy(
            p.age to Asc,
            p.name to Desc
          ) // TODO what if the return from here is UNIT, need to control for that in the parser
          p.name
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(SX.From(pIdent, personEnt)),
        sortBy = SX.SortBy(
          listOf(
            XR.OrderField.By(pIdent.prop("age"), XR.Ordering.Asc),
            XR.OrderField.By(pIdent.prop("name"), XR.Ordering.Desc)
          )
        ),
        select = XR.Property(pIdent, "name"),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + sort(Asc,Desc)").shouldBeGolden()
    }
    "from + sort(Asc)" {
      val people =
        capture.select {
          val p = from(people)
          sortBy(p.age to Asc)
          p.name
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(SX.From(pIdent, personEnt)),
        sortBy = SX.SortBy(listOf(XR.OrderField.By(pIdent.prop("age"), XR.Ordering.Asc))),
        select = XR.Property(pIdent, "name"),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + sort(Asc)").shouldBeGolden()
    }

    "from + groupBy(a)" {
      val people =
        capture.select {
          val p = from(people)
          groupBy(p.age)
          p.age
        }

      people.xr shouldBeEqual SelectClause.of(
        listOf(SX.From(pIdent, personEnt)),
        groupBy = SX.GroupBy(pIdent.prop("age")),
        select = XR.Property(pIdent, "age"),
        type = XRType.Value
      ).toXrRef()

      people.build<PostgresDialect>("from + groupBy").shouldBeGolden()
    }
  }

  "from + groupBy(a, b)" {
    val people =
      capture.select {
        val p = from(capture { Table<Person>() })
        groupBy(p.age, p.name)
        p.age
      }

    people.xr shouldBeEqual SelectClause.of(
      listOf(SX.From(pIdent, personEnt)),
      groupBy = SX.GroupBy(TupleSmartN(pIdent.prop("age"), pIdent.prop("name"))),
      select = XR.Property(pIdent, "age"),
      type = XRType.Value
    ).toXrRef()

    people.build<PostgresDialect>("from + groupBy(a, b)").shouldBeGolden()
  }
})
