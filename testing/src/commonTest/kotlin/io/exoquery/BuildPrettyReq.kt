package io.exoquery

import io.exoquery.annotation.CapturedFunction
import io.exoquery.sql.PostgresDialect

// Note that the 1st time you overwrite the golden file it will still fail because the compile is using teh old version
// Also note that it won't actually override the BasicQuerySanitySpecGolden file unless you change this one

// build the file BasicQuerySanitySpecGolden.kt, is that as the constructor arg
class BuildPrettyReq: GoldenSpecDynamic(BuildPrettyReqGoldenDynamic, Mode.ExoGoldenTest(), {
  data class Person(val id: Int, val name: String, val age: Int)
  data class Address(val ownerId: Int, val street: String, val city: String)

  "select with join" {
    val people = capture.select {
      val p = from(Table<Person>())
      val a = join(Table<Address>()) { a -> a.ownerId == p.id }
      Pair(p.name, a.street)
    }
    shouldBeGolden(people.xr, "XR")
    shouldBeGolden(people.buildPrettyFor.Postgres(), useTokenRendering = false)
  }
})
