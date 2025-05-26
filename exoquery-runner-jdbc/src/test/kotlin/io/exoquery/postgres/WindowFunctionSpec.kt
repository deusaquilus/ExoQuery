package io.exoquery.postgres

import io.exoquery.testdata.Address
import io.exoquery.Ord
import io.exoquery.testdata.Person
import io.exoquery.sql.PostgresDialect
import io.exoquery.TestDatabases
import io.exoquery.capture
import io.exoquery.controller.runActions
import io.exoquery.jdbc.runOn
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class WindowFunctionSpec : FreeSpec({
  val ctx = TestDatabases.postgres

  beforeSpec {
    ctx.runActions(
      """
      TRUNCATE TABLE Person RESTART IDENTITY CASCADE;
      DELETE FROM Address;
      INSERT INTO Person (id, firstName, lastName, age) VALUES (1, 'Joe', 'Bloggs', 111);
      INSERT INTO Person (id, firstName, lastName, age) VALUES (2, 'Joe', 'Doggs', 222);
      INSERT INTO Person (id, firstName, lastName, age) VALUES (3, 'Jim', 'Roogs', 333);
      INSERT INTO Person (id, firstName, lastName, age) VALUES (4, 'Jim', 'Smith', 444);
      INSERT INTO Address (ownerId, street, zip) VALUES (1, '123 Main St', '12345');
      INSERT INTO Address (ownerId, street, zip) VALUES (1, '456 Elm St', '67890');
      INSERT INTO Address (ownerId, street, zip) VALUES (2, '789 Oak St', '54321');
      INSERT INTO Address (ownerId, street, zip) VALUES (3, '101 Pine St', '98765');
      """
    )
  }

  "rank over partition by firstName order by lastName" {
    val q = capture.select {
      val p = from(Table<Person>())
      p to over().partitionBy(p.firstName).sortBy(p.lastName).rank()
    }
    q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
      Person(3, "Jim", "Roogs", 333) to 1,
      Person(4, "Jim", "Smith", 444) to 2,
      Person(1, "Joe", "Bloggs", 111) to 1,
      Person(2, "Joe", "Doggs", 222) to 2,
    )
  }

  "dense_rank over partition by firstName order by lastName" {
    val q = capture.select {
      val p = from(Table<Person>())
      p to over().partitionBy(p.firstName).sortBy(p.lastName).rankDense()
    }
    q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
      Person(3, "Jim", "Roogs", 333) to 1,
      Person(4, "Jim", "Smith", 444) to 2,
      Person(1, "Joe", "Bloggs", 111) to 1,
      Person(2, "Joe", "Doggs", 222) to 2,
    )
  }

  "row_number over partition by firstName order by lastName" {
    val q = capture.select {
      val p = from(Table<Person>())
      p to over().partitionBy(p.firstName).sortBy(p.lastName).rowNumber()
    }
    q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
      Person(3, "Jim", "Roogs", 333) to 1,
      Person(4, "Jim", "Smith", 444) to 2,
      Person(1, "Joe", "Bloggs", 111) to 1,
      Person(2, "Joe", "Doggs", 222) to 2,
    )
  }

  "sum over partition by firstName" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).sum(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 333),
      Triple("Joe", "Doggs", 333),
      Triple("Jim", "Roogs", 777),
      Triple("Jim", "Smith", 777)
    )
  }

  "avg over partition by firstName" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).avg(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 166.5),
      Triple("Joe", "Doggs", 166.5),
      Triple("Jim", "Roogs", 388.5),
      Triple("Jim", "Smith", 388.5)
    )
  }

  "min over partition by firstName" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).min(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 111),
      Triple("Joe", "Doggs", 111),
      Triple("Jim", "Roogs", 333),
      Triple("Jim", "Smith", 333)
    )
  }

  "max over partition by firstName" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).max(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 222),
      Triple("Joe", "Doggs", 222),
      Triple("Jim", "Roogs", 444),
      Triple("Jim", "Smith", 444)
    )
  }

  "count over partition by firstName" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).count())
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 2),
      Triple("Joe", "Doggs", 2),
      Triple("Jim", "Roogs", 2),
      Triple("Jim", "Smith", 2)
    )
  }

  "lag over partition by firstName order by age" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).sortBy(p.age).lag(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", null),
      Triple("Joe", "Doggs", 111),
      Triple("Jim", "Roogs", null),
      Triple("Jim", "Smith", 333)
    )
  }

  "lead over partition by firstName order by age" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).sortBy(p.age).lead(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 222),
      Triple("Joe", "Doggs", null),
      Triple("Jim", "Roogs", 444),
      Triple("Jim", "Smith", null)
    )
  }

  "first_value over partition by firstName order by age" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().partitionBy(p.firstName).sortBy(p.age).firstValue(p.age))
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 111),
      Triple("Joe", "Doggs", 111),
      Triple("Jim", "Roogs", 333),
      Triple("Jim", "Smith", 333)
    )
  }

  "rank over orderBy lastName without partition" {
    val q = capture.select {
      val p = from(Table<Person>())
      p to over().sortBy(p.lastName).rank()
    }
    q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
      Person(1, "Joe", "Bloggs", 111) to 1,
      Person(2, "Joe", "Doggs", 222) to 2,
      Person(3, "Jim", "Roogs", 333) to 3,
      Person(4, "Jim", "Smith", 444) to 4
    )
  }

  "count over without partition or ordering" {
    val q = capture.select {
      val p = from(Table<Person>())
      Triple(p.firstName, p.lastName, over().count())
    }
    q.build<PostgresDialect>().runOn(ctx) shouldContainExactlyInAnyOrder listOf(
      Triple("Joe", "Bloggs", 4),
      Triple("Joe", "Doggs", 4),
      Triple("Jim", "Roogs", 4),
      Triple("Jim", "Smith", 4)
    )
  }
})
