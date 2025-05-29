package io.exoquery.sqlserver

import io.exoquery.Ord
import io.exoquery.TestDatabases
import io.exoquery.capture
import io.exoquery.capture.invoke
import io.exoquery.controller.runActions
import io.exoquery.jdbc.runOn
import io.exoquery.sql.SqlServerDialect
import io.exoquery.testdata.Address
import io.exoquery.testdata.Person
import io.exoquery.testdata.Robot
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

class BooleanLiteralSpec : FreeSpec({
  val ctx = TestDatabases.sqlServer

  beforeSpec {
    ctx.runActions(
      // Note when doing 'SET IDENTITY_INSERT Person ON INSERT INTO Person' DONT put ';' because then the statement will be executed in a different run and the setting will not take
      """
      DELETE FROM Person;
      SET IDENTITY_INSERT Person ON INSERT INTO Person (id, firstName, lastName, age) VALUES (1, 'Joe', 'Bloggs', 111) SET IDENTITY_INSERT Person OFF;
      """
    )
  }

  "where - simple" {
    val q = capture.select {
      val p = from(Table<Person>())
      where { param(true) }
      p.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "where - combined" {
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(true) || e.firstName == "Joe" }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "where - combined complex A" {
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(false) || if (param(false)) param(false) || param(false) else e.firstName == "Joe" }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "where - combined complex A - false" {
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(false) || if (param(false)) param(false) || param(false) else e.firstName == "Jack" }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).isEmpty() shouldBe true
  }

  "where - combined complex C" {
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(false) || if (param(true)) param(false) || param(true) else e.firstName == "Jack" }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "exists - lifted complex - notnull->false->true" {
    val nullableBool: Boolean? = true
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(nullableBool)?.let { if (param(false)) param(false) else param(true) } ?: false }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "exists - lifted complex - notnull->true->true" {
    val nullableBool: Boolean? = true
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(nullableBool)?.let { if (param(true)) param(true) else param(false) } ?: false }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "exists - lifted complex - notnull->true->false" {
    val nullableBool: Boolean? = true
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(nullableBool)?.let { if (param(true)) param(false) else param(true) } ?: false }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).isEmpty() shouldBe true
  }

  "exists - lifted complex - null->true" {
    val nullableBool: Boolean? = null
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(nullableBool)?.let { if (param(false)) param(false) else param(false) } ?: true }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).first() shouldBe "Joe"
  }

  "exists - lifted complex - null->false" {
    val nullableBool: Boolean? = null
    val q = capture.select {
      val e = from(Table<Person>())
      where { param(nullableBool)?.let { if (param(true)) param(true) else param(true) } ?: false }
      e.firstName
    }
    q.buildFor.SqlServer().runOn(ctx).isEmpty() shouldBe true
  }
})
