package io.exoquery

import io.exoquery.sql.PostgresDialect

class QueryWindowReq: GoldenSpecDynamic(GoldenQueryFile.Empty, Mode.ExoGoldenOverride(), {
  data class Person(val id: Int, val name: String, val age: Int)
  "paritionBy, orderBy" - {
    "rank" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().partitionBy(p.name).sortBy(p.age).rank()
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
    "avg" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().partitionBy(p.name).sortBy(p.age).avg(p.id)
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
    "rowNumber" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().partitionBy(p.name).sortBy(p.age).rowNumber()
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
    "count" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().partitionBy(p.name).sortBy(p.age).count(p.age)
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
    "count star" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().partitionBy(p.name).sortBy(p.age).count()
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
  }
  "just partitionBy" - {
    "rank" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().partitionBy(p.name).rank()
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
  }
  "just orderBy" - {
    "rank" {
      val q = capture.select {
        val p = from(Table<Person>())
        Pair(
          p.name,
          over().sortBy(p.age).rank()
        )
      }.dyanmic()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
  }
  "empty over" {
    val q = capture.select {
      val p = from(Table<Person>())
      Pair(
        p.name,
        over().rank()
      )
    }.dyanmic()
    shouldBeGolden(q.xr, "XR")
    shouldBeGolden(q.build<PostgresDialect>(), "SQL")
  }
})
