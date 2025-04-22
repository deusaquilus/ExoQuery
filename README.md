# ExoQuery
Language-Integrated Querying for Kotlin Mutiplatform


##### *Question: Why does querying a database need to be any harder than traversing an array?* 

Let's say something like:
```kotlin
data class Person(val name: String, val age: Int)

Table<Person>().map { p -> p.name }
```
Naturally we're pretty sure it should look something like:
```sql
SELECT name FROM Person
```

##### *...but wait, don't databases have complicated things like joins, case-statements, and subqueries?*

Maybe something like this?
```kotlin
capture.select {
  val p = from(people)
  val a = join(addresses) { a -> a.personId == p.id }
  Data(p.name, a.city)
}
//> SELECT p.name, a.city FROM Person p JOIN Address a ON a.personId = p.id
```

Of something like this?
```kotlin
capture.select {
  val p = from(people)
  val a = join(addresses) { a -> a.personId == p.id }
  Data(p.firstName, a.city, if (p.age > 18) 'adult' else 'minor')
}
//> SELECT p.name, a.city, CASE WHEN p.age > 18 THEN 'adult' ELSE 'minor' END FROM Person p JOIN Address a ON a.personId = p.id
```

Or perhaps like this:
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

> "What is `people`, `addresses`, and `companies` you ask? They are just tables in your database
> that you can encode with simple data classes:
> ```kotlin
> data class Person(val name: String, val age: Int, val companyId: Int)
> data class Address(val city: String, val personId: Int)
> data class Company(val name: String, val id: Int)
> 
> val person = capture { Table<Person>() }
> val address = capture { Table<Address>() }
> val company = capture { Table<Company>() }
> ```

##### *...but wait, how can you use `==`, or regular `if` or regular case classes in a DSL?*

By using the `capture` function to deliniate relevant code snippets and a compiler-plugin to
transform them, I can synthesize a SQL query the second your code is compiled in most cases.

You can even see it in the build output in a file:

TODO Video

### Use the Kotlin You Know and Love

That means that you can use regular Kotlin constructs that you know and love in order to write SQL code including:

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
  @CapturedFunction
  fun Stock.marketCap(): Double = catpure.expression {
    price * sharesOutstanding
  }
  capture {
    val totalWeight = Table<Stock>().map { it.marketCap() }.sum()
    Table<Stock>().map { stock -> peRatioWeighted(stock, stock.marketCap/totalWeight) } 
  }
  //> TODO complete this
  ```

### How does Capturing Work?


# Getting Started


# ExoQuery Features
