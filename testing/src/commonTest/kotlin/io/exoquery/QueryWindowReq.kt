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

  }
})
