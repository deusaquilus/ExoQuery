package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object QueryDistinctReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "distinct simple/table.map.distinct/XR" to kt(
      "Table(Person).map { it -> Tuple(first = it.name, second = it.age) }.distinct"
    ),
    "distinct simple/table.map.distinct/SQL" to cr(
      "SELECT DISTINCT it.name AS first, it.age AS second FROM Person it"
    ),
    "distinct simple/table.map.distinct.sortBy/XR" to kt(
      "Table(Person).map { it -> Tuple(first = it.name, second = it.age) }.distinct.sortBy { it.first to Asc }"
    ),
    "distinct simple/table.map.distinct.sortBy/SQL" to cr(
      "SELECT DISTINCT it.name AS first, it.age AS second FROM Person it ORDER BY it.name ASC"
    ),
    "distinct simple/(selectClause + aggregation).distinct/XR" to kt(
      "select { val p = from(Table(Person)); val a = join(Table(Address)) { p.id == a.ownerId }; groupBy(p.name); Tuple(first = p.name, second = max_GC(p.age)) }.distinct"
    ),
    "distinct simple/(selectClause + aggregation).distinct/SQL" to cr(
      "SELECT DISTINCT p.name AS first, max(p.age) AS second FROM Person p INNER JOIN Address a ON p.id = a.ownerId GROUP BY p.name"
    ),
    "distinct simple/table.join(table.distinct)/XR" to kt(
      "select { val p = from(Table(Person)); val a = join(Table(Address).distinct) { p.id == a.ownerId }; Tuple(first = p, second = a) }"
    ),
    "distinct simple/table.join(table.distinct)/SQL" to cr(
      "SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city FROM Person p INNER JOIN (SELECT DISTINCT x.ownerId, x.street, x.city FROM Address x) AS a ON p.id = a.ownerId"
    ),
    "distinct simple/from.distinct, from/XR" to kt(
      "select { val p = from(Table(Person).distinct); val a = from(Table(Address).filter { it -> it.ownerId == p.id }); Tuple(first = p, second = a) }"
    ),
    "distinct simple/from.distinct, from/SQL" to cr(
      "SELECT p.id, p.name, p.age, it.ownerId, it.street, it.city FROM (SELECT DISTINCT x.id, x.name, x.age FROM Person x) AS p, Address it WHERE it.ownerId = p.id"
    ),
    "distinct simple/from.distinct, sort.join/XR" to kt(
      "select { val p = from(Table(Person).distinct); val a = join(Table(Address).sortBy { it.city to Asc }) { p.id == a.ownerId }; Tuple(first = p, second = a) }"
    ),
    "distinct simple/from.distinct, sort.join/SQL" to cr(
      "SELECT p.id, p.name, p.age, it.ownerId, it.street, it.city FROM (SELECT DISTINCT x.id, x.name, x.age FROM Person x) AS p INNER JOIN (SELECT it.ownerId, it.street, it.city FROM Address it ORDER BY it.city ASC) AS it ON p.id = it.ownerId"
    ),
  )
}
