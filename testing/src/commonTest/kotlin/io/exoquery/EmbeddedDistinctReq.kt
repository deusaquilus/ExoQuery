package io.exoquery

import io.exoquery.sql.PostgresDialect

class EmbeddedDistinctReq: GoldenSpecDynamic(EmbeddedDistinctReqGoldenDynamic, Mode.ExoGoldenTest(), {
  "queries with embedded entities should" - {

//    "function property inside of nested distinct queries" in {
//      case class Parent(id: Int, emb1: Emb)
//      case class Emb(a: Int, b: Int)
//      val q = quote {
//        query[Emb].map(e => Parent(1, e)).distinct
//      }
//      ctx.run(q).string mustEqual "SELECT DISTINCT 1 AS id, e.a, e.b FROM Emb e"
//    }
//
//    "function property inside of nested distinct queries - tuple" in {
//      case class Parent(id: Int, emb1: Emb)
//      case class Emb(a: Int, b: Int)
//      val q = quote {
//        query[Emb].map(e => Parent(1, e)).distinct.map(p => (2, p)).distinct
//      }
//      ctx.run(q).string mustEqual "SELECT DISTINCT 2 AS _1, e.id, e.emb1a AS a, e.emb1b AS b FROM (SELECT DISTINCT 1 AS id, e.a AS emb1a, e.b AS emb1b FROM Emb e) AS e"
//    }
//

    "function property inside of nested distinct queries" {
      data class Emb(val a: Int, val b: Int)
      data class Parent(val id: Int, val emb1: Emb)

      val q = capture {
        Table<Emb>().map { e -> Parent(1, e) }.distinct()
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }

    "function property inside of nested distinct queries - tuple" {
      data class Emb(val a: Int, val b: Int)
      data class Parent(val id: Int, val emb1: Emb)

      val q = capture {
        Table<Emb>().map { e -> Parent(1, e) }.distinct().map { p -> 2 to p }.distinct()
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }

//    "function property inside of nested distinct queries through tuples" in {
//      case class Parent(id: Int, emb1: Emb)
//      case class Emb(a: Int, b: Int)
//      val q = quote {
//        query[Emb].map(e => (1, e)).distinct.map(t => Parent(t._1, t._2)).distinct
//      }
//      ctx.run(q).string mustEqual "SELECT DISTINCT e._1 AS id, e._2a AS a, e._2b AS b FROM (SELECT DISTINCT 1 AS _1, e.a AS _2a, e.b AS _2b FROM Emb e) AS e"
//    }

    "function property inside of nested distinct queries through tuples" {
      data class Emb(val a: Int, val b: Int)
      data class Parent(val id: Int, val emb1: Emb)

      val q = capture {
        Table<Emb>().map { e -> 1 to e }.distinct().map { t -> Parent(t.first, t.second) }.distinct()
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }

    "function property inside of nested distinct queries - twice" {
      data class Emb(val a: Int, val b: Int)
      data class Parent(val idP: Int, val emb1: Emb)
      data class Grandparent(val idG: Int, val par: Parent)

      val q = capture {
        Table<Emb>().map { e -> Parent(1, e) }.distinct().map { p -> Grandparent(2, p) }.distinct()
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }

    "function property inside of nested distinct queries - twice - into tuple" {
      data class Emb(val a: Int, val b: Int)
      data class Parent(val idP: Int, val emb1: Emb)
      data class Grandparent(val idG: Int, val par: Parent)

      val q = capture {
        // Not right result. Need to debug this
        Table<Emb>().map { e -> Parent(1, e) }.distinct().map { p -> Grandparent(2, p) }.distinct().map { g -> 3 to g }
          .distinct()
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.build<PostgresDialect>(), "SQL")
    }
  }
})

// Scala:
//  "queries with embedded entities should" - {
//    "function property inside of nested distinct queries - twice" in {
//      case class Grandparent(idG: Int, par: Parent)
//      case class Parent(idP: Int, emb1: Emb)
//      case class Emb(a: Int, b: Int)
//      val q = quote {
//        query[Emb].map(e => Parent(1, e)).distinct.map(p => Grandparent(2, p)).distinct
//      }
//      ctx.run(q).string mustEqual "SELECT DISTINCT 2 AS idG, e.idP, e.emb1a AS a, e.emb1b AS b FROM (SELECT DISTINCT 1 AS idP, e.a AS emb1a, e.b AS emb1b FROM Emb e) AS e"
//    }
//
//    "function property inside of nested distinct queries - twice - into tuple" in {
//      case class Grandparent(idG: Int, par: Parent)
//      case class Parent(idP: Int, emb1: Emb)
//      case class Emb(a: Int, b: Int)
//      val q = quote {
//        query[Emb].map(e => Parent(1, e)).distinct.map(p => Grandparent(2, p)).distinct.map(g => (3, g)).distinct
//      }
//      ctx.run(q).string mustEqual "SELECT DISTINCT 3 AS _1, p.idG, p.paridP AS idP, p.paremb1a AS a, p.paremb1b AS b FROM (SELECT DISTINCT 2 AS idG, p.idP AS paridP, p.emb1a AS paremb1a, p.emb1b AS paremb1b FROM (SELECT DISTINCT 1 AS idP, e.a AS emb1a, e.b AS emb1b FROM Emb e) AS p) AS p"
//    }
//  }
