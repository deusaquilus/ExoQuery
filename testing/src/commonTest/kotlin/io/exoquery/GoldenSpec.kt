package io.exoquery

import io.exoquery.annotation.ExoExtras
import io.exoquery.printing.PrintableValue
import io.exoquery.printing.QueryFileKotlinMaker
import io.exoquery.sql.Renderer
import io.exoquery.xr.XR
import io.kotest.core.spec.DslDrivenSpec
import io.kotest.core.spec.style.scopes.FreeSpecRootScope
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestScope
import kotlin.test.assertEquals

sealed interface Mode {
  val fileName: String

  data class ExoGoldenTest(override val fileName: String): Mode
  data class ExoGoldenOverride(override val fileName: String): Mode
  companion object {
    // TODO add a parameter for package so tests can be in other packages then io.exoquery
    @ExoExtras
    fun ExoGoldenTest(): Mode =
      ExoGoldenTest(errorCap("No file name provided. This should be overridden by the compiler-plugin to ExoGoldenTestExpr"))

    fun ExoGoldenTestExpr(fileName: String): Mode = ExoGoldenTest(fileName)

    @ExoExtras
    fun ExoGoldenOverride(): Mode =
      ExoGoldenOverride(errorCap("No file name provided. This should be overridden by the compiler-plugin to ExoGoldenOverrideExpr"))

    fun ExoGoldenOverrideExpr(fileName: String): Mode = ExoGoldenOverride(fileName)
  }
}

