package io.exoquery.postgres

import io.exoquery.Address
import io.exoquery.Person
import io.exoquery.PostgresDialect
import io.exoquery.TestDatabases
import io.exoquery.capture
import io.exoquery.controller.jdbc.DatabaseController
import io.exoquery.controller.runOn
import io.exoquery.run
import io.exoquery.runOn
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.to

class BasicQuerySpec : FreeSpec({

  val ds = TestDatabases.postgres
  val ctx by lazy { DatabaseController.Postgres(ds) }

  beforeSpec {
    ds.run(
      """
      DELETE FROM Person;
      DELETE FROM Address;
      INSERT INTO Person (id, firstName, lastName, age) VALUES (1, 'Joe', 'Bloggs', 111);
      INSERT INTO Person (id, firstName, lastName, age) VALUES (2, 'Jim', 'Roogs', 222);
      INSERT INTO Address (ownerId, street, zip) VALUES (1, '123 Main St', '12345');
      """
    )
  }

  "simple" {
    //Sql("SELECT id, firstName, lastName, age FROM Person").queryOf<Person>().runOn(ctx) shouldBe listOf(
    //  Person(1, "Joe", "Bloggs", 111),
    //  Person(2, "Jim", "Roogs", 222)
    //)
    val q = capture { Table<Person>() }
    q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
      Person(1, "Joe", "Bloggs", 111),
      Person(2, "Jim", "Roogs", 222)
    )
  }

  "map" {
    val q = capture { Table<Person>().map { it.firstName to it.lastName }  }
    q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
      "Joe" to "Bloggs",
      "Jim" to "Roogs"
    )
  }


//  "joins" - {
//    @Serializable
//    data class Person(val id: Int, val firstName: String, val lastName: String, val age: Int)
//    @Serializable
//    data class Address(val ownerId: Int, val street: String, val zip: String)
//
//    "SELECT Person, Address - join" {
//      Sql("SELECT p.id, p.firstName, p.lastName, p.age, a.ownerId, a.street, a.zip FROM Person p JOIN Address a ON p.id = a.ownerId").queryOf<Pair<Person, Address>>().runOn(ctx) shouldBe listOf(
//        Person(1, "Joe", "Bloggs", 111) to Address(1, "123 Main St", "12345")
//      )
//    }
//
//    "SELECT Person, Address - leftJoin + null" {
//      Sql("SELECT p.id, p.firstName, p.lastName, p.age, a.ownerId, a.street, a.zip FROM Person p LEFT JOIN Address a ON p.id = a.ownerId").queryOf<Pair<Person, Address?>>().runOn(ctx) shouldBe listOf(
//        Person(1, "Joe", "Bloggs", 111) to Address(1, "123 Main St", "12345"),
//        Person(2, "Jim", "Roogs", 222) to null
//      )
//    }
//

  "joins" - {
    "Person, Address - join" {
      val q = capture.select {
        val p = from(Table<Person>())
        val a = join(Table<Address>()) { a -> a.ownerId == p.id }
        p to a
      }
      q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
        Person(1, "Joe", "Bloggs", 111) to Address(1, "123 Main St", "12345")
      )
    }

    "Person, Address - left join" {
      val q = capture.select {
        val p = from(Table<Person>())
        val a = joinLeft(Table<Address>()) { a -> a.ownerId == p.id }
        p to a
      }
      q.build<PostgresDialect>().runOn(ctx) shouldBe listOf(
        Person(1, "Joe", "Bloggs", 111) to Address(1, "123 Main St", "12345"),
        Person(2, "Jim", "Roogs", 222) to null
      )
    }
  }


//    @Serializable
//    data class CustomRow1(val Person: Person, val Address: Address)
//    @Serializable
//    data class CustomRow2(val Person: Person, val Address: Address?)
//
//    "SELECT Person, Address - join - custom row" {
//      Sql("SELECT p.id, p.firstName, p.lastName, p.age, a.ownerId, a.street, a.zip FROM Person p JOIN Address a ON p.id = a.ownerId").queryOf<CustomRow1>().runOn(ctx) shouldBe listOf(
//        CustomRow1(Person(1, "Joe", "Bloggs", 111), Address(1, "123 Main St", "12345"))
//      )
//    }
//
//    "SELECT Person, Address - leftJoin + null - custom row" {
//      Sql("SELECT p.id, p.firstName, p.lastName, p.age, a.ownerId, a.street, a.zip FROM Person p LEFT JOIN Address a ON p.id = a.ownerId").queryOf<CustomRow2>().runOn(ctx) shouldBe listOf(
//        CustomRow2(Person(1, "Joe", "Bloggs", 111), Address(1, "123 Main St", "12345")),
//        CustomRow2(Person(2, "Jim", "Roogs", 222), null)
//      )
//    }
//  }
//
//  "joins + null complex" - {
//    @Serializable
//    data class Person(val id: Int, val firstName: String?, val lastName: String, val age: Int)
//    @Serializable
//    data class Address(val ownerId: Int?, val street: String, val zip: String)
//
//    "SELECT Person, Address - join" {
//      Sql("SELECT p.id, null as firstName, p.lastName, p.age, null as ownerId, a.street, a.zip FROM Person p JOIN Address a ON p.id = a.ownerId").queryOf<Pair<Person, Address>>().runOn(ctx) shouldBe listOf(
//        Person(1, null, "Bloggs", 111) to Address(null, "123 Main St", "12345")
//      )
//    }
//
//    "SELECT Person, Address - leftJoin + null" {
//      Sql("SELECT p.id, null as firstName, p.lastName, p.age, null as ownerId, a.street, a.zip FROM Person p LEFT JOIN Address a ON p.id = a.ownerId").queryOf<Pair<Person, Address?>>().runOn(ctx) shouldBe listOf(
//        Person(1, null, "Bloggs", 111) to Address(null, "123 Main St", "12345"),
//        Person(2, null, "Roogs", 222) to null
//      )
//    }
//  }
//
//  "SELECT Person - nested" {
//    @Serializable
//    data class Name(val firstName: String, val lastName: String)
//    @Serializable
//    data class Person(val id: Int, val name: Name, val age: Int)
//
//    Sql("SELECT id, firstName, lastName, age FROM Person").queryOf<Person>().runOn(ctx) shouldBe listOf(
//      Person(1, Name("Joe", "Bloggs"), 111),
//      Person(2, Name("Jim", "Roogs"), 222)
//    )
//  }
//
//  "SELECT Person - nested with join" {
//    @Serializable
//    data class Name(val firstName: String, val lastName: String)
//    @Serializable
//    data class Person(val id: Int, val name: Name, val age: Int)
//    @Serializable
//    data class Address(val street: String, val zip: String)
//
//    Sql("SELECT p.id, p.firstName, p.lastName, p.age, a.street, a.zip FROM Person p JOIN Address a ON p.id = a.ownerId").queryOf<Pair<Person, Address>>().runOn(ctx) shouldBe listOf(
//      Person(1, Name("Joe", "Bloggs"), 111) to Address("123 Main St", "12345")
//    )
//  }
})
