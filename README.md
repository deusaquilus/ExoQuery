# ExoQuery
Language Integrated Query for Kotlin Multiplatform

* SQL Queries at Compile Time
* Forget `eq`, use regular `==`
* Forget `Case().When`, use regular `if` and `when`.
* Forget `Column<T>`, use regular primitives!
* Cross-Platform: JVM, iOS, Android, Linux, Windows, MacOS, JS-coming soon!
* Functional, Composable, Powerful, and Fun!

[ExoQuery](https://github.com/user-attachments/assets/c3089ca8-702c-406c-9e11-42fe0f38d074)

## Introduction

### *Question: Why does querying a database need to be any harder than traversing an array?*
---

Let's say something like:
```kotlin
people.map { p -> p.name }
```

Naturally we're pretty sure it should look something like:
```sql
SELECT name FROM Person
```

That is why in C# when you write a statement like this:
```csharp
var q = people.Select(p => p.name);   // Select is C#'s .map function
```
In LINQ we understand that it's this:
```csharp
var q = from p in people
        select p.name
```

So in Kotlin, let's just do this:
```kotlin
val q = capture.select {
  val p = from(people)
  p.name
}
```

Then build it for a specific SQL dialect and run it on a database!
```kotlin
val data: List<Person> = q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.name FROM Person p
```

Welcome to ExoQuery!

---
### *...but wait, don't databases have complicated things like joins, case-statements, and subqueries?*
---

Let's take some data:
```kotlin
@Ser data class Person(val name: String, val age: Int, val companyId: Int)
@Ser data class Address(val city: String, val personId: Int)
@Ser data class Company(val name: String, val id: Int)
// Going to use @Ser as a concatenation of @Serializeable for now
val people: SqlQuery<Person> = capture { Table<Person>() }
val addresses: SqlQuery<Address> = capture { Table<Address>() }
val companies: SqlQuery<Company> = capture { Table<Company>() }
```

Here is a query with some Joins:
```kotlin
capture.select {
  val p: Person  = from(people)
  val a: Address = join(addresses) { a -> a.personId == p.id }
  Data(p.name, a.city)
}
//> SELECT p.name, a.city FROM Person p JOIN Address a ON a.personId = p.id
```

Compared to Microsoft LINQ where it would look like this:
```csharp
var q = from p in people
        join a in addresses on a.personId == p.id
        select Data(p.name, a.city)
```

Let's add some case-statements:
```kotlin
capture.select {
  val p = from(people)
  val a = join(addresses) { a -> a.personId == p.id }
  Data(p.name, a.city, if (p.age > 18) 'adult' else 'minor')
}
//> SELECT p.name, a.city, CASE WHEN p.age > 18 THEN 'adult' ELSE 'minor' END FROM Person p JOIN Address a ON a.personId = p.id
```

Now let's try a subquery:
```kotlin
capture.select {
  val (c, p) = from(
    select {
      val c: Company = from(companies)
      val p: Person  = join(people) { p -> p.companyId == c.id }
      c to p
    } // -> SqlQuery<Pair<Company, Person>>
  )
  val a: Address = join(addresses) { a -> a.personId == p.id }
  Data(p.name, c.name, a.city)
}
//> SELECT p.name, c.name, a.city FROM (
//   SELECT p.name, p.age, p.companyId FROM Person p JOIN companies c ON c.id = p.companyId
//  ) p JOIN Address a ON a.personId = p.id
```
Notice how the types compose completely fluidly? The output of a subquery is the same datatype as a table.

---
### *...but wait, how can you use `==`, or regular `if` or regular case classes in a DSL?*
---

By using the `capture` function to deliniate relevant code snippets and a compiler-plugin to
transform them, I can synthesize a SQL query the second your code is compiled in most cases.

<img src="https://github.com/user-attachments/assets/aafeaa92-ea35-4c43-a1fc-a532f66583b6" width=50% height=50%>

<br />
You can even see it in the build output in a file. Have a look at the `build/generated/exoquery` directory.

<img src="https://github.com/user-attachments/assets/fe00c574-ef03-4657-898b-afd37ef16e99" width=50% height=50%>

---
### *So I can just use normal Kotlin to write Queries?*
---

That's right! You can use regular Kotlin constructs that you know and love in order to write SQL code including:

- Elvis operators
  ```kotlin
  people.map { p ->
    p.name ?: "default"
  }
  //> SELECT CASE WHEN p.name IS NULL THEN 'default' ELSE p.name END FROM Person p
  ```
- Question marks and nullary .let statements
  ```kotlin
  people.map { p ->
      p.name?.let { free("mySuperSpecialUDF($it)").asPure<String>() } ?: "default"
  }
  //> SELECT CASE WHEN p.name IS NULL THEN 'default' ELSE mySuperSpecialUDF(p.name) END FROM Person p
  ```
- If and When
  ```kotlin
  people.map { p ->
    when {
      p.age >= 18 -> "adult"
      p.age < 18 && p.age > 10 -> "minor"
      else -> "child"
    } 
  }
  //>  SELECT CASE WHEN p.age >= 18 THEN 'adult' WHEN p.age < 18 AND p.age > 10 THEN 'minor' ELSE 'child' END AS value FROM Person p
  ```
- Simple arithmetic, simple functions on datatypes
  ```kotlin
  @CapturedFunction
  fun peRatioWeighted(stock: Stock, weight: Double): Double = catpure.expression {
    (stock.price / stock.earnings) * weight
  }
  capture {
    Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
  }
  //> SELECT (s.price / s.earnings) * s.marketCap / totalWeight FROM Stock s
  ```
- Pairs and Tuples
  ```kotlin
  val query: SqlQuery<Pair<String, String>> = capture {
    Table<Person>().map { p -> p.name to p.age }  
     // or Triple(p.name, p.age, p.companyId), or MyDataClass(p.name, p.age, p.companyId)
  }
  //> SELECT p.name, p.age FROM Person p
  ```
  You can use pairs and tuples with the whole row too! In fact, you can output any simple data-class.
  See below for examples.

---
### *What is this `capture` thing?*
---

The `capture` function is the cornerstone of ExoQuery's functionality, acting as the bridge between Kotlin code and SQL generation. It identifies specific code blocks within the Kotlin compiler plugin that should be transformed into SQL queries. This powerful mechanism is what enables ExoQuery's distinctive ability to leverage standard Kotlin constructs—such as `if`, `when`, and `let`—directly within the query DSL. The capture function supports several different patterns and expressions, each serving specific query transformation needs:

- A regular table-expression
  ```kotlin
  val people: SqlQuery<Person> = capture { Table<Person>() }
  //> SELECT x.name, x.age FROM Person x
  val joes: SqlQuery<Person> = capture { Table<Person>().where { p -> p.name == "Joe" } }
  //> SELECT p.name, p.age FROM Person p WHERE p.name = 'Joe'
  // (Notice how ExoQuery knows that 'p' should be the variable in this case?)
  ```
- A table-select function. This is a special syntax for doing joins, groupBy, and other complex expressions.
  ```kotlin
  val people: SqlQuery<Pair<Person, Address>> = capture.select {
    val p = from(people)
    val a = join(addresses) { a -> a.personId == p.id }
    p to a
  }
  //> SELECT p.id, p.name, p.age, a.personId, a.street, a.zip  FROM Person p JOIN Address a ON a.personId = p.id 
  ```
  (This is actually just a shortening of the `capture { select { ... } }` expression.)

- An arbitrary code snippet
  ```kotlin
  val nameIsJoe: SqlExpression<(Person) -> Boolean> = capture.expression {
    { p: Person -> p.name == "Joe" }
  }
  ```
  
  You can them use them with normal queries like this:
  ```kotlin
  // The .use function changes it from SqlExpression<(Person) -> Boolean> to just (Person) -> Boolean
  // you can only use it inside of a `capture` block. 
  capture { Table<Person>().filter { p -> nameIsJoe.use(p) } }
  ```
---
### *How do I use normal runtime data inside my SQL captures?*
---

For most data types use `param(...)`. For example:
```Kotlin
val runtimeName = "Joe"
val q = capture { Table<Person>().filter { p -> p.name == param(runtimeName) } }
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ?
```
The ExoQuery infrastructure will know to inject the value of `runtimeName` into the `?`
placeholder in the prepared-statement of the database driver.

This will work for primitives, and KMP and Java date-types (i.e. from `java.time.*`)
ExoQuery uses kotlinx.serialization behind the scenes. In some cases you might
want to use `paramCtx` to create a contextual parameter (Kotlin docs: [contextual-serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#contextual-serialization)) or `paramCustom`
to sepecify a custom serializer (Kotlin docs:  [custom-serializers](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#custom-serializers)).
See the [Parameters and Serialization](#parameters-and-serialization) section for more details.

# Getting Started

Add the following to your `build.gradle.kts`. First add the plugin and then one of the below
dependency blocks.

```kotlin
plugins {
  id("io.exoquery.exoquery-plugin") version "2.1.0-1.0.0.PL"
  kotlin("plugin.serialization") version "2.1.0" // exoquery relies on this
}

// For Java:
dependencies {
  implementation("io.exoquery:exoquery-jdbc:1.0.0.PL-1.0.0")
  implementation("org.postgresql:postgresql:42.7.0") // Remember to include the right JDBC Driver
}

// For: IOS, OSX, Native Linux, and Mingw using Kotlin Multiplatform
dependencies {
  implementation("io.exoquery:exoquery-native:1.0.0.PL-1.0.0")
  implementation("app.cash.sqldelight:native-driver:2.0.2")
}

// For Android:
dependencies {
  implementation("io.exoquery:exoquery-android:1.0.0.PL-1.0.0")
  implementation("androidx.sqlite:sqlite-framework:2.4.0")
}
```

You can get started writing queries like this:
```kotlin
// Create a JVM DataSource the way you normally would
val ds: DataSource = ...
val controller = JdbcControllers.Postgres(ds) 

// Create a query:
val query = capture {
  Table<Person>().filter { p -> p.name == "Joe" }
}

// Build it for the right dialect and execute:
val output = query.buildFor.Postgres().runOn(controller)
```

Have a look at code samples for starter projects here:
- Basic Java project - https://github.com/ExoQuery/exoquery-sample-jdbc
- Basic Linux Native project: TBD
- Android and OSX project: TBD

# ExoQuery Features

ExoQuery has a plethora of features that allow you to write SQL queries, these are enumerated below. Instead
of using reflection to encode and decode data, ExoQuery builds on top of the [Terpal-SQL](https://github.com/ExoQuery/terpal-sql)  database controller
in order to encode and decode Kotlin data classes in a fully cross-platform way. Although you do not need data classes
to be `@Serializeable` in order to build the actual queries, you do need it in order to run them.

## Composing Queries

ExoQuery compose allow you to perform functions on SqlQuery instances. These functions are not available outside of a
compose block becuase the compose block delimiates the boundary of the query. SqlQuery instances created by the
`Table<MyRow>` function representing simple table elements. Following functional-programming principles, 
any transformation on any SqlQuery instance work, and work the same way.

### Map

This is also known as an SQL projection. It allows you to select a subset of the columns in a table.
```Kotlin
val q = capture {
  Table<Person>().map { p -> p.name }
}
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name FROM Person p
```

You can also use the `.map` funtion to perform simple aggreations on tables. For example:
```kotlin
val q = capture {
  Table<Person>().map { p -> Triple(min(p.age), max(p.age), avg(p.age)) }
}
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT min(p.age), max(p.age), avg(p.age) FROM Person p
```

### Filter

This is also known as a SQL where clause. It allows you to filter the rows in a table.

```Kotlin
val q = capture {
  Table<Person>().filter { p -> p.name == "Joe" }
}
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = 'Joe'
```
You can also do `.where { name == "Joe" }` for a slightly more SQL-diomatic experience but this function is not as powerful.

Also, if you are using a `capture.select` block, you can also use the `where` function to filter the rows:
```kotlin
val q = capture.select {
  val p = from(Table<Person>())
  where { p.name == "Joe" }
}
```

### Correlated Subqueries

You can use a combine filter and map functions to create correlated subqueries. 
For example, let's say we want to find all the people over the average age:
```kotlin
val q = capture {
  Table<Person>().filter { p -> p.age > Table<Person>().map { it.age }.avg() }
}
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.age > (SELECT avg(p1.age) FROM Person p1)
```

Recall that you could table the `.map` function with table aggregations. Let's say you want to get
not average but also use that together with another aggreagtor (e.g. the average minus the minimum).
Normally you could use an expression `avg(p.age) + min(p.age)` with a the `.map` function.
```kotlin
val customExpr: SqlQuery<Double> = capture.select { Table<Person>().map { p -> avg(p.age) + min(p.age) } }
```
If you want to use a statement like this inside of a correlated subquery, we can use the `.value()`
function inside of a capture block to convert a `SqlQuery<T>` into just `T`.
```kotlin
val q = capture {
  Table<Person>().filter { p -> p.age > Table<Person>().map { p -> avg(p.age) + min(p.age) }.value() }
}
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.age > (SELECT avg(p1.age) + min(p1.age) FROM Person p1)
```

### SortedBy

The kotlin collections `sortedBy` and `sortedByDescending` functions are also available on SqlQuery instances.
```kotlin
val q = capture {
  Table<Person>().sortedBy { p -> p.name }
}
//> SELECT p.id, p.name, p.age FROM Person p ORDER BY p.name

val q = capture {
  Table<Person>().sortedByDescending { p -> p.name }
}
//> SELECT p.id, p.name, p.age FROM Person p ORDER BY p.name DESC
```

When you want to do advanced sorting (e.g. different sorting for different columns) use a `select` block 
and the sortBy function inside.
```kotlin
val q = capture.select {
  val p = from(Table<Person>())
  sortBy(p.name to Asc, p.age to Desc)
}
//> SELECT p.id, p.name, p.age FROM Person p ORDER BY p.name ASC, p.age DESC
```

### Joins

Use the `capture.select` to do as many joins as you need.
```kotlin
val q: SqlQuery<Pair<Person, Address>> = 
  capture.select {
    val p = from(Table<Person>())
    val a = join(Table<Address>()) { a -> a.ownerId == p.id }
    p to a
  }
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age, a.ownerId, a.street, a.zip FROM Person p JOIN Address a ON a.personId = p.id
```

Let's add a left-join:
```kotlin
val q: SqlQuery<Pair<Person, Address?>> = 
  capture.select {
    val p = from(Table<Person>())
    val a = join(Table<Address>()) { a -> a.ownerId == p.id }
    val f = joinLeft(Table<Furnitire>()) { f -> f.locatedAt == a.id }
    Triple(p, a, f)
  }
//> SELECT p.id, p.name, p.age, a.ownerId, a.street, a.zip, f.name, f.locatedAt FROM Person p 
//  LEFT JOIN Address a ON a.personId = p.id 
//  LEFT JOIN Furnitire f ON f.locatedAt = a.id
```
Notice that the `Address` table is now nullable. This is because the left-join can return null values.

What can go inside of the `capture.select` function is very carefully controlled by exoquery.
It needs to be one of the following:
- A `from` statement
- A `join` or `leftJoin` statement
- A `where` clause (see the [filter](#filter) section above for more details).
- A `sortBy` clause (see the [sortBy](#sortedby) section above for more details).
- A `groupBy` clause (see the [groupBy](#groupby) section below for more details).

You can use all of these features all together. For example:
```kotlin
val q: SqlQuery<Pair<Person, Address>> = 
  capture.select {
    val p = from(Table<Person>())
    val a = join(Table<Address>()) { a -> a.ownerId == p.id }
    where { p.name == "Joe" }
    sortBy(p.name to Asc, p.age to Desc)
    groupBy(p.name)
    p to a
  }

q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age, a.ownerId, a.street, a.city FROM Person p 
//  INNER JOIN Address a ON p.id = a.ownerId 
//  WHERE p.age > 18 GROUP BY p.age, a.street ORDER BY p.age ASC, a.street DESC
```

Also note that you can do implicit joins using the `capture.select` function if desired as well.
For example, the following query is perfectly reasonable:
```kotlin
val q = capture.select {
  val p = from(Table<Person>())
  val a = from(Table<Address>())
  val r = from(Table<Robot>())
  where {
    p.id == a.ownerId && p.id == r.ownerId && p.name == "Joe"
  }
  Triple(p, a, r)
}
//> SELECT p.id, p.name, p.age, a.ownerId, a.street, a.zip, r.ownerId, r.model FROM Person p, Address a, Robot r 
//  WHERE p.id = a.ownerId AND p.id = r.ownerId AND p.name = 'Joe'
```

### GroupBy

Use a `capture.select` function to do groupBy. You can use the `groupBy` function to group by multiple columns.
```kotlin
val q: SqlQuery<Pair<Person, Address>> = 
  capture.select {
    val p = from(Table<Person>())
    val a = join(Table<Address>()) { a -> a.ownerId == p.id }
    groupBy(p.name, a.street)
    MyData(p.name, a.street, avg(p.age)) // Average age of all people named X on the same street
  }
//> SELECT p.name, a.street, avg(p.age) FROM Person p
```

## SQL Actions
ExoQuery has a simple DSL for performing SQL actions.

### Insert

```kotlin
val q =
  capture {
    insert<Person> { set(name to "Joe", age to 44, companyId to 123) }
  }
q.buildFor.Postgres().runOn(myDatabase)
//> INSERT INTO Person (name, age, companyId) VALUES ('Joe', 44, 123)
```
Typically you will use `param` to insert data from runtime values:
```kotlin
val nameVal = "Joe"
val ageVal = 44
val companyIdVal = 123
val q =
  capture {
    insert<Person> { set(name to param(nameVal), age to param(ageVal), companyId to param(companyIdVal)) }
  }
//> INSERT INTO Person (name, age, companyId) VALUES (?, ?, ?)
```

When this gets to cumbersome (and it will!) see how to insert a whole row.

#### Insert a Whole Row

You can insert and entire `person` object using `setParams`.
```kotlin
val person = Person("Joe", 44, 123)
val q =
  capture {
    insert<Person> { setParams(person) }
  }
//> INSERT INTO Person (name, age, companyId) VALUES (?, ?, ?)
```

#### Insert with Exclusions

Wait a second, don't database-model objects like `Person` typically have one or more primary keys key columns
that need to be excluded during the insert because the database generates them?

Here is how to do that:
```kotlin
val person = Person(id = 0, "Joe", 44, 123)

val q =
  capture {
    insert<Person> { setParams(person).excluding(id) } // you can add multiple exclusions here e.g. exlcuding(id, id1, id2, ...)
  }
//> INSERT INTO Person (name, age, companyId) VALUES (?, ?, ?)
```

#### Insert with Returning ID

What do we do if we need to know the row id of the row we just inserted?
The best way to do that is to use the `returning` function to add a `RETURNING` clause to the insert statement.
```kotlin
data class Person(val id: Int, val name: String, val age: Int, val companyId: Int)

val person = Person(id = 0, "Joe", 44, 123)
val q =
  capture {
    insert<Person> { setParams(person).excluding(id) }.returning { p -> p.id }
  }

q.buildFor.Postgres().runOn(myDatabase) // Also works with SQLite
//> INSERT INTO Person (name, age, companyId) VALUES (?, ?, ?) RETURNING id
q.buildFor.SqlServer().runOn(myDatabase)
//> INSERT INTO Person (name, age, companyId) OUTPUT INSERTED.id VALUES (?, ?, ?)
```
This will work for Postgres, SQLite, and SqlServer. For other databases use
`.returningKeys { id }` which will instruct the database-driver to return the
inserted row keys on a more low level. This function is more limited than what
`returning` can do, and it is prone to various database-driver quirks
so be sure to test it on your database appropriately.

The `returning` function is more flexible that `returningKeys` because it allows you to
return not only any column in the inserted row but also collect these columns into
a composite object of your choice. For example:
```kotlin
data class MyOutputData(val id: Int, val name: String)

val person = Person(id = 0, "Joe", 44, 123)

val q =
  capture {
    insert<Person> { setParams(person).excluding(id) }
      .returning { p -> MyOutputData(p.id, p.name + "-suffix") }
  }

val output: List<MyOutputData> = q.buildFor.Postgres().runOn(myDatabase)
//> INSERT INTO Person (name, age, companyId) VALUES (?, ?, ?) RETURNING id, name || '-suffix' AS name
```

### Update

The update statement is similar to the insert statement. You can use the `set` function to set the values of the 
columns you want to update, and typically you will use `param` to set SQL placeholders for runtime values. 
Use a `.where` clause to filter your update query.
```kotlin
val joeName = "Joe"
val joeAge = 44
val joeId = 123

val q =
  capture {
    update<Person> { set(name to param(joeName), age to param(joeAge))
      .where { id == param(joeId) } }
  }
q.buildFor.Postgres().runOn(myDatabase)
// > UPDATE Person SET name = ?, age = ?, companyId = ? WHERE id = 1
```

Similar to INSERT, you can use `setParams` to set columns from the entire `person` object.
Combine this with `excluding` to exclude the primary key column from the update statement and
use the `where` clause to filter your update query.
```kotlin
val person = Person(id = 1, "Joe", 44, 123)
val q =
  capture {
    update<Person> { setParams(person).excluding(id) }
      .where { id == param(joeId) }
  }
q.buildFor.Postgres().runOn(myDatabase)
//> UPDATE Person SET name = ?, age = ?, companyId = ? WHERE id = 1
```

You can also use a `returning` clause to return the updated row if your database supports it.
```kotlin
val person = Person(id = 1, "Joe", 44, 123)

val q =
  capture {
    update<Person> { setParams(person).excluding(id) }
      .where { id == param(joeId) }
      .returning { p -> p.id }
  }

q.buildFor.Postgres().runOn(myDatabase) // Also works with SQLite
//> UPDATE Person SET name = ?, age = ?, companyId = ? WHERE id = ? RETURNING id
q.buildFor.SqlServer().runOn(myDatabase)
//> UPDATE Person SET name = ?, age = ?, companyId = ? OUTPUT INSERTED.id WHERE id = ?
```

### Delete

Delete works exactly the same as insert and updated but there is no `set` clause
since no values are being set.


```kotlin
val joeId = 123
val q =
  capture {
    delete<Person>.where { id == param(joeId) }
  }
q.buildFor.Postgres().runOn(myDatabase)
//> DELETE FROM Person WHERE id = ?
```

The Delete DSL also supports `returning` clauses:
```kotlin
val joeId = 123
val q = 
  capture {
    delete<Person>
      .where { id == param(joeId) }
      .returning { p -> p.id }
  }
q.buildFor.Postgres().runOn(myDatabase) // Also works with SQLite
//> DELETE FROM Person WHERE id = ? RETURNING id
q.buildFor.SqlServer().runOn(myDatabase)
//> DELETE FROM Person OUTPUT DELETED.id WHERE id = ?
```

### Batch Actions

Batch queries are supported (only JDBC for now) for insert, update, and delete actions as well as all of the
features they support (e.g. returning, excluding, etc.).
```kotlin
val people = listOf(
  Person(id = 0, name = "Joe", age = 33, companyId = 123),
  Person(id = 0, name = "Jim", age = 44, companyId = 456),
  Person(id = 0, name = "Jack", age = 55, companyId = 789)
)
val q =
  capture { p ->
    capture.batch(people) { p -> insert<Person> { set(name to param(p.name), age to param(p.age), companyId to (p.companyId)) } }
  }

q.buildFor.Postgres().runOn(myDatabase)
//> INSERT INTO Person (name, age, companyId) VALUES (?, ?, ?)
```
This will tell the JDBC driver to use a PreparedStatement with `.addBatch()` and `executeBatch()` between which every
insert will be executed. Batch queries for update and delete work the same way.

## Column and Table Naming

If you need your table or columns to be named differently that than the data-class name or it's fields
you can use the kotlinx.serialization `SerialName("...")` annotation:
```kotlin
@SerialName("corp_customer")
data class CorpCustomer {
  val name: String
  @SerialName("num_orders")
  val numOrders: Int
  @SerialName("created_at")
  val createdAt: Int
}
val q = capture { Table<CorpCustomer>() }
q.buildFor.Postgres().runOn(myDatabase)
//> 
```
If you do not want to use this annotation for somemthing else (e.g. JSON field names) you can also
use `@ExoEntity` and `@ExoField` to do the same thing.

```kotlin
@ExoEntity("corp_customer")
data class CorpCustomer {
  val name: String
  @ExoField("num_orders")
  val numOrders: Int
  @ExoField("created_at")
  val createdAt: Int
}
val q = capture { Table<CorpCustomer>() }
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT x.name, x.num_orders, x.created_at FROM corp_customer x
```

### Captured Functions

Captured functions allow you to use kotlin functions inside of blocks. Writing a captured function is as simple as adding
the `@CapturedFunction` annotation to a function that returns a `SqlQuery<T>` or `SqlExpression<T>` instance.
Recall that in the introduction we saw a captured function that calculated the P/E ratio of a stock:
```kotlin
  @CapturedFunction
  fun peRatioWeighted(stock: Stock, weight: Double): Double = catpure.expression {
    (stock.price / stock.earnings) * weight
  }
```
Once this function is defined you can use it inside a `capture` block like this:
```kotlin
capture {
  Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
}
```

Note that captured functions can call other captured functions, for example:
```kotlin
@CapturedFunction
fun peRationSimple(stock: Stock): Double = catpure.expression {
  stock.price / stock.earnings
}
@CapturedFunction
fun peRatioWeighted(stock: Stock, weight: Double): Double = catpure.expression {
  peRationSimple(stock) * weight
}
capture {
  Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
}
```

Also note that captured functions can make use of the context-receiver position. For example, let's make the
`marketCap` field into a function:
```kotlin
@CapturedFunction
fun Stock.marketCap() = capture.expression {
    price * sharesOutstanding
  }
val q = capture {
  val totalWeight = Table<Stock>().map { it.marketCap().use }.sum() // A local variable used in the query!
  Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap().use/totalWeight) }
}
println(q.buildFor.Postgres().value)
// SELECT (stock.price / stock.earnings) * ((this.price * this.sharesOutstanding) / (SELECT sum(this.price * this.sharesOutstanding) FROM Stock it)) AS value FROM Stock stock
```

Since captured-functions guarantee that the code inside of them leads to a compile-time generated query they cannot
be used arbitrarily. They can only contain a single `capture`, `capture.select`, or `capture.expression` block.
They cannot have any other kind of control logic (e.g. `if`, `when`, `for`, etc.) inside of them. If you want
a more flexible mechanism for writing queries see the [dynamic queries](#dynamic-queries) section below.

### Polymorphic Query Abstraction

Continuing from the section on [captured-functions](#captured-functions) above, captured functions can use generics and polymorphism in order to create highly abstractable query components.
For example:
```kotlin
interface Locateable { val locationId: Int }
data class Person(val name: String, val age: Int, locationId: Int)
data class Robot(val model: String, val createdOn: LocalDate, val locationId: Int)
data class Address(val id: Int, val street: String, val zip: String)

// Now let's create a captured function that can be used with any Locateable object:
@CapturedFunction
fun <L: Locateable> joinLocation(locateable: SqlQuery<L>): SqlQuery<Pair<L, Address>> = 
  capture.select {
    val l = from(locateable)
    val a = join(addresses) { a -> a.id == l.locationId }
    l to a
  }
```
Now I can use this function with the Person table
```kotlin
val people: SqlQuery<Pair<Person, Address>> = capture {
  joinLocation(Table<Person>().filter { p -> p.name == "Joe" })
}
people.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.name, p.age, p.locationId, a.id, a.street, a.zip FROM Person p JOIN Address a ON a.id = p.locationId WHERE p.name = 'Joe'
```
As well as the Robot table:
```kotlin
val robots: SqlQuery<Pair<Robot, Address>> = capture {
  joinLocation(Table<Robot>().filter { r -> r.model == "R2D2" })
}
//> SELECT r.model, r.createdOn, r.locationId, a.id, a.street, a.zip FROM Robot r JOIN Address a ON a.id = r.locationId WHERE r.model = 'R2D2'
```

You can then continue to compose the output of this function to get more and more powerful queries!

### Local Variables
Captured functions can also be used to define local variables inside of a `capture` block. In the introduction we saw
a query that looked like this:
```kotlin
capture {
  Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
}
```
Note how I intentionally left the `totalWeight` variable undefined. Let's try to define it as a local varaible:
```kotlin
val q =
  capture {
    val totalWeight = Table<Stock>().map { it.marketCap().use }.sum() 
    Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
  }
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT (stock.price / stock.earnings) * ((this.price * this.sharesOutstanding) / (SELECT sum(this.price * this.sharesOutstanding) FROM Stock it)) AS value FROM Stock stock
```

## Transactions

ExoQuery supports transactions! Once a query or action is built e.g. once you do `.buildFor.Postgres()` you will get
one of three possbile things:
1. A `SqlCompiledQuery` object. This is a query that can be executed on the database.
2. A `SqlCompiledAction` object. This is an action that can be executed on the database.
3. A `SqlCompiledBatchAction` object This is a SQL batch action, typically it is not supported for transactions.

Once you have imported a ExoQuery runner project (e.g. exoquery-jdbc) and created a DatabaseController
(e.g. `JdbcControllers.Postgres`), you can run the query or action:
```kotlin
val ds: DataSource = ...
val controller = JdbcControllers.Postgres(ds)

val getJoes = capture {
  Table<Person>().filter { p -> p.name == "Joe" }
}.buildFor.Postgres()

fun setJoesToJims(ids: List<String>) = capture {
  update<Person> { set(name to "Jim").where { p -> p.id in params(ids) } }
}.buildFor.Postgres()

controller.transaction {
  val allJoes = getJoes.runOn(controller)
  // Execute some runtime logic to filter the people we want to update i.e. `shouldActuallyBeUpdated`
  val someJoes = allJoes.filter { p -> shouldActuallyBeUpdated(p) }
  setJoesToJims(someJoes).runOn(controller)
}
```

## Free Blocks

### Calling UDFs

In situations where you need to use a SQL UDF that is available directly on the database, or when you need
to use custom SQL syntax that is not supported by ExoQuery, you can use a free block.
```kotlin
val q = capture {
  Table<Person>().filter { p -> free("mySpecialDatabaseUDF(${p.name})") == "Joe" }
}
//> SELECT p.id, p.name, p.age FROM Person p WHERE mySpecialDatabaseUDF(p.name) = 'Joe'
```

You can pass param-calls into the free-block as well.
```kotlin
val myRuntimeVar = ...
val q = capture {
  Table<Person>().filter { p -> p.name == free("mySpecialDatabaseUDF(${param(myRuntimeVar)})") }
}
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = mySpecialDatabaseUDF(?)
```

### Enriching Queries


Free blocks are also useful for adding information before/after an entire query. For example:
```kotlin
val q = capture {
  free("${Table<Person>().filter { p -> p.name == "Joe" }} FOR UPDATE").asPure<SqlQuery<Person>>()
}
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = 'Joe' FOR UPDATE
```

This is technique is quite powerful when combined with captured-functions to abstract out logic:
```kotlin
@CapturedFunction
fun <T: Person> forUpdate(v: SqlQuery<T>) = capture {
  free("${v} FOR UPDATE").asPure<SqlQuery<T>>()
}

val q = capture {
  forUpdate(Table<Person>().filter { p -> p.age > 21 })
}
//> (SELECT p.id, p.name, p.age FROM Person p WHERE p.age > 21) FOR UPDATE
```


### Enriching Actions

Free blocks can even be used with Action (i.e. insert, update, delete) statements:
```kotlin
val q = capture {
  insert<Person> { setParams(Person(1, "Joe", "Bloggs", 111)) }
}
val qq = capture {
  free("SET IDENTITY_INSERT Person ON ${q} SET IDENTITY_INSERT Person OFF").asPure<SqlAction<Person, Long>>()
}
qq.build<SqlServerDialect>().runOn(ctx)
//> SET IDENTITY_INSERT Person ON  INSERT INTO Person (id, name, age, companyId) VALUES (?, ?, ?, ?) SET IDENTITY_INSERT Person OFF
```

## Parameters and Serialization

ExoQuery builds on top of kotlinx.serialization in order to encode/decode information into SQL prepared-statements and result-sets.
The param function `param` is used to bring runtime data into `capture` functions which are processed at compile-time.
It does this in an SQL-injection-proof fashion by using parameterized queries on the driver-level.
```kotlin
val runtimeName = "Joe"
val q = capture { Table<Person>().filter { p -> p.name == param(runtimeName) } }
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ?
```

### param

The following data-types can be used with `param`
- Primitives: String, Int, Long, Short, Byte, Float, Double, Boolean
- Time Types: `java.util.Date`, LocalDate, LocalTime, LocalDateTime, ZonedDateTime, Instant, OffsetTime, OffsetDateTime
- Kotlin Multiplatform Time Types: `kotlinx.datetime.LocalDate`, `kotlinx.datetime.LocalTime`, `kotlinx.datetime.LocalDateTime`, `kotlinx.datetime.Instant`
- SQL Time Types: `java.sql.Date`, `java.sql.Timestamp`, `java.sql.Time`
- Other: BigDecimal, ByteArray
- Note that in all the time-types Nano-second granularity is not supported. It will be rounded to the nearest millisecond.

Note that for Kotlin native things like `java.sql.Date` and `java.sql.Time` do not exist. Kotlin Multiplatform uses `kotlinx.datetime` objects instead.
> If you've used [Terpal-SQL](https://github.com/ExoQuery/terpal-sql) you'll notice these are the same as the Wrapped-Types that it supports.
> This is because Terpal-SQL and ExoQuery use the same underlying Database Controllers.

### params

If you want to do in-set SQL checks with runtime collections, use the `params` function:
```kotlin
val runtimeNames = listOf("Joe", "Jim", "Jack")
val q = capture { Table<Person>().filter { p -> p.name in params(runtimeNames) } }
q.buildFor.Postgres().runOn(myDatabase)
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name IN (?, ?, ?)
```
Internally this is handled almost the same way as `param`. It finds an appropriate kotlinx.serialization
Serializer and uses it to encode the collection into a SQL prepared-statement.

(Note are also `paramsCustom` and `paramsCtx` functions that are analogous to `paramCustom` and `paramCtx` described below.)

### paramCustom

If you want to use a custom serializer for a specific type, you can use the `paramCustom` function.
This is frequently useful when you want to map structured-data to a primitive type:
```kotlin
// Define a serializer for the custom type
object EmailSerializer : KSerializer<Email> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Email", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: Email) = encoder.encodeString(value.value)
  override fun deserialize(decoder: Decoder) = Email(decoder.decodeString())
}

val email: Email = Email.safeEncode("joe@joesplace.com")
val q = capture {
  Table<User>().filter { p -> p.email == paramCustom(email, EmailSerializer) }
}
```

If you're wondering what the User class looks like, remember that using Kotlin serialization,
this kind of data most likely uses a [property-based-serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#specifying-a-serializer-on-a-property) 
annotation!

```kotlin
@Serializable
data class User(
  val id: Int,
  val name: String,
  @Serializable(with = EmailSerializer::class)
  val email: Email
)
```

You can essentially think of `paramCustom` as way to bring custom-serialized entities into a capture block. The way they are set up
on the data-class coming out of the Query can be a [property-based](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#specifying-a-serializer-on-a-property) serializer, 
a [particular-type](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#specifying-a-serializer-for-a-particular-type) serializer,
a [file-specified](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#specifying-a-serializer-for-a-particular-type) serializer,
or even a [typealias-serializer](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#specifying-a-serializer-for-a-particular-type) serializer.
When you want to any of thse kinds of things brought in as `param` into a `capture` block, use `paramCustom` to do that.


### paramCtx

The `paramCtx` function allows you to use [contextual-serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#contextual-serialization) 
for a specific type. Essentially this is like telling ExoQuery, and kotlinx.serialization "don't worry about not having a serializer here... I got this."
This means that you eventually need to provide a low-level Database-Driver encoder/decoder pair into Database-Controller that you are going to use.


Let's say for example that you have a highly customized encoded type that can only be processed correctly at a low level.
```kotlin
data class ByteContent(val bytes: InputStream) {
  companion object {
    fun bytesFrom(input: InputStream) = ByteContent(ByteArrayInputStream(input.readAllBytes()))
  }
}
```
Then when creating the Database-Controller, provide a low-level encoder/decoder pair:
```kotlin
val myDatabase = object: JdbcControllers.Postgres(postgres.postgresDatabase) {
  override val additionalDecoders =
    super.additionalDecoders + JdbcDecoderAny.fromFunction { ctx, i -> ByteContent(ctx.row.getBinaryStream(i)) }
  override val additionalEncoders =
    super.additionalEncoders + JdbcEncoderAny.fromFunction(Types.BLOB) { ctx, v: ByteContent, i -> ctx.stmt.setBinaryStream(i, v.bytes) }
}
```

Then you can execute queries using `ByteContent` instances like this:
```kotlin
val bc: ByteContent = ByteContent.bytesFrom(File("myfile.txt").inputStream())
val q = capture {
  Table<MyBlobTable>().filter { b -> b.content == paramCtx(bc) }
}
q.buildFor.Postgres().runOn(myDatabase)
```

If you are wondering how what `MyBlobTable` looks like, it is a simple data-class with a `ByteContent` field
that specifies a contextual-serializer. This is in fact *required* so that you can get instances of `MyBlobTable`
out of the database.
```kotlin
@Serializable
data class Image(val id: Int, @Contextual val content: ByteContent)
```

Without this `q.buildFor.Postgres()` will work but `.runOn(myDatabase)` will not.

> Note that this section is largely taken from [Custom Primitives](https://github.com/ExoQuery/terpal-sql?tab=readme-ov-file#custom-primitives)
> in the Terpal-SQL Database Controller documentation which also points to a code-sample for a [Contextual Column Clob](https://github.com/ExoQuery/terpal-sql/blob/main/terpal-sql-jdbc/src/test/kotlin/io/exoquery/sql/examples/ContextualColumnCustom.kt).
> If you are having any difficulty getting the above example to work with the Database-Controller, have a look at the link above.



### Playing well with other Kotlinx Formats

When using ExoQuery with kotlinx-serialization with other formats such as JSON in real-world situations, you may
frequently need either different encodings or even entirely different schemas for the same data. For example, you may
want to encode a `LocalDate` using the SQL `DATE` type, but when sending the data to a REST API you may want to encode
the same `LocalDate` as a `String` in ISO-8601 format (i.e. using DateTimeFormatter.ISO_LOCAL_DATE).

There are several ways to do this in Kotlinx-serialization, I will discuss two of them.

#### Using a Contextual Serializer
The simplest way to have a different encoding for the same data in different contexts is to use a contextual serializer.
```kotlin
@Serializable
data class Customer(val id: Int, val firstName: String, val lastName: String, @Contextual val createdAt: LocalDate)

// Database Schema:
// CREATE TABLE customers (id INT, first_name TEXT, last_name TEXT, created_at DATE)

// This Serializer encodes the LocalDate as a String in ISO-8601 format and it will only be used for JSON encoding.
object DateAsIsoSerializer: KSerializer<LocalDate> {
  override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.format(DateTimeFormatter.ISO_LOCAL_DATE))
  override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString(), DateTimeFormatter.ISO_LOCAL_DATE)
}

// When working with the database, the LocalDate will be encoded as a SQL DATE type. The Terpal Driver knows
// will behave this way by default when a field is marked as @Contextual.
val ctx = JdbcController.Postgres.fromConfig("mydb")
val c = Customer(1, "Alice", "Smith", LocalDate.of(2021, 1, 1))
val q = capture {
  insert<Customer> { set(firstName to param(c.firstName), lastName to param(c.lastName), createdAt to paramCtx(c.createdAt)) }
}
q.buildFor.Postgres().runOn(ctx)
//> INSERT INTO customers (first_name, last_name, created_at) VALUES (?, ?, ?)

// Then later when encoding the data as JSON, the make sure to specify the DateAsIsoSerializer in the serializers-module.
val json = Json {
  serializersModule = SerializersModule {
    contextual(LocalDate::class, DateAsIsoSerializer)
  }
}
val jsonCustomer = json.encodeToString(Customer.serializer(), customer)
println(jsonCustomer)
//> {"id":1,"firstName":"Alice","lastName":"Smith","createdAt":"2021-01-01"}
```

The Terpal-SQL repository has a useful code sample relevant to this use-case.
See the [Playing Well using Different Encoders](terpal-sql-jdbc/src/test/kotlin/io/exoquery/sql/examples/PlayingWell_DifferentEncoders.kt)
example for more details.

#### Using Row-Surrogate Encoder
When the changes in encoding between the Database and JSON are more complex, you may want to use a row-surrogate encoder.

A row-surrogate encoder will take a data-class and copy it into another data-class (i.e. the surrogate data-class) whose schema is appropriate
for the target format. The surrogate data-class needs to also be serializable and know how to create itself from the original data-class.

```kotlin
// Create the "original" data class
@Serializable
data class Customer(
  val id: Int, 
  val firstName: String, 
  val lastName: String, 
  @Serializable(with = DateAsIsoSerializer::class) val createdAt: LocalDate
)

// Create the "surrogate" data class
@Serializable
data class CustomerSurrogate(val id: Int, val firstName: String, val lastName: String, @Contextual val createdAt: LocalDate) {
  fun toCustomer() = Customer(id, firstName, lastName, createdAt)
  companion object {
    fun fromCustomer(customer: Customer): CustomerSurrogate {
      return CustomerSurrogate(customer.id, customer.firstName, customer.lastName, customer.createdAt)
    }
  }
}
```

Then create a surrogate serializer which uses the surrogate data-class to encode the original data-class.
```kotlin
object CustomerSurrogateSerializer: KSerializer<Customer> {
  override val descriptor = CustomerSurrogate.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Customer) = 
    encoder.encodeSerializableValue(CustomerSurrogate.serializer(), CustomerSurrogate.fromCustomer(value))
  override fun deserialize(decoder: Decoder): Customer = 
    decoder.decodeSerializableValue(CustomerSurrogate.serializer()).toCustomer()
}
```

Then use the surrogate serializer when reading data from the database.
```kotlin
// You can then use the surrogate class when reading/writing information from the database:
val customers = capture {
  Table<Customer>().filter { c -> c.firstName == "Joe" }
}.buildFor.Postgres().runOn(ctx, CustomerSurrogateSerializer)
//> SELECT c.id, c.firstName, c.lastName, c.createdAt FROM customers c WHERE c.firstName = 'Joe'

// ...and use the regular data-class/serializer when encoding/decoding to JSON
println(Json.encodeToString(ListSerializer(Customer.serializer()), customers))
//> [{"id":1,"firstName":"Alice","lastName":"Smith","createdAt":"2021-01-01"}]
```

The Terpal-SQL repository has a useful code sample relevant to this use-case.
See the [Playing Well using Row-Surrogate Encoder](terpal-sql-jdbc/src/test/kotlin/io/exoquery/sql/examples/PlayingWell_RowSurrogate.kt)
section for more details.


## Dynamic Queries

There are certain situations where ExoQuery cannot generate a query at compile-time. Most notably this happens when
runtime values are used to choose a particular instance of SqlQuery or SqlExpression to be used. For example:

```kotlin
val someFlag: Boolean = someRuntimeLogic()
val q = capture {
  if (someFlag) {
    Table<Person>().filter { p -> p.name == "Joe" }
  } else {
    Table<Person>().filter { p -> p.name == "Jim" }
  }
}
```

ExoQuery does not know the value of `someFlag` at compile-time and therefore cannot generate a query at that point. This means
that ExoQuery needs to run the query at runtime as the `capture` block is executed. This is called a dynamic query.
Dynamic queries are extremely flexible and ExoQuery is very good at handling them however there are a few caveats:

* Dynamic queries require the ExoQuery Query-Compiler to run with your runtime-code. Specifically, wherever you call
  the `.buildFor.SomeDatabase()` function.
* It can be problematic to call this code from performance-critical areas
  because their cost can be in the order of milliseconds (whereas non-dynamic queries have zero runtime cost since
  they are created inside the compiler). Be sure to test out how much time your dynamic-queries are taking
  if you have any concerns. Kotlin's [measureTime](https://kotlinlang.org/docs/time-measurement.html#measure-code-execution-time)
  function is useful for this so long as you run it 5-10 times to let JIT do its work.
* You will not see dynamic-queries in the SQL log output because they are not
  generated until runtime, although you will see a message that the query is dynamic.

Dynamic queries effectively allow you pass around `SqlQuery<T>` and `SqlExpression<T>` objects without any
restrictions or limitations. For example:
```kotlin
@CapturedDynamic
fun filteredIds(robotsAllowed: Boolean, value: SqlExpression<String>) =
  if (robotsAllowed)
    capture { 
      Table<Person>().filter { p -> p.name == value }.map { p -> p.id } 
      union 
      Table<Robot>().filter { r -> r.model == value }.map { r -> r.id }
    }
  else
    capture { Table<Person>().filter { p -> p.name == value }.map { r -> r.id } }

val q = capture {
  Table<Tenants>().filter { c -> filteredIds(true, capture.expression { c.signatureName }) }
}
//> SELECT c.signatureName, c.rentalCode, c.moveInDate FROM Tenants c WHERE c.signatureName IN (SELECT p.id FROM Person p WHERE p.name = ? UNION SELECT r.id FROM Robot r WHERE r.model = ?)
```
Note several things:
* The `@CapturedFunction` annotation is used to mark the function as a dynamic function, otherwise
  a parsing error along the lines of:
  ```
  Could not understand the SqlExpression (from the scaffold-call) that you are attempting to call `.use` on...
  ------------ Source ------------
  filteredIds(true, capture.expression { c.signatureName })
  ```
  or possibly:
  ```
  It looks like you are attempting to call the external function `joinedClauses` in a captured block
  ```
  will occur. This is because ExoQuery tries to be extra careful about controlling what goes inside
  a capture block, otherwise "Backend Lowering Exceptions" can occur which are notoriously hard to debug.
* Captured functions will typically have one or two runtime flags and the other parameters
  going in should be `SqlQuery<T>` or `SqlExpression<T>` objects. If you do not need to pass around
  `SqlQuery<T>` or `SqlExpression<T>` objects like this, you probably do not need captured-dynamics at all
  and instead should use compile-time [captured functions](#captured-functions).
* Notice how above I used `capture.expression { c.signatureName }` nested inside another `capture` block.
  This is required because `c.signatureName` is merely a `String`, not a `SqlExpression<String>` type. 
  More importantly, it is a compile-time *symbolic* value (i.e. literally the expression `"c.signatureName"`) 
  that relies on the `c` variable, defined inside the parent capture block.
  It is essential to understand that this `c` variable is not actually a real variable that exists "out there"
  in the runtime scope of accessible variables. Unless we re-wrap it, it is forced to remain
  inside the outer `capture` block in which it was defined.**


> ** NOTE: This kind of logic is not arbitrary or magical. It is deeply rooted in the principle 
> of [Phase Consistency](https://gist.github.com/odersky/f91362f6d9c58cc1db53f3f443311140#the-phase-consistency-law)
> that is well understood in the academic literature of compiler metaprogramming theory. Don't bother learning
> any of these things, you don't need to know them in order to use ExoQuery effectively. Only be aware of the fact
> ExoQuery is fundamentally lawful behind the scenes.

Another advantage of dynamic queries is that you can use them to create query-fragments inside of collections.
For example, the following code takes a list of possible names and creates a `person.name == x || person.name == y || ...`
set of clauses from the list.

```kotlin
val possibleNames = listOf("Joe", "Jack")

@CapturedDynamic
fun joinedClauses(p: SqlExpression<Person>) =
  possibleNames.map { n -> capture.expression { p.use.name == param(n) } }.reduce { a, b -> capture.expression { a.use || b.use } }

val filteredPeople = capture {
  Table<Person>().filter { p -> joinedClauses(capture.expression { p }).use }
}

filteredPeople.buildFor.Postgres()
//> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ? OR p.name = ?
```
Again, notice how I wrapped `p` into `capture.expression { p }` to make it a `SqlExpression<Person>` type.
What is being passed from `filteredPeople` to `joinedClauses` is literally the *symbolic* expression `p`.


## Nested Datatypes

TBD

# In the Nuts and Bolts

> WARNING: This section is not for the faint of heart. It is a deep-dive into the more obscure parts of ExoQuery,
> and is not necessary to understand in order to use ExoQuery effectively. Please proceed with caution.

ExoQuery is based on my learnings from the [Quill](https://github.com/zio/zio-quill) Language Integrated Query library
that I have maintained for the better part of a decade. It incorporates fundamental ideas from Functional Programming,
Metaprogramming, and Category Theory in a non-invasive way. It was inspired by a series of precedents.

* Flavio Brasil's Seminal Library [Monadless](https://github.com/monadless/monadless) and my successor to it [ZIO Direct](https://github.com/zio/zio-direct)
* Philip Wadler's talk ["A practical theory of language-integrated query"](http://www.infoq.com/presentations/theory-language-integrated-query)
* Philip Wadler's paper [Everything old is new again: Quoted Domain Specific Languages](http://homepages.inf.ed.ac.uk/wadler/papers/qdsl/qdsl.pdf)
* [The Flatter, the Better](http://db.inf.uni-tuebingen.de/staticfiles/publications/the-flatter-the-better.pdf)

One important thing to understand about ExoQuery from a theoretical standpoint is that it is fundamentally monadic,
just like Microsoft LINQ. The construct `capture.select` bundles the sequence of linear variable assignments until a 
nesting of flatMaps is created. This is based on the novel approach of Monadless.

Take for example this query:
```kotlin
val q = capture.select {
  val p = from(Table<Person>())
  val a = join(Table<Address>()) { a -> p.id == a.ownerId }
  val r = join(Table<Robot>()) { r -> p.id == r.ownerId }
  Triple(p, a, r)
}
println(q.normalizeSelects().xr.show())

// Will yield:
Table(Person).flatMap { p ->
  Table(Address).join { a -> p.id == a.ownerId }.flatMap {
    a -> Table(Robot).join { r -> p.id == r.ownerId }.map { r ->
      Triple(first = p, second = a, third = r)
    }
  }
}
```

ExoQuery supports a user-accessible flatMap function that can literally be used to create the same exact structure:
```kotlin
val q2 = capture {
  Table<Person>().flatMap { p ->
    internal.flatJoin(Table<Address>()) { a -> p.id == a.ownerId }.flatMap { a ->
      internal.flatJoin(Table<Robot>()) { r -> p.id == r.ownerId }.map { r ->
        Triple(p, a, r)
      }
    }
  }
}
println(q2.show())

// Creates the same thing!   
Table(Person).flatMap { p ->
  Table(Address).join { a -> p.id == a.ownerId }.flatMap { a ->
    Table(Robot).join { r -> p.id == r.ownerId }.map { r ->
      Triple(first = p, second = a, third = r)
    }
  }
}
```

The takeaway here is that the `from()` and `join()` functions are actually customized variations
of the monadic bind, embedded into a direct-style syntactic sugar.
