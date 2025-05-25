package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object QueryWindowReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "paritionBy, orderBy/rank/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(rank_GC())) }"
    ),
    "paritionBy, orderBy/rank/SQL" to cr(
      "SELECT p.name AS first,  rank() OVER (PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
    "paritionBy, orderBy/avg/XR" to kt(
      "select { val p = from(Table(Person)); Pair(first = p.name, second = window().partitionBy(p.name).orderBy(p.age).over(avg_GC(p.id))) }"
    ),
    "paritionBy, orderBy/avg/SQL" to cr(
      "SELECT p.name AS first,  avg(p.id) OVER (PARTITION BY p.name ORDER BY p.age) AS second FROM Person p"
    ),
  )
}
