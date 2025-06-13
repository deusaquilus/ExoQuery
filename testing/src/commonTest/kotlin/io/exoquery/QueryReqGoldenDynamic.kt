package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object QueryReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "basic query/XR" to kt(
      "Table(Person)"
    ),
    "basic query" to cr(
      "SELECT x.id, x.name, x.age FROM Person x"
    ),
    "query with map/XR" to kt(
      "Table(Person).map { p -> p.name }"
    ),
    "query with map" to cr(
      "SELECT p.name AS value FROM Person p"
    ),
    "union with impure free - should not collapse/XR" to kt(
      """select { val u = from(Table(Person).filter { p -> p.name == Joe }.union(Table(Person).filter { p -> p.name == Jack })); val a = join(Table(Address)) { a.ownerId == u.id }; free("stuff(, ${'$'}{u.name}, )").invoke() }"""
    ),
    "union with impure free - should not collapse" to cr(
      "SELECT stuff(u.name) AS value FROM ((SELECT p.id, p.name, p.age FROM Person p WHERE p.name = 'Joe') UNION (SELECT p1.id, p1.name, p1.age FROM Person p1 WHERE p1.name = 'Jack')) AS u INNER JOIN Address a ON a.ownerId = u.id"
    ),
    "union with pure function - should collapse/XR" to kt(
      "select { val u = from(Table(Person).filter { p -> p.name == Joe }.union(Table(Person).filter { p -> p.name == Jack })); val a = join(Table(Address)) { a.ownerId == u.id }; u.name.uppercase_MC() }"
    ),
    "union with pure function - should collapse" to cr(
      "(SELECT UPPER(p.name) AS value FROM Person p INNER JOIN Address a ON a.ownerId = p.id WHERE p.name = 'Joe') UNION (SELECT UPPER(p1.name) AS value FROM Person p1 INNER JOIN Address a ON a.ownerId = p1.id WHERE p1.name = 'Jack')"
    ),
    "union with aggregation - shuold not collapse/XR" to kt(
      "select { val u = from(Table(Person).filter { p -> p.name == Joe }.union(Table(Person).filter { p -> p.name == Jack })); val a = join(Table(Address)) { a.ownerId == u.id }; avg_GC(u.age) }"
    ),
    "union with aggregation - shuold not collapse" to cr(
      "SELECT avg(u.age) AS value FROM ((SELECT p.id, p.name, p.age FROM Person p WHERE p.name = 'Joe') UNION (SELECT p1.id, p1.name, p1.age FROM Person p1 WHERE p1.name = 'Jack')) AS u INNER JOIN Address a ON a.ownerId = u.id"
    ),
    "map with aggregation/XR" to kt(
      "Table(Person).map { p -> avg_GC(p.age) }"
    ),
    "map with aggregation" to cr(
      "SELECT avg(p.age) AS value FROM Person p"
    ),
    "map with count/XR" to kt(
      "Table(Person).map { p -> count_GC(p.age) }"
    ),
    "map with count" to cr(
      "SELECT count(p.age) AS value FROM Person p"
    ),
    "map with count star/XR" to kt(
      "Table(Person).map { p -> COUNT_STAR_GC() }"
    ),
    "map with count star" to cr(
      "SELECT count(*) AS value FROM Person p"
    ),
    "map with count distinct/XR" to kt(
      "Table(Person).map { p -> countDistinct_GC(p.name, p.age) }"
    ),
    "map with count distinct" to cr(
      "SELECT count(DISTINCT p.name, p.age) AS value FROM Person p"
    ),
    "map with stddev/XR" to kt(
      "Table(Person).map { p -> stddev_GC(p.age) }"
    ),
    "map with stddev" to cr(
      "SELECT stddev(p.age) AS value FROM Person p"
    ),
    "query with filter/XR" to kt(
      "Table(Person).filter { p -> p.age > 18 }"
    ),
    "query with filter" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.age > 18"
    ),
    "query with where/XR" to kt(
      "Table(Person).filter { x -> x.age > 18 }"
    ),
    "query with where" to cr(
      "SELECT x.id, x.name, x.age FROM Person x WHERE x.age > 18"
    ),
    "filter + correlated isEmpty/XR" to kt(
      "Table(Person).filter { p -> p.age > Table(Person).map { p -> p.age }.avg_MC() }"
    ),
    "filter + correlated isEmpty" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.age > (SELECT avg(p1.age) FROM Person p1)"
    ),
    "filter + correlated + value/XR" to kt(
      "Table(Person).filter { p -> p.age.toDouble_MCS() > Table(Person).map { p -> avg_GC(p.age) - min_GC(p.age) }.toExpr }"
    ),
    "filter + correlated + value" to cr(
      "SELECT p.id, p.name, p.age FROM Person p WHERE p.age > (SELECT avg(p1.age) - min(p1.age) FROM Person p1)"
    ),
    "query with flatMap/XR" to kt(
      "Table(Person).flatMap { p -> Table(Address).filter { a -> a.ownerId == p.id } }"
    ),
    "query with flatMap" to cr(
      "SELECT a.ownerId, a.street, a.city FROM Person p, Address a WHERE a.ownerId = p.id"
    ),
    "query with union/XR" to kt(
      "Table(Person).filter { p -> p.age > 18 }.union(Table(Person).filter { p -> p.age < 18 })"
    ),
    "query with union" to cr(
      "(SELECT p.id, p.name, p.age FROM Person p WHERE p.age > 18) UNION (SELECT p1.id, p1.name, p1.age FROM Person p1 WHERE p1.age < 18)"
    ),
    "query with unionAll/XR" to kt(
      "Table(Person).filter { p -> p.age > 18 }.unionAll(Table(Person).filter { p -> p.age < 18 })"
    ),
    "query with unionAll" to cr(
      "(SELECT p.id, p.name, p.age FROM Person p WHERE p.age > 18) UNION ALL (SELECT p1.id, p1.name, p1.age FROM Person p1 WHERE p1.age < 18)"
    ),
    "query with unionAll - symbolic/XR" to kt(
      "Table(Person).filter { p -> p.age > 18 }.unionAll(Table(Person).filter { p -> p.age < 18 })"
    ),
    "query with unionAll - symbolic" to cr(
      "(SELECT p.id, p.name, p.age FROM Person p WHERE p.age > 18) UNION ALL (SELECT p1.id, p1.name, p1.age FROM Person p1 WHERE p1.age < 18)"
    ),
    "query with surrounding free/XR" to kt(
      """free(", ${'$'}{Table(Person).filter { p -> p.name == Joe }},  FOR UPDATE").asPure()"""
    ),
    "query with surrounding free" to cr(
      "SELECT p.id AS id, p.name AS name, p.age AS age FROM Person p WHERE p.name = 'Joe' FOR UPDATE"
    ),
    "query with free in captured function/XR" to kt(
      """{ v -> free(", ${'$'}v,  FOR UPDATE").asPure() }.toQuery.apply(Table(Person).filter { p -> p.age > 21 })"""
    ),
    "query with free in captured function" to cr(
      "(SELECT p.id AS id, p.name AS name, p.age AS age FROM Person p WHERE p.age > 21) FOR UPDATE"
    ),
    "query with free in captured function - receiver position/XR" to kt(
      """{ this -> free(", ${'$'}this,  FOR UPDATE").asPure() }.toQuery.apply(Table(Person).filter { p -> p.age > 21 })"""
    ),
    "query with free in captured function - receiver position" to cr(
      "(SELECT p.id AS id, p.name AS name, p.age AS age FROM Person p WHERE p.age > 21) FOR UPDATE"
    ),
    "flat joins inside subquery/where/XR" to kt(
      "select { val p = from(Table(Person)); val innerRobot = join(select { val r = from(Table(Robot)); where(r.ownerId == p.id); Tuple(first = r.name, second = r.ownerId) }) { r.second == p.id }; Tuple(first = p, second = innerRobot) }"
    ),
    "flat joins inside subquery/where" to cr(
      "SELECT p.id, p.name, p.age, r.first, r.second FROM Person p INNER JOIN (SELECT r.name AS first, r.ownerId AS second FROM Robot r WHERE r.ownerId = p.id) AS r ON r.second = p.id"
    ),
    "flat joins inside subquery/groupBy/XR" to kt(
      "select { val p = from(Table(Person)); val innerRobot = join(select { val r = from(Table(Robot)); groupBy(r.ownerId); Tuple(first = r.name, second = r.ownerId) }) { r.second == p.id }; Tuple(first = p, second = innerRobot) }"
    ),
    "flat joins inside subquery/groupBy" to cr(
      "SELECT p.id, p.name, p.age, r.first, r.second FROM Person p INNER JOIN (SELECT r.name AS first, r.ownerId AS second FROM Robot r GROUP BY r.ownerId) AS r ON r.second = p.id"
    ),
    "flat joins inside subquery/sortBy/XR" to kt(
      "select { val p = from(Table(Person)); val innerRobot = join(select { val r = from(Table(Robot)); sortBy(r.name to Desc); Tuple(first = r.name, second = r.ownerId) }) { r.second == p.id }; Tuple(first = p, second = innerRobot) }"
    ),
    "flat joins inside subquery/sortBy" to cr(
      "SELECT p.id, p.name, p.age, r.first, r.second FROM Person p INNER JOIN (SELECT r.name AS first, r.ownerId AS second FROM Robot r ORDER BY r.name DESC) AS r ON r.second = p.id"
    ),
  )
}
