package io.exoquery

import io.exoquery.util.TraceType
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe


class SelectClauseQueryReq : GoldenSpecDynamic(SelectClauseQueryReqGoldenDynamic, Mode.ExoGoldenTest(), {
  "table.emb?.field" - {
    data class Name(val first: String, val last: String)
    data class Person(val name: Name?, val age: Int)
    data class Robot(val ownerFirstName: String, val model: String)

    "join(p.name?.first)" {
      val people =
        capture.select {
          val p = from(Table<Person>())
          val r = join(Table<Robot>()) { r -> p.name?.first == r.ownerFirstName }
          p to r
        }
      shouldBeGolden(people.xr, "XR")
      shouldBeGolden(people.build<PostgresDialect>(), "SQL")
    }
    "join(p.name?.first ?: alternative)" {
      val people =
        capture.select {
          val p = from(Table<Person>())
          val r = join(Table<Robot>()) { r -> p.name?.first ?: "defaultName" == r.ownerFirstName }
          p to r
        }
      shouldBeGolden(people.xr, "XR")
      shouldBeGolden(people.build<PostgresDialect>(), "SQL")
    }
    "joinLeft(p.name?.first ?: alternative)" {
      val people =
        capture.select {
          val p = from(Table<Person>())
          val r = joinLeft(Table<Robot>()) { r -> p.name?.first ?: "defaultName" == r.ownerFirstName }
          p.name to r?.model
        }
      shouldBeGolden(people.xr, "XR")
      shouldBeGolden(people.build<PostgresDialect>(), "SQL")
    }

    "joinLeft(p.name?.first ?: alternative) -> r ?: alternative" {
      val people =
        capture.select {
          val p = from(Table<Person>())
          val r = joinLeft(Table<Robot>()) { r -> p.name?.first ?: "defaultName" == r.ownerFirstName }
          p.name to (r ?: Robot("defaultName", "defaultModel")).model
        }.dyanmic()
      shouldBeGolden(people.xr, "XR")
      shouldBeGolden(people.build<PostgresDialect>(), "SQL")
    }

    "joinLeft(p.name?.first ?: alternative) -> p ?: alternative" {
      val people =
        capture.select {
          val r = from(Table<Robot>())
          val p = joinLeft(Table<Person>()) { p -> r.ownerFirstName == p.name?.first ?: "defaultName" }
          (p ?: Person(Name("foo", "bar"), 123)).name?.first to r.model
        }.dyanmic()
      shouldBeGolden(people.xr, "XR")
      shouldBeGolden(people.build<PostgresDialect>(), "SQL")
    }
  }
})
