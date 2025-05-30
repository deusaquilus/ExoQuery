package io.exoquery.sqlite

import io.exoquery.SqlQuery
import io.exoquery.TestDatabases
import io.exoquery.capture
import io.exoquery.runOn
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class CastingSpec : FreeSpec({
  val ctx = TestDatabases.sqlite

  "Int to String" {
    val q = capture.select { 1.toString() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe "1"
  }

  "String to Long" {
    val q = capture.select { "1".toLong() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe 1
  }
  "String to Int" {
    val q = capture.select { "1".toInt() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe 1
  }
  "String to Short" {
    val q = capture.select { "1".toShort() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe 1
  }
  "String to Double" {
    val q = capture.select { "1.2".toDouble() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe (1.2).toDouble()
  }
  "String to Float" {
    val q = capture.select { "1.2".toFloat() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe (1.2).toFloat()
  }
  "String to Boolean" {
    val q = capture.select { "true".toBoolean() }
    q.buildFor.Sqlite().runOn(ctx).first() shouldBe true
  }
})
