package io.exoquery.config

import io.exoquery.xr.EncodingXR
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.encodeToHexString

fun unpackOptions(str: String) =
  EncodingXR.protoBuf.decodeFromHexString<ExoCompileOptions>(ExoCompileOptions.serializer(), str)


@Serializable
class ExoCompileOptions(
  val generationDir: String,
  val projectSrcDir: String,
  val sourceSetName: String,
  val targetName: String,
  val projectBaseDir: String,
  val kotlinOutputDir: String,
  val resourceOutputDir: String,
  val projectDir: String,
  val outputString: String = DefaultOutputString,
  val queryFilesEnabled: Boolean = DefaultQueryFilesEnabled,
  val queryPrintingEnabled: Boolean = DefaultQueryPrintingEnabled
) {
  fun encode(): String {
    return EncodingXR.protoBuf.encodeToHexString(ExoCompileOptions.serializer(), this)
  }
  companion object {
    val DefaultOutputString = "Compiled %{kind} in %{total}ms: %{sql}"
    val DefaultQueryFilesEnabled = true
    val DefaultQueryPrintingEnabled = true
  }

  val outputStringMaker: OutputStringMaker = OutputStringMaker(outputString)
}

@Serializable
data class OutputStringMaker(val outputString: String) {
  fun make(total: Long, queryString: String, kind: String): String =
    outputString
      .replace("%{total}", total.toString())
      .replace("%{sql}", queryString)
      .replace("%{kind}", kind)
      // CANNOT HAVE LINEBREAKS in outputString.set, will throw
      // `Wrong plugin option format: null, should be plugin:<pluginId>:<optionName>=<value>`
      .replace("%{br}", "\n")

  companion object {
    val Default = OutputStringMaker(ExoCompileOptions.DefaultOutputString)
  }
}
