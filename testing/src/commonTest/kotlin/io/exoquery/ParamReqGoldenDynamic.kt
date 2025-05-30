package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object ParamReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "single single-param/compileTime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ?"
    ),
    "single single-param/compileTime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = {0:Leah}",
      "0" to "Leah"
    ),
    "single single-param/compileTime/Params" to cr(
      "[ParamSingle(0, Leah, String)]"
    ),
    "single single-param/runtime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ?"
    ),
    "single single-param/runtime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ?"
    ),
    "single single-param/runtime/Params" to cr(
      "[ParamSingle(0, Leah, String)]"
    ),
    "multi single-param/compileTime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ? AND p.age = ?"
    ),
    "multi single-param/compileTime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ? AND p.age = ?"
    ),
    "multi single-param/compileTime/Params" to cr(
      "[ParamSingle(0, Leib, String), ParamSingle(1, 42, Int)]"
    ),
    "multi single-param/runtime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ? AND p.age = ?"
    ),
    "multi single-param/runtime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ? AND p.age = ?"
    ),
    "multi single-param/runtime/Params" to cr(
      "[ParamSingle(0, Leib, String), ParamSingle(1, 42, Int)]"
    ),
    "single multi-param/compileTime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name IN (?)"
    ),
    "single multi-param/compileTime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name IN (?, ?)"
    ),
    "single multi-param/compileTime/Params" to cr(
      "[ParamMulti(0, [Leah, Leib], String)]"
    ),
    "single multi-param/runtime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name IN (?, ?)"
    ),
    "single multi-param/runtime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.name IN (?, ?)"
    ),
    "single multi-param/runtime/Params" to cr(
      "[ParamMulti(0, [Leah, Leib], String)]"
    ),
    "multi multi-param/compileTime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name IN (?) THEN p.age IN (?) ELSE false END"
    ),
    "multi multi-param/compileTime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name IN (?, ?) THEN p.age IN (?, ?) ELSE false END"
    ),
    "multi multi-param/compileTime/Params" to cr(
      "[ParamMulti(0, [Leah, Leib], String), ParamMulti(1, [42, 43], Int)]"
    ),
    "multi multi-param/runtime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name IN (?, ?) THEN p.age IN (?, ?) ELSE false END"
    ),
    "multi multi-param/runtime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name IN (?, ?) THEN p.age IN (?, ?) ELSE false END"
    ),
    "multi multi-param/runtime/Params" to cr(
      "[ParamMulti(0, [Leah, Leib], String), ParamMulti(1, [42, 43], Int)]"
    ),
    "one single, one multi/compileTime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name = ? THEN p.age IN (?) ELSE false END"
    ),
    "one single, one multi/compileTime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name = ? THEN p.age IN (?, ?) ELSE false END"
    ),
    "one single, one multi/compileTime/Params" to cr(
      "[ParamSingle(0, Joe, String), ParamMulti(1, [42, 43], Int)]"
    ),
    "one single, one multi/runtime/Original SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name = ? THEN p.age IN (?, ?) ELSE false END"
    ),
    "one single, one multi/runtime/Determinized SQL" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE CASE WHEN p.name = ? THEN p.age IN (?, ?) ELSE false END"
    ),
    "one single, one multi/runtime/Params" to cr(
      "[ParamSingle(0, Joe, String), ParamMulti(1, [42, 43], Int)]"
    ),
    "datatypes/LocalDate comparison/Original SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthDate > ? OR c.birthDate >= ? OR c.birthDate < ? OR c.birthDate <= ?"
    ),
    "datatypes/LocalDate comparison/Determinized SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthDate > ? OR c.birthDate >= ? OR c.birthDate < ? OR c.birthDate <= ?"
    ),
    "datatypes/LocalDate comparison/Params" to cr(
      "[ParamSingle(0, 2000-01-01, LocalDate), ParamSingle(1, 2000-01-01, LocalDate), ParamSingle(2, 2000-01-01, LocalDate), ParamSingle(3, 2000-01-01, LocalDate)]"
    ),
    "datatypes/LocalTime comparison/Original SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthTime > ? OR c.birthTime >= ? OR c.birthTime < ? OR c.birthTime <= ?"
    ),
    "datatypes/LocalTime comparison/Determinized SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthTime > ? OR c.birthTime >= ? OR c.birthTime < ? OR c.birthTime <= ?"
    ),
    "datatypes/LocalTime comparison/Params" to cr(
      "[ParamSingle(0, 12:00, LocalTime), ParamSingle(1, 12:00, LocalTime), ParamSingle(2, 12:00, LocalTime), ParamSingle(3, 12:00, LocalTime)]"
    ),
    "datatypes/LocalDateTime comparison/Original SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthDateTime > ? OR c.birthDateTime >= ? OR c.birthDateTime < ? OR c.birthDateTime <= ?"
    ),
    "datatypes/LocalDateTime comparison/Determinized SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthDateTime > ? OR c.birthDateTime >= ? OR c.birthDateTime < ? OR c.birthDateTime <= ?"
    ),
    "datatypes/LocalDateTime comparison/Params" to cr(
      "[ParamSingle(0, 2000-01-01T12:00, LocalDateTime), ParamSingle(1, 2000-01-01T12:00, LocalDateTime), ParamSingle(2, 2000-01-01T12:00, LocalDateTime), ParamSingle(3, 2000-01-01T12:00, LocalDateTime)]"
    ),
    "datatypes/Instant comparison/Original SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthInstant > ? OR c.birthInstant >= ? OR c.birthInstant < ? OR c.birthInstant <= ?"
    ),
    "datatypes/Instant comparison/Determinized SQL" to cr(
      "SELECT c.name AS value FROM Client c WHERE c.birthInstant > ? OR c.birthInstant >= ? OR c.birthInstant < ? OR c.birthInstant <= ?"
    ),
    "datatypes/Instant comparison/Params" to cr(
      "[ParamSingle(0, 2000-01-01T00:00:00Z, Instant), ParamSingle(1, 2000-01-01T00:00:00Z, Instant), ParamSingle(2, 2000-01-01T00:00:00Z, Instant), ParamSingle(3, 2000-01-01T00:00:00Z, Instant)]"
    ),
  )
}
