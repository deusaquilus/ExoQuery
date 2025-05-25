package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object SelectClauseQueryReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "table.emb?.field/join(p.name?.first)/XR" to kt(
      "select { val p = from(Table(Person)); val r = join(Table(Robot)) { { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first } == r.ownerFirstName }; Tuple(first = p, second = r) }"
    ),
    "table.emb?.field/join(p.name?.first)/SQL" to cr(
      "SELECT p.first, p.last, p.age, r.ownerFirstName, r.model FROM Person p INNER JOIN Robot r ON p.first = r.ownerFirstName"
    ),
    "table.emb?.field/join(p.name?.first ?: alternative)/XR" to kt(
      "select { val p = from(Table(Person)); val r = join(Table(Robot)) { { val tmp1_elvis_lhs = { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first }; if (tmp1_elvis_lhs == null) defaultName else tmp1_elvis_lhs } == r.ownerFirstName }; Tuple(first = p, second = r) }"
    ),
    "table.emb?.field/join(p.name?.first ?: alternative)/SQL" to cr(
      "SELECT p.first, p.last, p.age, r.ownerFirstName, r.model FROM Person p INNER JOIN Robot r ON CASE WHEN p.first IS NULL THEN 'defaultName' ELSE p.first END = r.ownerFirstName"
    ),
    "table.emb?.field/joinLeft(p.name?.first ?: alternative)/XR" to kt(
      "select { val p = from(Table(Person)); val r = leftJoin(Table(Robot)) { { val tmp1_elvis_lhs = { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first }; if (tmp1_elvis_lhs == null) defaultName else tmp1_elvis_lhs } == r.ownerFirstName }; Tuple(first = p.name, second = { val tmp0_safe_receiver = r; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.model }) }"
    ),
    "table.emb?.field/joinLeft(p.name?.first ?: alternative)/SQL" to cr(
      "SELECT p.first, p.last, r.model AS second FROM Person p LEFT JOIN Robot r ON CASE WHEN p.first IS NULL THEN 'defaultName' ELSE p.first END = r.ownerFirstName"
    ),
    "table.emb?.field/joinLeft(p.name?.first ?: alternative) -> r ?: alternative/XR" to kt(
      "select { val p = from(Table(Person)); val r = leftJoin(Table(Robot)) { { val tmp1_elvis_lhs = { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first }; if (tmp1_elvis_lhs == null) defaultName else tmp1_elvis_lhs } == r.ownerFirstName }; Tuple(first = p.name, second = { val tmp0_safe_receiver = r; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.model }) }"
    ),
    "table.emb?.field/joinLeft(p.name?.first ?: alternative) -> r ?: alternative/SQL" to cr(
      "SELECT p.first, p.last, r.model AS second FROM Person p LEFT JOIN Robot r ON CASE WHEN p.first IS NULL THEN 'defaultName' ELSE p.first END = r.ownerFirstName"
    ),
    "table.emb?.field/joinLeft(p.name?.first ?: alternative) -> p ?: alternative/XR" to kt(
      "select { val r = from(Table(Robot)); val p = leftJoin(Table(Person)) { r.ownerFirstName == { val tmp1_elvis_lhs = { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first }; if (tmp1_elvis_lhs == null) defaultName else tmp1_elvis_lhs } }; Tuple(first = { val tmp2_elvis_lhs = { val tmp1_safe_receiver = { val tmp0_safe_receiver = p; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.name }; if (tmp1_safe_receiver == null) null else tmp1_safe_receiver.first }; if (tmp2_elvis_lhs == null) foo else tmp2_elvis_lhs }, second = r.model) }"
    ),
    "table.emb?.field/joinLeft(p.name?.first ?: alternative) -> p ?: alternative/SQL" to cr(
      "SELECT CASE WHEN p.first IS NULL THEN 'foo' ELSE p.first END AS first, r.model AS second FROM Robot r LEFT JOIN Person p ON r.ownerFirstName = CASE WHEN p.first IS NULL THEN 'defaultName' ELSE p.first END"
    ),
    "table.emb?.field/join + aggregation/XR" to kt(
      "select { val p = from(Table(Person)); val r = join(Table(Robot)) { { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first } == r.ownerFirstName }; groupBy(TupleA2(first = { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first }, second = r.model)); Tuple(first = { val tmp1_safe_receiver = p.name; if (tmp1_safe_receiver == null) null else tmp1_safe_receiver.first }, second = r.model) }"
    ),
    "table.emb?.field/join + aggregation/SQL" to cr(
      "SELECT p.first, r.model AS second FROM Person p INNER JOIN Robot r ON p.first = r.ownerFirstName GROUP BY p.first, r.model"
    ),
    "table.emb?.field/sortBy/XR" to kt(
      "select { val p = from(Table(Person)); sortBy(p.age to Asc, { val tmp0_safe_receiver = p.name; if (tmp0_safe_receiver == null) null else tmp0_safe_receiver.first } to Desc); Tuple(first = p.name, second = p.age) }"
    ),
    "table.emb?.field/sortBy/SQL" to cr(
      "SELECT p.first, p.last, p.age AS second FROM Person p ORDER BY p.age ASC, p.first DESC"
    ),
    "join + where + groupBy + sortBy/XR" to kt(
      "select { val p = from(Table(Person)); val a = join(Table(Address)) { p.id == a.ownerId }; where(p.age > 18); groupBy(TupleA2(first = p.age, second = a.street)); sortBy(p.age to Asc, a.street to Desc); Tuple(first = p, second = a) }"
    ),
    "join + where + groupBy + sortBy/SQL" to cr(
      "SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city FROM Person p INNER JOIN Address a ON p.id = a.ownerId WHERE p.age > 18 GROUP BY p.age, a.street ORDER BY p.age ASC, a.street DESC"
    ),
  )
}
