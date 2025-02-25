package io.exoquery

import io.exoquery.annotation.CapturedFunction
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.testdata.*
import io.exoquery.util.TraceType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain

class ExpressionFunctionReq : GoldenSpecDynamic(ExpressionFunctionReqGoldenDynamic, Mode.ExoGoldenTest(), {
  "String" - {
    "toInt" {
      val q = capture { Table<Person>().map { p -> p.name.toInt() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toLong" {
      val q = capture { Table<Person>().map { p -> p.name.toLong() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toBoolean" {
      val q = capture { Table<Person>().map { p -> p.name.toBoolean() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "substr" {
      val q = capture { Table<Person>().map { p -> p.name.sql.substring(1, 2) } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "left" {
      val q = capture { Table<Person>().map { p -> p.name.sql.left(2) } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "right" {
      val q = capture { Table<Person>().map { p -> p.name.sql.right(2) } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "replace" {
      val q = capture { Table<Person>().map { p -> p.name.sql.replace("a", "b") } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
// TODO method whitelist for this case
//    "upper" {
//      val q = capture { Table<Person>().map { p -> p.name.uppercase() } }
//      shouldBeGolden(q.build<PostgresDialect>())
//    }
    "upper - sql" {
      val q = capture { Table<Person>().map { p -> p.name.sql.uppercase() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
// TODO method whitelist for this case
//    "lower" {
//      val q = capture { Table<Person>().map { p -> p.name.lowercase() } }
//      shouldBeGolden(q.build<PostgresDialect>())
//    }
    "lower - sql" {
      val q = capture { Table<Person>().map { p -> p.name.sql.lowercase() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
  }
  "Int" - {
    "toLong" {
      val q = capture { Table<Person>().map { p -> p.age.toLong() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toDouble" {
      val q = capture { Table<Person>().map { p -> p.age.toDouble() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toFloat" {
      val q = capture { Table<Person>().map { p -> p.age.toFloat() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
  }
  "Long" - {
    "toInt" {
      val q = capture { Table<Person>().map { p -> p.age.toInt() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toDouble" {
      val q = capture { Table<Person>().map { p -> p.age.toDouble() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toFloat" {
      val q = capture { Table<Person>().map { p -> p.age.toFloat() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
  }
  "Float" - {
    "toInt" {
      val q = capture { Table<Person>().map { p -> p.age.toInt() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toLong" {
      val q = capture { Table<Person>().map { p -> p.age.toLong() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
    "toDouble" {
      val q = capture { Table<Person>().map { p -> p.age.toDouble() } }
      shouldBeGolden(q.build<PostgresDialect>())
    }
  }
})
