package io.exoquery

class BooleanLiteralSupportSpec: GoldenSpecDynamic(BooleanLiteralSupportSpecGoldenDynamic, Mode.ExoGoldenTest(), {
  "value-fy boolean expression where needed" - {
    data class Ent(val name: String, val b: Boolean, val bb: Boolean, val bc: Boolean, val num: Int)
    data class Status(val name: String, val value: Boolean)

    "filter - simple" {
      val q = capture {
        Table<Ent>().filter { e -> e.b }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "where - simple" {
      val q = capture.select {
        val e = from(Table<Ent>())
        where { e.b }
        e.name
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "where - combined" {
      val q = capture.select {
        val e = from(Table<Ent>())
        where { e.b || e.name == "Joe" }
        e.name
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "where - combined complex 1" {
      val q = capture.select {
        val e = from(Table<Ent>())
        where { e.b || e.name == "Joe" || e.bb }
        e.name
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "where - combined complex 2" {
      val q = capture.select {
        val e = from(Table<Ent>())
        where { e.b || if (e.bb) e.bc || e.b else e.num > 1 }
        e.name
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }


    "condition" {
      val q = capture {
        Table<Ent>().map { e -> e.name to if (e.b == e.bb) e.bc else e.b == e.bb }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "map-clause" {
      val q = capture {
        Table<Ent>().map { e -> e.bb == true }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "map-clause with int" {
      val q = capture {
        Table<Ent>().map { e -> e.num > 10 }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "tuple" {
      val q = capture {
        Table<Ent>().map { e -> "foo" to (e.bb == true) }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "tuple-multi" {
      val q = capture {
        Table<Ent>().map { e -> (e.bb == true) to (e.bc == false) to (e.num > 1) }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "case-class" {
      val q = capture {
        Table<Ent>().map { e -> Status("foo", e.bb == true) }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }
  }

  //  "sql" - testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//    import ctx._
//
//    "expressify asCondition" - {
//      case class Ent(name: String, i: Int, b: Boolean)
//
//      "filter-clause" in {
//        val q = quote {
//          query[Ent].filter(e => sql"${e.i} > 123".asCondition)
//        }
//        ctx.run(q).string mustEqual
//          "SELECT e.name, e.i, e.b FROM Ent e WHERE e.i > 123"
//      }
//
//      "pure filter-clause" in {
//        val q = quote {
//          query[Ent].filter(e => sql"${e.i} > 123".pure.asCondition)
//        }
//        ctx.run(q).string mustEqual
//          "SELECT e.name, e.i, e.b FROM Ent e WHERE e.i > 123"
//      }
//
//      "map-clause" in {
//        val q = quote {
//          query[Ent].map(e => sql"${e.i} > 123".asCondition) // hello
//        }
//        ctx.run(q).string mustEqual
//          "SELECT CASE WHEN e.i > 123 THEN 1 ELSE 0 END FROM Ent e"
//      }
//
//      "distinct map-clause" in {
//        val q = quote {
//          query[Ent].map(e => ("foo", sql"${e.i} > 123".asCondition)).distinct.map(r => ("baz", r._2))
//        }
//        ctx.run(q).string mustEqual
//          "SELECT 'baz' AS _1, e._2 FROM (SELECT DISTINCT x._1, x._2 FROM (SELECT 'foo' AS _1, CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS _2 FROM Ent e) AS x) AS e"
//      }
//
//      "distinct tuple map-clause" in {
//        val q = quote {
//          query[Ent].map(e => ("foo", sql"${e.i} > 123".asCondition)).distinct
//        }
//        ctx.run(q).string mustEqual
//          "SELECT DISTINCT x._1, x._2 FROM (SELECT 'foo' AS _1, CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS _2 FROM Ent e) AS x"
//      }
//
//      "pure map-clause" in {
//        val q = quote {
//          query[Ent].map(e => sql"${e.i} > 123".pure.asCondition)
//        }
//        ctx.run(q).string mustEqual
//          "SELECT CASE WHEN e.i > 123 THEN 1 ELSE 0 END FROM Ent e"
//      }
//
//      "pure distinct map-clause" in {
//        val q = quote {
//          query[Ent].map(e => ("foo", sql"${e.i} > 123".pure.asCondition)).distinct.map(r => ("baz", r._2))
//        }
//        println(io.getquill.util.Messages.qprint(q.ast))
//        ctx.run(q).string mustEqual
//          "SELECT 'baz' AS _1, e._2 FROM (SELECT DISTINCT 'foo' AS _1, CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS _2 FROM Ent e) AS e"
//      }
//
//      "pure map-clause - double element" in {
//        val q = quote {
//          query[Ent].map(e => sql"${e.i} > 123".pure.asCondition).distinct.map(r => (r, r))
//        }.dynamic
//        ctx.run(q).string mustEqual
//          "SELECT e._1, e._1 AS _2 FROM (SELECT DISTINCT CASE WHEN e.i > 123 THEN 1 ELSE 0 END AS _1 FROM Ent e) AS e"
//      }
//    }
//

  "sql" - {
    data class Ent(val name: String, val i: Int, val b: Boolean)

    "expressify asCondition" - {
      "filter-clause" {
        val q = capture {
          Table<Ent>().filter { e -> free("${e.i} > 123").asConditon() }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "pure filter-clause" {
        val q = capture {
          Table<Ent>().filter { e -> free("${e.i} > 123").asPureConditon() }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "map-clause" {
        val q = capture {
          Table<Ent>().map { e -> free("${e.i} > 123").asConditon() }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "distinct map-clause" {
        val q = capture {
          Table<Ent>()
            .map { e -> "foo" to free("${e.i} > 123").asConditon() }
            .distinct()
            .map { r -> "baz" to r.second }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "distinct tuple map-clause" {
        val q = capture {
          Table<Ent>()
            .map { e -> "foo" to free("${e.i} > 123").asPureConditon() }
            .distinct()
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "pure map-clause" {
        val q = capture {
          Table<Ent>().map { e -> free("${e.i} > 123").asPureConditon() }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "pure distinct map-clause" {
        val q = capture {
          Table<Ent>()
            .map { e -> "foo" to free("${e.i} > 123").asPureConditon() }
            .distinct()
            .map { r -> "baz" to r.second }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "pure map-clause - double element" {
        val q = capture {
          Table<Ent>()
            .map { e -> free("${e.i} > 123").asPureConditon() }
            .distinct()
            .map { r -> r to r }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }
    }

//    "valuefy normally" - {
//      case class Ent(name: String, i: Int, b: Boolean)
//
//      "filter-clause" in {
//        val q = quote {
//          query[Ent].filter(e => sql"SomeUdf(${e.i})".as[Boolean])
//        }
//        ctx.run(q).string mustEqual
//          "SELECT e.name, e.i, e.b FROM Ent e WHERE 1 = SomeUdf(e.i)"
//      }
//
//      "pure filter-clause" in {
//        val q = quote {
//          query[Ent].filter(e => sql"SomeUdf(${e.i})".pure.as[Boolean])
//        }
//        ctx.run(q).string mustEqual
//          "SELECT e.name, e.i, e.b FROM Ent e WHERE 1 = SomeUdf(e.i)"
//      }
//
//      "map-clause" in {
//        val q = quote {
//          query[Ent].map(e => sql"SomeUdf(${e.i})".as[Int]).map(x => x + 1)
//        }
//        ctx.run(q).string mustEqual
//          "SELECT e.x + 1 FROM (SELECT SomeUdf(e.i) AS x FROM Ent e) AS e"
//      }
//
//      "pure map-clause" in {
//        val q = quote {
//          query[Ent].map(e => sql"SomeUdf(${e.i})".pure.as[Int]).map(x => x + 1)
//        }
//        ctx.run(q).string mustEqual
//          "SELECT SomeUdf(e.i) + 1 FROM Ent e"
//      }
//    }
//  }

    "valuefy normally" - {
      data class Ent(val name: String, val i: Int, val b: Boolean)

      "filter-clause" {
        val q = capture {
          Table<Ent>().filter { e -> free("SomeUdf(${e.i})")<Boolean>() }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "map-clause - impure" {
        val q = capture {
          Table<Ent>()
            .map { e -> free("SomeUdf(${e.i})")<Int>() }
            .map { x -> x + 1 }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }

      "map-clause - pure" {
        val q = capture {
          Table<Ent>()
            .map { e -> free("SomeUdf(${e.i})").asPure<Int>() }
            .map { x -> x + 1 }
        }
        shouldBeGolden(q.xr, "XR")
        shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
      }
    }
  }

  //  "do not expressify string transforming operations" - {
//    case class Product(id: Long, desc: String, sku: Int)
//
//    "first parameter" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        query[Product].filter(p => lift("1").toInt == p.sku)
//      }
//      ctx.run(q).string mustEqual
//        "SELECT p.id, p.desc, p.sku FROM Product p WHERE  (?) = p.sku"
//    }
//
//    "second parameter" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        query[Product].filter(p => p.sku == lift("1").toInt)
//      }
//      ctx.run(q).string mustEqual
//        "SELECT p.id, p.desc, p.sku FROM Product p WHERE p.sku =  (?)"
//    }
//
//    "both parameters" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        query[Product].filter(p => lift("2").toInt == lift("1").toInt)
//      }
//      ctx.run(q).string mustEqual
//        "SELECT p.id, p.desc, p.sku FROM Product p WHERE  (?) =  (?)"
//    }
//  }

  "do not expressify string transforming operations" - {
    data class Product(val id: Long, val desc: String, val sku: Int)

    "first parameter" {
      val q = capture {
        Table<Product>().filter { p -> param("1").toInt() == p.sku }
      }.determinizeDynamics()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "second parameter" {
      val q = capture {
        Table<Product>().filter { p -> p.sku == param("1").toInt() }
      }.determinizeDynamics()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "both parameters" {
      val q = capture {
        Table<Product>().filter { p -> param("2").toInt() == param("1").toInt() }
      }.determinizeDynamics()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }
  }

  "joins" - {
    data class TestEntity(val s: String, val i: Int, val l: Long, val o: Boolean?, val b: Boolean)
    data class TestEntity2(val i: Int, val s: String)

    "for-comprehension with constant" {
      val q = capture.select {
        val t1 = from(Table<TestEntity>())
        val t2 = join(Table<TestEntity>()) { t -> true }
        t1 to t2
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "for-comprehension with field" {
      val q = capture.select {
        val t1 = from(Table<TestEntity>())
        val t2 = join(Table<TestEntity>()) { t -> t.b }
        t1 to t2
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }
  }

  "optionals" - {
    data class TestEntity(val s: String, val i: Int, val l: Long, val o: Boolean?, val b: Boolean)

    "exists" {
      val q = capture {
        Table<TestEntity>()
          .filter { t -> if (t.o ?: false) false else true }
          .map { t -> t.b to true }
      }
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "exists - lifted contains" {
      val q = capture {
        Table<TestEntity>()
          // if (if (x == null) null else f(x)) == null) null else g(if (x == null) null else f(x))
          .filter { t -> t.o?.let { it == true } ?: false }
          .map { t -> t.b to true }
      }
      println(q.xr.showRaw())

      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "exists - lifted not contains" {
      val q = capture {
        Table<TestEntity>()
          .filter { t -> t.o?.let { param(true) } ?: false } // { it -> true }.apply(t.o) -> true
          .map { t -> t.b to true }
      }.determinizeDynamics()
      println(q.xr.showRaw())

      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }

    "exists - lifted complex" {
      val q = capture {
        Table<TestEntity>()
          .filter { t -> t.o?.let { if (param(false)) param(false) else param(true) } ?: false }
          .map { t -> t.b to true }
      }.determinizeDynamics()
      shouldBeGolden(q.xr, "XR")
      shouldBeGolden(q.buildRuntime(BooleanLiteralTestDialect(), "SQL"), "SQL")
    }
  }
})

// Scala:
//class BooleanLiteralSupportSpec extends Spec {
//
//
//

//
//  TODO test as optionals (also should test left-join conditions)
//  "options" - {
//    "exists" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        qr1.filter(t => t.o.exists(_ => if (false) false else true)).map(t => (t.b, true))
//      }
//      ctx.run(q).string mustEqual
//        "SELECT t.b AS _1, 1 AS _2 FROM TestEntity t WHERE (1 = 0 AND 1 = 0 OR NOT (1 = 0) AND 1 = 1) AND t.o IS NOT NULL"
//    }
//
//    "exists - lifted" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        qr1.filter(t => t.o.exists(_ => if (lift(false)) lift(false) else lift(true))).map(t => (t.b, true))
//      }
//      ctx.run(q).string mustEqual
//        "SELECT t.b AS _1, 1 AS _2 FROM TestEntity t WHERE (1 = ? AND 1 = ? OR NOT (1 = ?) AND 1 = ?) AND t.o IS NOT NULL"
//    }
//  }
//
//  "joins" - {
//    import testContext.extras._
//
//    "for-comprehension with constant" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        for {
//          t1 <- query[TestEntity]
//          t2 <- query[TestEntity].join(t => true)
//        } yield (t1, t2)
//      }
//      ctx.run(q).string mustEqual
//        "SELECT t1.s, t1.i, t1.l, t1.o, t1.b, t.s, t.i, t.l, t.o, t.b FROM TestEntity t1 INNER JOIN TestEntity t ON 1 = 1"
//    }
//
//    "for-comprehension with field" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        for {
//          t1 <- query[TestEntity]
//          t2 <- query[TestEntity].join(t => t.b)
//        } yield (t1, t2)
//      }
//      ctx.run(q).string mustEqual
//        "SELECT t1.s, t1.i, t1.l, t1.o, t1.b, t.s, t.i, t.l, t.o, t.b FROM TestEntity t1 INNER JOIN TestEntity t ON 1 = t.b"
//    }
//  }
//
//  "unary operators" - {
//    "constant" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        query[TestEntity].filter(t => !true)
//      }
//
//      // See:
//      //  - Discord question: https://discord.com/channels/632150470000902164/632150470000902166/1153978338168291369
//      //  - Discord answer: https://discord.com/channels/632150470000902164/632150470000902166/1154004784806891571
//      val isScala212 = io.getquill.Versions.scala.startsWith("2.12")
//      val expectedQuery =
//        if (isScala212)
//          "SELECT t.s, t.i, t.l, t.o, t.b FROM TestEntity t WHERE NOT (1 = 1)"
//        else
//          "SELECT t.s, t.i, t.l, t.o, t.b FROM TestEntity t WHERE 1 = 0"
//
//      ctx.run(q).string mustEqual expectedQuery
//    }
//    "field" in testContext.withDialect(MirrorSqlDialectWithBooleanLiterals) { ctx =>
//      import ctx._
//      val q = quote {
//        query[TestEntity].filter(t => !t.b)
//      }
//      ctx.run(q).string mustEqual
//        "SELECT t.s, t.i, t.l, t.o, t.b FROM TestEntity t WHERE NOT (1 = t.b)"
//    }
//  }
//}
