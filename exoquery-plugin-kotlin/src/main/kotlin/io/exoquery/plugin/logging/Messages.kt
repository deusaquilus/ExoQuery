package io.exoquery.plugin.logging

import io.exoquery.annotation.CapturedFunction
import io.exoquery.plugin.dataClassProperties
import io.exoquery.plugin.printing.dumpSimple
import io.exoquery.plugin.safeName
import io.exoquery.plugin.source
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.transform.dumpKotlinLikePretty
import io.exoquery.plugin.transform.prepareForPrinting
import io.exoquery.plugin.trees.showLineage
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.*

object Messages {

context(CX.Parsing)
fun batchParamError() = batchParamError(batchAlias?.name?.asString() ?: "<???>")

fun batchParamError(batchParamName: String) = "Detected an invalid use of the batch-parameter `$batchParamName` in the query.\n" + UsingBatchParam

val UsingBatchParam =
"""
In order to use a batch-param or a field of a batch param use wrap it in a param(...) function.
For example for a batch query of List<Person> use it like this:

val people: List<Person> = ...
capture.batch(people) { p -> insert<Person> { set(name to param(p.name)) } }

You can use use setParams to set the entire batch param as a single entity:
capture.batch(people) { p -> insert<Person> { setParams(p) } }

Note that the batch parameter can be different from the entity being inserted for example:
val names: List<String> = listOf("Joe", "John")
capture.batch(names) { name -> insert<Person> { set(name to param(name), age to 123) } }
""".trimIndent()

val ReturningKeysExplanation =
"""
The returningKeys function is designed to be used with the JDBC PreparedStatement.generatedKeys (and similar APIs) that allow
you to retrieved generated keys from a insert statement but does NOT actually have a specific construct in the SQL 
(such as a Postgres, RETURNING clause, a SQL Server OUTPUT clause etc...). This is necessary for cases where you want to
get back IDs being returned by a Query implicitly which has broader support across different databases. Also there are some edge-cases
where explicit returning-clauses are not supported for in all situations (e.g. of SQL Server, the OUTPUT clause cannot be propertly used
with database-tables that have triggers in some situations). Due to these limitations, the `returningKeys` function is more strictly controlled
than the `returning` function. It can only be used in one of two scenarios:

1. The returningKeys function can contain a single column of the inserted entity:
    insert<Person> { setParams(joe).returningKeys { it.id } }
    
2. The returningKeys function can contain a product-type that contains only columns of the inserted entity:
    insert<Person> { setParams(joe).returningKeys { Pair(it.id, it.name) } }

In either situation, it is assumed that the columns being mentioned inside the single-column or product-type are columns that are being generated.
If this is not the case, decoding errors will result.

""".trimIndent()

val ProductTypeInsertInstructions =
"""
The `set` function used inside of an Insert and an Update query can only be used to update leaf-level values. These are values
that are either Kotlin primitives (e.g. String, Int, Boolean, etc...) or type that are explicitly marked with @ExoValue (or @Contextual).
You cannot set who product types in a set-clause. That is to say, you cannot do this:

data class Name(first: String, last: String)
data class Person(name: Name, age: Int)
insert<Person> { set(name to Name("Joe", "Smith") <- This is Illegal, ...) }  

Instead  you can either:

1. Set all of the individual fields of the product type separately e.g:  
  insert<Person> { set(name.first to "Joe", name.last to "Smith", age to 123) }
  
2. Set the entire outer-product using the `setParams` command e.g:
  val joe = Person(Name("Joe", "Smith"), 123)
  insert<Person> { setParams(joe) }

""".trimIndent()

val InvalidColumnExclusions =
"""
Invalid columns were used in the `excluding` function.
The `excluding` function is called from an `insert` or `update` action when a setParams function
is used, in order to exlcude generated-columns from the insert or update query. For example:

data class Person(val id: Int, val name: String, val age: Int)
val joe = Person(1, "Joe", 123)

val insertPerson = capture {
  insert<Person> {
    setParams(joe).excluding(id)
  }
}

This will generate a insert query that skips the `id` column.
INSERT INTO Person (name, age) VALUES (?, ?)
""".trimIndent()

fun InvalidSqlActionFunctionBody() =
"""
The SqlAction expression has an invalid structure. An SqlAction expression should be a lambda with a single expression. 
""".trimIndent() + "\n" + ActionExample

val ActionExample =
"""
For example:

val insertPerson = capture {
  insert<Person> { set(name to "Joe", age to 123) }
}

You can also use setParams to make an action based on an existing data-class instance:

val joe = Person(name = "Joe", age = 123)
val insertPerson = capture {
  insert<Person> { setParams(joe) }
}
""".trimIndent()

fun CannotCallUseOnAnArbitraryDynamic() =
"""
Could not understand the SqlExpression (from the scaffold-call) that you are attempting to call `.use` on. You can only call `.use` on a variable type as SqlExpression.
If you are attempting to use an expression here, it is best practice to write it into a variable outside the capture-block and then call `.use` on that variable. If
this is a function that you are sure can be safely spliced (e.g. it is a pure-function that does not have side-effects) then you can use the @CapturedDynamic annotation
on the function to allow it to be used in this context.
""".trimIndent()

context(CX.Scope)
fun ValueLookupComingFromExternalInExpression(variable: IrGetValue, captureTypeName: String = "select") =
"""
It looks like the variable `${variable.symbol.safeName}` is coming from outside the capture/${captureTypeName} block. Typically
this is a runtime-value that you need to bring into the query as a parameter like this: `param(${variable.symbol.safeName})`.
For example:

val nameVariable = "Joe"
val query = select { Table<Person>().filter { p -> p.name == param(nameVariable) } }
> This will create the query:
> SELECT p.id, p.name, p.age FROM Person p WHERE p.name = ?

(Lineage: ${variable.showLineage()})
""".trimIndent()

context(CX.Scope)
fun VariableComingFromNonCapturedFunction(expr: IrExpression, funName: String) =
"""
It appears that the expression `${expr.source()}` is an argument coming from a function call which will force
the whole surrounding query to become dynamic. If the whole function `${funName}` just returns a SqlQuery and does nothing
else, annotate it as @CapturedFunction and you can then use it to build compile-time functions.
================= For example: =================

fun joes(people: SqlQuery<Person>) = capture { people.filter { p -> p.name == "Joe" } }
val myJoes = joes(Table<Person>()) // This will be dynamic

@CapturedFunction
fun joes(people: SqlQuery<Person>) = capture { people.filter { p -> p.name == "Joe" } }
val myJoes = joes(Table<Person>()) // Now it will be static
""".trimIndent()


fun TypeParseErrorMsg(msg: String) =
"""
$msg
If this is a custom type defined on a data-class e.g. `data class Customer(lastTransacted: MyCustomDate)` make sure to either:

1) Annotate the type (or field) with @kotlinx.serialization.Contextual e.g. `data class Customer(lastTransacted: @Contextual MyCustomDate)`.
   When decoding the `Customer` instance you will need to give it a custom encoder for MyCustomDate.
   
2) Annotate the type with @ExoValue e.g. `data class Customer(lastTransacted: @ExoValue MyCustomDate)`. This will treat the type as a value type
   in ExoQuery independently of how it is serialized i.e. there could be other Serialization-related annotations on the field for example:
   `@Serializable(with=Something::class) class MyCustomDate(...); data class Customer(lastTransacted: @ExoValue MyCustomDate)`.
""".trimIndent()

context(CX.Scope)
fun CapturedFunctionFormWrong(msg: String) =
"""
$msg

The form of the function annotated with @CapturedFunction is incorrect. It must be a function with a 
single output expression that returns a SqlQuery<T> instance.
================= For example: =================

@CapturedFunction
fun myFunction(): SqlQuery<Int> = capture { Table<Person>().map { it.age } }

@CapturedFunction
fun myFunction(): SqlQuery<Int> = select { 
  val p = from(Table<Person>())
  p.age
}
""".trimIndent()


context(CX.Scope)
fun ParserMessage(ir: IrExpression?, parsedCode: String?) =
"""
================ Parsed As: ================
$parsedCode
================ Interpreted IR: ================
${ir?.dumpKotlinLikePretty()}
================= Raw IR: ========================
${ir?.dumpSimple()}
""".trimIndent()

context(CX.Scope)
fun PrintingMessage(ir: IrExpression?) =
"""
================ Interpreated IR: ================
${ir?.dumpKotlinLikePretty()}
================= Raw IR: ========================
${ir?.dumpSimple()}
""".trimIndent()


