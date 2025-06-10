package io.exoquery.plugin.settings

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption

enum class ExoCliOption(
  override val optionName: String,
  override val valueDescription: String,
  override val description: String,
  override val required: Boolean = false,
  override val allowMultipleOccurrences: Boolean = false
) : AbstractCliOption {
  GENERATION_DIR_OPTION(
    "generationDir",
    "<generationDir>",
    "Dir of generated sources",
    false
  ),

  PROJECT_SRC_DIR_OPTION(
    "projectSrcDir",
    "<projectSrcDir>",
    "Dir of project sources",
    false
  ),

  SOURCE_SET_NAME_OPTION(
    "sourceSetName",
    "<sourceSetName>",
    "Name of the source set",
    false
  ),

  TARGET_NAME_OPTION(
    "targetName",
    "<targetName>",
    "Name of the target",
    false
  ),

  KOTLIN_OUTPUT_DIR_OPTION(
    "kotlinOutputDir",
    "<kotlinOutputDir>",
    "Dir of generated Kotlin sources",
    false
  ),

  RESOURCE_OUTPUT_DIR_OPTION(
    "resourceOutputDir",
    "<resourceOutputDir>",
    "Dir of generated resources",
    false
  ),

  PROJECT_BASE_DIR_OPTION(
    "projectBaseDir",
    "<projectBaseDir>",
    "path to gradle project",
    false
  ),

  PROJECT_DIR_KEY(
    "projectDir",
    "<projectDir>",
    "path to gradle project",
    false
  ),

  OUTPUT_STRING(
    "outputString",
    "<outputString>",
    "override the string printed when a static query is executed use %total and %sql switches to specify",
    false
  ),

  QUERY_FILES_ENABLED(
    "queryFilesEnabled",
    "<true|false>",
    "Enables/disables the generation of exo/generated/_.queries.sql query files",
    false
  ),

  QUERY_PRINTING_ENABLED(
    "queryPrintingEnabled",
    "<true|false>",
    "Enables/disables the printing of queries during compile-time",
    false
  )
}