package io.exoquery.plugin.settings

import io.exoquery.config.ExoCompileOptions
import org.jetbrains.kotlin.config.CompilerConfigurationKey

val EXO_OPTIONS = CompilerConfigurationKey.create<ExoCompileOptionsBuilder>("ExoQuery options")

class ExoCompileOptionsBuilder {
  var generationDir: String? = null
  var projectSrcDir: String? = null
  var sourceSetName: String? = null
  var targetName: String? = null
  var projectBaseDir: String? = null
  var kotlinOutputDir: String? = null
  var resourceOutputDir: String? = null
  var projectDir: String? = null
  var outputString: String? = null
  var queryFilesEnabled: Boolean? = true
  var queryPrintingEnabled: Boolean? = true


  fun build(): ExoCompileOptions {
    return ExoCompileOptions(
      requireNotNull(generationDir) { "A non-null generationDir must be provided" },
      requireNotNull(projectSrcDir) { "A non-null projectSrcDir must be provided" },
      requireNotNull(sourceSetName) { "A non-null sourceSetName must be provided" },
      requireNotNull(targetName) { "A non-null targetName must be provided" },
      requireNotNull(projectBaseDir) { "A non-null projectBaseDir must be provided" },
      requireNotNull(kotlinOutputDir) { "A non-null kotlinOutputDir must be provided" },
      requireNotNull(resourceOutputDir) { "A non-null resourceOutputDir must be provided" },
      requireNotNull(projectDir) { "A non-null projectDir must be provided" },
      outputString ?: ExoCompileOptions.DefaultOutputString,
      queryFilesEnabled ?: ExoCompileOptions.DefaultQueryFilesEnabled,
      queryPrintingEnabled ?: ExoCompileOptions.DefaultQueryPrintingEnabled,
    )
  }
}

fun ExoCompileOptionsBuilder.processOption(option: ExoCliOption, value: String) = when (option) {
  ExoCliOption.GENERATION_DIR_OPTION -> generationDir = value
  ExoCliOption.PROJECT_SRC_DIR_OPTION -> projectSrcDir = value
  ExoCliOption.SOURCE_SET_NAME_OPTION -> sourceSetName = value
  ExoCliOption.TARGET_NAME_OPTION -> targetName = value
  ExoCliOption.PROJECT_BASE_DIR_OPTION -> projectBaseDir = value
  ExoCliOption.KOTLIN_OUTPUT_DIR_OPTION -> kotlinOutputDir = value
  ExoCliOption.RESOURCE_OUTPUT_DIR_OPTION -> resourceOutputDir = value
  ExoCliOption.PROJECT_DIR_KEY -> projectDir = value
  ExoCliOption.OUTPUT_STRING -> outputString = value
  ExoCliOption.QUERY_FILES_ENABLED -> queryFilesEnabled = value.toBoolean()
  ExoCliOption.QUERY_PRINTING_ENABLED -> queryPrintingEnabled = value.toBoolean()
}
