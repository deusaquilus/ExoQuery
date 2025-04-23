# ExoQuery
Language-Integrated Querying for Kotlin Mutiplatform

## Introduction

### *Question: Why does querying a database need to be any harder than traversing an array?* 

Let's say something like:
```kotlin
data class Person(val name: String, val age: Int)

Table<Person>().map { p -> p.name }
```
Naturally we're pretty sure it should look something like:
```sql
SELECT name FROM Person
```

### *...but wait, don't databases have complicated things like joins, case-statements, and subqueries?*

Let's take some data:
```kotlin
data class Person(val name: String, val age: Int, val companyId: Int)
data class Address(val city: String, val personId: Int)
data class Company(val name: String, val id: Int)
val people: SqlQuery<Person> = capture { Table<Person>() }
val addresses: SqlQuery<Address> = capture { Table<Address>() }
val companies: SqlQuery<Company> = capture { Table<Company>() }
```

Here is a query with some Joins:
```kotlin
capture.select {
  val p = from(people)
  val a = join(addresses) { a -> a.personId == p.id }
  Data(p.name, a.city)
}
//> SELECT p.name, a.city FROM Person p JOIN Address a ON a.personId = p.id
```

Let's add some case-statements:
```kotlin
capture.select {
  val p = from(people)
  val a = join(addresses) { a -> a.personId == p.id }
  Data(p.firstName, a.city, if (p.age > 18) 'adult' else 'minor')
}
//> SELECT p.name, a.city, CASE WHEN p.age > 18 THEN 'adult' ELSE 'minor' END FROM Person p JOIN Address a ON a.personId = p.id
```

Now let's try a subquery:
```kotlin
capture.select {
  val p = from(
    select {
      val c = from(companies)
      val p = join(people) { p -> p.companyId == c.id }
      p
    }
  )
  val a = join(addresses) { a -> a.personId == p.id }
  Data(p.firstName, a.city)
}
//> SELECT p.name, a.city FROM (
//   SELECT p.name, p.age, p.companyId FROM Person p JOIN companies c ON c.id = p.companyId
//  ) p JOIN Address a ON a.personId = p.id
```

The `select` and `catpure.select` functions return a `SqlQuery<T>` object, just like `Table<T>` does.
ExoQuery is well-typed, functionally composeable, deeply respects functional-programming
principles to the core.

### *...but wait, how can you use `==`, or regular `if` or regular case classes in a DSL?*

By using the `capture` function to deliniate relevant code snippets and a compiler-plugin to
transform them, I can synthesize a SQL query the second your code is compiled in most cases.

You can even see it in the build output in a file:

TODO Video

### So I can just use normal Kotlin to write Queries?

That's right! You can use regular Kotlin constructs that you know and love in order to write SQL code including:

TODO double-check these

- Elvis operators
  ```kotlin
  people.map { p ->
    p.name ?: "default"
  }
  //> SELECT CASE WHEN p.name IS NULL THEN 'default' ELSE p.firstName END FROM Person p
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
  //> SELECT CASE WHEN p.age >= 18 THEN 'adult' WHEN p.age < 18 AND p.age > 10 THEN 'minor' ELSE 'child' END FROM Person p
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
- Local variables and extension functions!!
  ```kotlin
  // Building on the previous example...
  @CapturedFunction
  fun peRatioWeighted(stock: Stock, weight: Double): Double = catpure.expression {
    (stock.price / stock.earnings) * weight
  }
  // A extension function used in the query!
  @CapturedFunction
  fun Stock.marketCap(): Double = catpure.expression {
    price * sharesOutstanding
  }
  capture {
    val totalWeight = Table<Stock>().map { it.marketCap() }.sum() // A local variable used in the query!
    Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
  }
  //> TODO complete this
  ```

### How does Capturing Work?


# Getting Started


# ExoQuery Features
