package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object BuildPrettyReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "select with join/XR" to kt(
      "select { val p = from(Table(Person)); val a = join(Table(Address)) { a.ownerId == p.id }; Pair(first = p.name, second = a.street) }"
    ),
    "select with join" to cr(
      """
      SELECT
        p.name AS first,
        a.street AS second
      FROM
        Person p
        INNER JOIN Address a ON a.ownerId = p.id
      """
    ),
  )
}
