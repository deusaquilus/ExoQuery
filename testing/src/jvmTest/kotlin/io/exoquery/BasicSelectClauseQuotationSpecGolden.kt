package io.exoquery

import io.exoquery.printing.cr

object BasicSelectClauseQuotationSpecGolden: GoldenQueryFile {
  override val queries = mapOf(
    "from + join -> (p, r)" to cr(
      """
      SELECT
        p.id AS id,
        p.name AS name,
        p.age AS age,
        r.ownerId AS ownerId,
        r.model AS model
      FROM
        Person p
        INNER JOIN Robot r ON p.id = r.ownerId
      """
    ),
    "from + join + leftJoin -> Custom(p, r)" to cr(
      """
      SELECT
        p.id AS id,
        p.name AS name,
        p.age AS age,
        r.ownerId AS ownerId,
        r.model AS model
      FROM
        Person p
        INNER JOIN Robot r ON p.id = r.ownerId
        LEFT JOIN Address a ON p.id = a.ownerId
      """
    ),
    "from + leftJoin -> Custom(p, r)" to cr(
      """
      SELECT
        p.id AS id,
        p.name AS name,
        p.age AS age,
        r.ownerId AS ownerId,
        r.model AS model
      FROM
        Person p
        LEFT JOIN Robot r ON p.id = r.ownerId
      """
    ),
    "from + join + where" to cr(
      """
      SELECT
        p.name AS name
      FROM
        Person p
        INNER JOIN Robot r ON p.id = r.ownerId
      WHERE
        p.name = 'Joe'
      """
    ),
    "from + sort(Asc,Desc)" to cr(
      """
      SELECT
        p.name AS name
      FROM
        Person p
      ORDER BY
        p.age ASC,
        p.name DESC
      """
    ),
    "from + sort(Asc)" to cr(
      """
      SELECT
        p.name AS name
      FROM
        Person p
      ORDER BY
        p.age ASC
      """
    ),
    "from + groupBy" to cr(
      """
      SELECT
        p.age AS age
      FROM
        Person p
      GROUP BY
        p.age
      """
    ),
    "from + groupBy(a, b)" to cr(
      """
      SELECT
        p.age AS age
      FROM
        Person p
      GROUP BY
        p.age,
        p.name
      """
    ),
  )
}
