package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object BooleanLiteralSupportSpecGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "value-fy boolean expression where needed/condition/XR" to kt(
      "Table(Ent).map { e -> Tuple(first = e.name, second = if (e.b == e.bb) e.bc else e.b == e.bb) }"
    ),
    "value-fy boolean expression where needed/condition/SQL" to cr(
      "SELECT e.name AS first, CASE WHEN e.b = e.bb THEN e.bc ELSE CASE WHEN e.b = e.bb THEN 1 ELSE 0 END END AS second FROM Ent e"
    ),
    "value-fy boolean expression where needed/map-clause/XR" to kt(
      "Table(Ent).map { e -> e.bb == true }"
    ),
    "value-fy boolean expression where needed/map-clause/SQL" to cr(
      "SELECT e.bb AS value FROM Ent e"
    ),
    "value-fy boolean expression where needed/map-clause with int/XR" to kt(
      "Table(Ent).map { e -> e.num > 10 }"
    ),
    "value-fy boolean expression where needed/map-clause with int/SQL" to cr(
      "SELECT CASE WHEN e.num > 10 THEN 1 ELSE 0 END AS value FROM Ent e"
    ),
    "value-fy boolean expression where needed/tuple/XR" to kt(
      "Table(Ent).map { e -> Tuple(first = foo, second = e.bb == true) }"
    ),
    "value-fy boolean expression where needed/tuple/SQL" to cr(
      "SELECT 'foo' AS first, e.bb AS second FROM Ent e"
    ),
    "value-fy boolean expression where needed/tuple-multi/XR" to kt(
      "Table(Ent).map { e -> Tuple(first = Tuple(first = e.bb == true, second = e.bc == false), second = e.num > 1) }"
    ),
    "value-fy boolean expression where needed/tuple-multi/SQL" to cr(
      "SELECT e.bb AS first, CASE WHEN e.bc = 0 THEN 1 ELSE 0 END AS second, CASE WHEN e.num > 1 THEN 1 ELSE 0 END AS second FROM Ent e"
    ),
    "value-fy boolean expression where needed/case-class/XR" to kt(
      "Table(Ent).map { e -> Status(name = foo, value = e.bb == true) }"
    ),
    "value-fy boolean expression where needed/case-class/SQL" to cr(
      "SELECT 'foo' AS name, e.bb AS value FROM Ent e"
    ),
    "sql/expressify asCondition/filter-clause/XR" to kt(
      """Table(Ent).filter { e -> free(", ${'$'}{e.i},  > 123").asCondition() }"""
    ),
    "sql/expressify asCondition/filter-clause/SQL" to cr(
      "SELECT e.name, e.i, e.b FROM Ent e WHERE e.i > 123"
    ),
    "sql/expressify asCondition/pure filter-clause/XR" to kt(
      """Table(Ent).filter { e -> free(", ${'$'}{e.i},  > 123").asPureCondition() }"""
    ),
    "sql/expressify asCondition/pure filter-clause/SQL" to cr(
      "SELECT e.name, e.i, e.b FROM Ent e WHERE e.i > 123"
    ),
    "sql/expressify asCondition/map-clause/XR" to kt(
      """Table(Ent).map { e -> free(", ${'$'}{e.i},  > 123").asCondition() }"""
    ),
    "sql/expressify asCondition/map-clause/SQL" to cr(
      "SELECT CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS value FROM Ent e"
    ),
    "sql/expressify asCondition/distinct map-clause/XR" to kt(
      """Table(Ent).map { e -> Tuple(first = foo, second = free(", ${'$'}{e.i},  > 123").asCondition()) }.distinct.map { r -> Tuple(first = baz, second = r.second) }"""
    ),
    "sql/expressify asCondition/distinct map-clause/SQL" to cr(
      "SELECT 'baz' AS first, CASE WHEN r.second THEN 1 ELSE 0 END AS second FROM (SELECT DISTINCT x.first, x.second FROM (SELECT 'foo' AS first, CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS second FROM Ent e) AS x) AS r"
    ),
    "sql/expressify asCondition/distinct tuple map-clause/XR" to kt(
      """Table(Ent).map { e -> Tuple(first = foo, second = free(", ${'$'}{e.i},  > 123").asPureCondition()) }.distinct"""
    ),
    "sql/expressify asCondition/distinct tuple map-clause/SQL" to cr(
      "SELECT DISTINCT 'foo' AS first, CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS second FROM Ent e"
    ),
    "sql/expressify asCondition/pure map-clause/XR" to kt(
      """Table(Ent).map { e -> free(", ${'$'}{e.i},  > 123").asPureCondition() }"""
    ),
    "sql/expressify asCondition/pure map-clause/SQL" to cr(
      "SELECT CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS value FROM Ent e"
    ),
    "sql/expressify asCondition/pure distinct map-clause/XR" to kt(
      """Table(Ent).map { e -> Tuple(first = foo, second = free(", ${'$'}{e.i},  > 123").asPureCondition()) }.distinct.map { r -> Tuple(first = baz, second = r.second) }"""
    ),
    "sql/expressify asCondition/pure distinct map-clause/SQL" to cr(
      "SELECT 'baz' AS first, CASE WHEN r.second THEN 1 ELSE 0 END AS second FROM (SELECT DISTINCT 'foo' AS first, CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS second FROM Ent e) AS r"
    ),
    "sql/expressify asCondition/pure map-clause - double element/XR" to kt(
      """Table(Ent).map { e -> free(", ${'$'}{e.i},  > 123").asPureCondition() }.distinct.map { r -> Tuple(first = r, second = r) }"""
    ),
    "sql/expressify asCondition/pure map-clause - double element/SQL" to cr(
      "SELECT r.value AS first, r.value AS second FROM (SELECT DISTINCT CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS value FROM Ent e) AS r"
    ),
    "sql/valuefy normally/filter-clause/XR" to kt(
      """Table(Ent).filter { e -> free("SomeUdf(, ${'$'}{e.i}, )").invoke() }"""
    ),
    "sql/valuefy normally/filter-clause/SQL" to cr(
      "SELECT e.name, e.i, e.b FROM Ent e WHERE 1 = SomeUdf(e.i)"
    ),
    "sql/valuefy normally/map-clause - impure/XR" to kt(
      """Table(Ent).map { e -> free("SomeUdf(, ${'$'}{e.i}, )").invoke() }.map { x -> x + 1 }"""
    ),
    "sql/valuefy normally/map-clause - impure/SQL" to cr(
      "SELECT e.value + 1 AS value FROM (SELECT SomeUdf(e.i) AS value FROM Ent e) AS e"
    ),
    "sql/valuefy normally/map-clause - pure/XR" to kt(
      """Table(Ent).map { e -> free("SomeUdf(, ${'$'}{e.i}, )").asPure() }.map { x -> x + 1 }"""
    ),
    "sql/valuefy normally/map-clause - pure/SQL" to cr(
      "SELECT SomeUdf(e.i) + 1 AS value FROM Ent e"
    ),
    "do not expressify string transforming operations/first parameter/XR" to kt(
      """Table(Product).filter { p -> TagP("0").toInt_MC() == p.sku }"""
    ),
    "do not expressify string transforming operations/first parameter/SQL" to cr(
      "SELECT p.id, p.desc, p.sku FROM Product p WHERE CAST({0:1} AS INTEGER) = p.sku",
      "0" to "1"
    ),
    "do not expressify string transforming operations/second parameter/XR" to kt(
      """Table(Product).filter { p -> p.sku == TagP("0").toInt_MC() }"""
    ),
    "do not expressify string transforming operations/second parameter/SQL" to cr(
      "SELECT p.id, p.desc, p.sku FROM Product p WHERE p.sku = CAST({0:1} AS INTEGER)",
      "0" to "1"
    ),
    "do not expressify string transforming operations/both parameters/XR" to kt(
      """Table(Product).filter { p -> TagP("0").toInt_MC() == TagP("1").toInt_MC() }"""
    ),
    "do not expressify string transforming operations/both parameters/SQL" to cr(
      "SELECT p.id, p.desc, p.sku FROM Product p WHERE CAST({0:2} AS INTEGER) = CAST({1:1} AS INTEGER)",
      "0" to "2", "1" to "1"
    ),
    "joins/for-comprehension with constant/XR" to kt(
      "select { val t1 = from(Table(TestEntity)); val t2 = join(Table(TestEntity)) { true }; Tuple(first = t1, second = t2) }"
    ),
    "joins/for-comprehension with constant/SQL" to cr(
      "SELECT t1.s, t1.i, t1.l, t1.o, t1.b, t.s, t.i, t.l, t.o, t.b FROM TestEntity t1 INNER JOIN TestEntity t ON 1 = 1"
    ),
    "joins/for-comprehension with field/XR" to kt(
      "select { val t1 = from(Table(TestEntity)); val t2 = join(Table(TestEntity)) { t.b }; Tuple(first = t1, second = t2) }"
    ),
    "joins/for-comprehension with field/SQL" to cr(
      "SELECT t1.s, t1.i, t1.l, t1.o, t1.b, t.s, t.i, t.l, t.o, t.b FROM TestEntity t1 INNER JOIN TestEntity t ON 1 = t.b"
    ),
    "optionals/exists/XR" to kt(
      "Table(TestEntity).filter { t -> if ({ val tmp0_elvis_lhs = t.o; if (tmp0_elvis_lhs == null) false else tmp0_elvis_lhs }) false else true }.map { t -> Tuple(first = t.b, second = true) }"
    ),
    "optionals/exists/SQL" to cr(
      "SELECT t.b AS first, 1 AS second FROM TestEntity t WHERE NOT (CASE WHEN t.o IS NULL THEN 0 ELSE t.o END)"
    ),
    "optionals/exists - lifted contains/XR" to kt(
      "Table(TestEntity).filter { t -> { val tmp1_elvis_lhs = { val tmp0_safe_receiver = t.o; if (tmp0_safe_receiver == null) null else { it -> it == true }.apply(tmp0_safe_receiver) }; if (tmp1_elvis_lhs == null) false else tmp1_elvis_lhs } }.map { t -> Tuple(first = t.b, second = true) }"
    ),
    "optionals/exists - lifted contains/SQL" to cr(
      "SELECT t.b AS first, 1 AS second FROM TestEntity t WHERE t.o IS NULL AND 1 = 0 OR t.o IS NOT NULL AND 1 = t.o"
    ),
    "optionals/exists - lifted not contains/XR" to kt(
      """Table(TestEntity).filter { t -> { val tmp1_elvis_lhs = { val tmp0_safe_receiver = t.o; if (tmp0_safe_receiver == null) null else { it -> TagP("0") }.apply(tmp0_safe_receiver) }; if (tmp1_elvis_lhs == null) false else tmp1_elvis_lhs } }.map { t -> Tuple(first = t.b, second = true) }"""
    ),
    "optionals/exists - lifted not contains/SQL" to cr(
      "SELECT t.b AS first, 1 AS second FROM TestEntity t WHERE 1 = CASE WHEN CASE WHEN t.o IS NULL THEN null ELSE {1:true} END IS NULL THEN 0 ELSE CASE WHEN t.o IS NULL THEN null ELSE {1:true} END END",
      "1" to "true", "1" to "true"
    ),
    "optionals/exists - lifted complex/XR" to kt(
      """Table(TestEntity).filter { t -> { val tmp1_elvis_lhs = { val tmp0_safe_receiver = t.o; if (tmp0_safe_receiver == null) null else { it -> if (TagP("1")) TagP("2") else TagP("0") }.apply(tmp0_safe_receiver) }; if (tmp1_elvis_lhs == null) false else tmp1_elvis_lhs } }.map { t -> Tuple(first = t.b, second = true) }"""
    ),
    "optionals/exists - lifted complex/SQL" to cr(
      "SELECT t.b AS first, 1 AS second FROM TestEntity t WHERE 1 = CASE WHEN CASE WHEN t.o IS NULL THEN null ELSE CASE WHEN {3:false} THEN {4:false} ELSE {5:true} END END IS NULL THEN 0 ELSE CASE WHEN t.o IS NULL THEN null ELSE CASE WHEN {3:false} THEN {4:false} ELSE {5:true} END END END",
      "3" to "false", "4" to "false", "5" to "true", "3" to "false", "4" to "false", "5" to "true"
    ),
  )
}