abstract class GoldenSpecDynamic(
  val goldenQueries: GoldenQueryFile,
  val mode: Mode,
  body: io.exoquery.GoldenSpecDynamic.() -> Unit
): DslDrivenSpec(), FreeSpecRootScope {

  val outputQueries = mutableListOf<PrintableValue>()

  fun TestScope.testPath() = run {
    tailrec fun rec(case: TestCase, acc: List<String>): String =
      case.parent?.let { rec(it, acc + it.name.originalName) } ?: acc.reversed().joinToString("/")
    rec(this.testCase, listOf(testCase.name.originalName))
  }

  // In golden testing you can do asserts but if ExoGoldenOverride it is important not to run them since we want the new golden file to always be prodcued
  // this is a conditional check that only runs in ExoGoldenTest mode
  fun shouldBeTrueInGolden(label: String, cond: () -> Boolean) {
    if (mode is Mode.ExoGoldenTest) {
      if (!cond()) errorCap("The condition was not true: ${label}")
    }
  }

  fun shouldBeTrueInGolden(cond: () -> Boolean) {
    if (mode is Mode.ExoGoldenTest) {
      if (!cond()) errorCap("The condition was not true")
    }
  }

  fun TestScope.shouldBeGolden(xr: XR, suffix: String = "") =
    xr.shouldBeGolden(testPath() + if (suffix.isEmpty()) "" else "/$suffix")

  fun TestScope.shouldBeGolden(sqlRaw: ExoCompiled, suffix: String = "", useTokenRendering: Boolean = true) = run {
    // Get the query as it is resolved from the runtime tokes. That is the only way to know whether the binding lifts actually work or not.
    // The sql.value will never have <UNR?> entries because if params erroneously don't exist it is only know at runtime.
    val path = testPath() + if (suffix.isEmpty()) "" else "/$suffix"
    val sql = sqlRaw.determinizeDynamics()
    val params = sql.params.map { PrintableValue.Param(it.id.value, it.showValue()) }

    if (useTokenRendering) {
      val resolvedQuery = sql.determinizeDynamics().token.renderWith(Renderer())
      resolvedQuery.shouldBeGolden(
        path,
        PrintableValue.Type.SqlQuery,
        params
      )
    }
    else {
      val sqlValue = sqlRaw.value
      sqlValue.shouldBeGolden(
        path,
        PrintableValue.Type.SqlQuery,
        params
      )
    }
  }

  fun TestScope.shouldBeGolden(sqlRaw: BatchParamGroup<*, *, *>, suffix: String = "") = run {
    // Get the query as it is resolved from the runtime tokes. That is the only way to know whether the binding lifts actually work or not.
    // The sql.value will never have <UNR?> entries because if params erroneously don't exist it is only know at runtime.
    val sql = sqlRaw.determinizeDynamics()
    val resolvedQuery = sql.determinizeDynamics().effectiveToken().renderWith(Renderer())
    resolvedQuery.shouldBeGolden(
      testPath() + if (suffix.isEmpty()) "" else "/$suffix",
      PrintableValue.Type.SqlQuery,
      sql.params.map { PrintableValue.Param(it.id.value, it.showValue()) }
    )
  }

  fun TestScope.shouldBeGoldenParams(groups: List<BatchParamGroup<*, *, *>>, suffix: String = "") =
    shouldBeGolden(
      groups.map { it.determinizeDynamics().params.toString() }.joinToString(", "),
      suffix,
      PrintableValue.Type.KotlinCode
    )

  fun TestScope.shouldBeGoldenParams(action: SqlCompiledAction<*, *>, suffix: String = "") =
    shouldBeGolden(action.determinizeDynamics().params.toString(), suffix, PrintableValue.Type.KotlinCode)

  fun TestScope.shouldBeGolden(params: ParamSet, suffix: String = "") =
    shouldBeGolden(params.withNonStrictEquality().lifts.toString(), suffix, PrintableValue.Type.KotlinCode)

  fun TestScope.shouldBeGolden(
    value: String,
    suffix: String = "",
    valuePrinting: PrintableValue.Type = PrintableValue.Type.SqlQuery
  ) =
    value.shouldBeGolden(testPath() + if (suffix.isEmpty()) "" else "/$suffix", valuePrinting, listOf())

  // A simple test that does an assert only when in ExoGoldenTest mode
  fun <T> TestScope.shouldBeGoldenValue(expected: T, actual: T, suffix: String = "") =
    when (mode) {
      is Mode.ExoGoldenTest ->
        assertEquals(
          expected,
          actual,
          "The values were not equal: ${testPath() + if (suffix.isEmpty()) "" else "/$suffix"}"
        )

      is Mode.ExoGoldenOverride -> {}
    }

  // TODO the show() function should use the mirror idiom once that is complete
  fun XR.shouldBeGolden(label: String) = this.show().shouldBeGolden(label, PrintableValue.Type.KotlinCode, listOf())
  fun SqlCompiledQuery<*>.shouldBeGolden() =
    this.value.shouldBeGolden(
      this.label ?: errorCap("""The following query did not have a label: "$value""""),
      PrintableValue.Type.SqlQuery,
      this.params.map { PrintableValue.Param(it.id.value, it.showValue()) }
    )

  fun String.shouldBeGolden(label: String, printType: PrintableValue.Type, params: List<PrintableValue.Param>) =
    when (mode) {
      is Mode.ExoGoldenTest ->
        goldenQueries.queries[label]?.let {
          assertEquals(
            it.queryString.trimIndent().trim(), this.trimIndent().trim(),
            "Golden query for label: \"$label\" did not match"
          )
        } ?: errorCap("""No golden query found for label: "$label"""")

      is Mode.ExoGoldenOverride ->
        outputQueries.add(PrintableValue(this, printType, label, params))
    }

  fun complete() {
    fun isNotSep(c: Char) = c != '/' && c != '\\'
    val override = mode is Mode.ExoGoldenOverride
    val fileNameBase = mode.fileName.dropLastWhile { it != '.' }.dropLast(1).takeLastWhile { isNotSep(it) }
    val fileName = fileNameBase + "GoldenDynamic"
    val fileContent = QueryFileKotlinMaker.invoke(outputQueries, fileName, "io.exoquery")
    println("========================= Generated Override File: ${mode.fileName} =========================\n" + fileContent)
    writeToFile(mode.fileName, fileName, fileContent, override)
  }

  init {
    body()
    afterSpec { complete() }
  }
}


// This mimics FreeSpec but adds a shouldBeGolden function that compares the compiled query to a golden query
// whenever I try to extend FreeSpec I get the following error:
// kotlin.native.internal.IrLinkageError: Constructor 'GoldenSpec.<init>' can not be called: Can not instantiate abstract class 'GoldenSpec'
abstract class GoldenSpec(val goldenQueries: GoldenQueryFile, body: GoldenSpec.() -> Unit): DslDrivenSpec(),
  FreeSpecRootScope {

  fun TestScope.testPath() = run {
    tailrec fun rec(case: TestCase, acc: List<String>): String =
      case.parent?.let { rec(it, acc + it.name.originalName) } ?: acc.reversed().joinToString("/")
    rec(this.testCase, listOf(testCase.name.originalName))
  }

  private fun String.shouldBeGolden(label: String?, printType: PrintableValue.Type) =
    goldenQueries.queries[label]?.let {
      assertEquals(
        it.queryString.trimIndent().trim(), this.trimIndent().trim(),
        "Golden query for label: \"$label\" did not match"
      )
    } ?: errorCap("""No golden query found for label: "$label"""")

  fun SqlCompiledQuery<*>.shouldBeGolden() =
    this.value.shouldBeGolden(
      label ?: errorCap("""The following query did not have a label: "$value""""),
      PrintableValue.Type.SqlQuery
    )

  init {
    body()
  }
}
