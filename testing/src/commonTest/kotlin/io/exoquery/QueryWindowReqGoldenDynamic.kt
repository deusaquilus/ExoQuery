package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object QueryWindowReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "paritionBy, orderBy/rank/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(RANK_GC())) }"
    ),
    "paritionBy, orderBy/rank/SQL" to cr(
      "SELECT p.name AS first, RANK() OVER(PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
    "paritionBy, orderBy/avg/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(AVG_GC(p.id))) }"
    ),
    "paritionBy, orderBy/avg/SQL" to cr(
      "SELECT p.name AS first, AVG(p.id) OVER(PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
    "paritionBy, orderBy/rowNumber/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(ROW_NUMBER_GC())) }"
    ),
    "paritionBy, orderBy/rowNumber/SQL" to cr(
      "SELECT p.name AS first, ROW_NUMBER() OVER(PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
    "paritionBy, orderBy/count/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(COUNT_GC(p.age))) }"
    ),
    "paritionBy, orderBy/count/SQL" to cr(
      "SELECT p.name AS first, COUNT(p.age) OVER(PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
    "paritionBy, orderBy/count star/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(COUNT(*)_GC())) }"
    ),
    "paritionBy, orderBy/count star/SQL" to cr(
      "SELECT p.name AS first, COUNT(*)() OVER(PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
    "just partitionBy/rank/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy().over(RANK_GC())) }"
    ),
    "just partitionBy/rank/SQL" to cr(
      "SELECT p.name AS first, RANK() OVER(PARTITION BY p.name) AS second FROM Person p"
    ),
    "just orderBy/rank/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy().orderBy(p.age).over(RANK_GC())) }"
    ),
    "just orderBy/rank/SQL" to cr(
      "SELECT p.name AS first, RANK() OVER(ORDER BY p.age) AS second FROM Person p"
    ),
    "empty over/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy().orderBy().over(RANK_GC())) }"
    ),
    "empty over/SQL" to cr(
      "SELECT p.name AS first, RANK() OVER() AS second FROM Person p"
    ),
  )
}