  context(CX.Scope)
  fun PrintingMessageMulti(ir: List<IrElement>?, additionalHeading: String = ""): String {
    fun writeOutput(ir: IrElement?): String =
      when(ir) {
        is IrReturn -> {
          val tpe = ir.value.type
          val additionalData: String =
            if (true) {
              (tpe.classOrNull?.dataClassProperties() ?: listOf())
                .map { (name, value) -> "$name: ${value.dumpKotlinLike()}" }
                .joinToString(", ", "[", "]")
            } else {
              "$tpe is not a KClass"
            }
          "(Return Value): " + tpe.dumpKotlinLike() + " - " + additionalData
        }
        is IrExpression -> ir.type.dumpKotlinLike()
        else -> "No Type"
      }

val additionalPrint = if (additionalHeading.isNotEmpty()) " ($additionalHeading)" else ""

val irsWithUnpacks = ir?.map { elem -> elem.prepareForPrinting() }

return """
***
***************************************** Print Source *****************************************
***
================ Kotlin-Like:${additionalPrint} ================
${irsWithUnpacks?.withIndex()?.map { (i, it) -> "($i) " + it.dumpKotlinLike() }?.joinToString("\n")}
================= IR: ========================
${irsWithUnpacks?.withIndex()?.map { (i, it) -> "($i) " +  it.dumpSimple() }?.joinToString("\n")}
================= Output Type: ========================
${ir?.withIndex()?.map { (i, it) -> "($i) " + writeOutput(it) }?.joinToString("\n")}
""".trimIndent()
}

  context(CX.Scope)
  fun PrintingMessageSingle(ir: IrElement, additionalHeading: String = ""): String {
    fun writeOutput(ir: IrElement): String =
      when(ir) {
        is IrReturn -> {
          val tpe = ir.value.type
          val additionalData: String =
            if (true) {
              (tpe.classOrNull?.dataClassProperties() ?: listOf())
                .map { (name, value) -> "$name: ${value.dumpKotlinLike()}" }
                .joinToString(", ", "[", "]")
            } else {
              "$tpe is not a KClass"
            }
          "(Return Value): " + tpe.dumpKotlinLike() + " - " + additionalData
        }
        is IrExpression -> ir.type.dumpKotlinLike()
        else -> "No Type"
      }

val additionalPrint = if (additionalHeading.isNotEmpty()) " ($additionalHeading)" else ""

val irWithUnpacks = ir //.prepareForPrinting()

return """
================ Kotlin-Like:${additionalPrint} ================
${irWithUnpacks.dumpKotlinLike()}
================= IR: ========================
${irWithUnpacks.dumpSimple()}
================= Output Type: ========================
${writeOutput(ir)}
""".trimIndent()
}


}
