package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object MonadicMachineryReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "capture.expression.(Row)->Table/XR" to kt(
      "select { val p = from(Table(Person)); val a = from({ p -> Table(Address).join { a -> p.id == a.ownerId }.toExpr }.apply(p)); val r = from({ p -> Table(Robot).join { r -> p.id == r.ownerId }.toExpr }.apply(p)) }"
    ),
    "capture.expression.(Row)->Table/SQL" to cr(
      "SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city, r.ownerId, r.name, r.model FROM Person p INNER JOIN Address a ON p.id = a.ownerId INNER JOIN Robot r ON p.id = r.ownerId"
    ),
    "capture.expression.(@Cap (Row)->Table).use/XR" to kt(
      "select { val p = from(Table(Person)); val a = from({ p -> Table(Address).join { a -> p.id == a.ownerId }.toExpr }.apply(p).toQuery); val r = from({ p -> Table(Robot).join { r -> p.id == r.ownerId }.toExpr }.apply(p).toQuery) }"
    ),
    "capture.expression.(@Cap (Row)->Table).use/SQL" to cr(
      "SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city, r.ownerId, r.name, r.model FROM Person p INNER JOIN Address a ON p.id = a.ownerId INNER JOIN Robot r ON p.id = r.ownerId"
    ),
    "capture.expression.use.(@Cap (Row)()->Table)/XR" to kt(
      "select { val p = from(Table(Person)); val a = from({  -> Table(Address).join { a -> this.id == a.ownerId }.toExpr }.apply().toQuery); val r = from({  -> Table(Robot).join { r -> this.id == r.ownerId }.toExpr }.apply().toQuery) }"
    ),
    "capture.expression.use.(@Cap (Row)()->Table)/SQL" to cr(
      "SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city, r.ownerId, r.name, r.model FROM Person p INNER JOIN Address a ON this.id = a.ownerId INNER JOIN Robot r ON this.id = r.ownerId"
    ),
    "capture.(@Cap (Row)()->Table)/XR" to kt(
      "select { val p = from(Table(Person)); val a = from({  -> Table(Address).join { a -> this.id == a.ownerId } }.toQuery.apply()); val r = from({  -> Table(Robot).join { r -> this.id == r.ownerId } }.toQuery.apply()) }"
    ),
    "capture.(@Cap (Row)()->Table)/SQL" to cr(
      "SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city, r.ownerId, r.name, r.model FROM Person p INNER JOIN Address a ON this.id = a.ownerId INNER JOIN Robot r ON this.id = r.ownerId"
    ),
  )
}
