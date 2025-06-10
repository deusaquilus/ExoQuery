package io.exoquery

import io.exoquery.sql.PostgresDialect //hello

fun main() {
  data class Person(val id: Int, val name: String, val age: Int)
  data class Address(val ownerId: Int, val street: String, val city: String)
  data class Robot(val ownerId: Int, val name: String, val model: String)

  val cap =
    capture {
      Table<Person>().flatMap { p ->
        internal.flatJoin(Table<Address>()) { a -> p.id == a.ownerId }.map { a -> p to a }
          .flatMap { pa ->
            internal.flatJoin(Table<Robot>()) { r -> pa.first.id == r.ownerId }
              .map { r -> Triple(pa.first, pa.second, r) }
          }
      }
    }

  println("----------------- XR ---------------\n" + cap.xr.showRaw())
  val built = cap.buildPretty<PostgresDialect>()
  //val built = cap.buildRuntime(PostgresDialect(), null, true)
  println("----------------- SQL ---------------\n" + built.value)
}
