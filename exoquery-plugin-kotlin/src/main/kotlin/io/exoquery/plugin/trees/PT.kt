package io.exoquery.plugin.trees

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

// Common Dsl Paths used for extracting/calling Ir. Want to keep the name of this brief in order to minimize syntactic noise
object PT {
  val io_exoquery_util_scaffoldCapFunctionQuery = "io.exoquery.util.scaffoldCapFunctionQuery"

  val io_exoquery_unpackQueryModel = "io.exoquery.unpackQueryModel"
  val io_exoquery_unpackQueryModelLazy = "io.exoquery.unpackQueryModelLazy"

  val io_exoquery_unpackQuery = "io.exoquery.unpackQuery"
  val io_exoquery_unpackQueryLazy = "io.exoquery.unpackQueryLazy"

  val io_exoquery_unpackAction = "io.exoquery.unpackAction"
  val io_exoquery_unpackActionLazy = "io.exoquery.unpackActionLazy"

  val io_exoquery_unpackActionBatch = "io.exoquery.unpackActionBatch"
  val io_exoquery_unpackActionBatchLazy = "io.exoquery.unpackActionBatchLazy"

  val io_exoquery_unpackExpr = "io.exoquery.unpackExpr"

  val io_exoquery_SqlQuery = "io.exoquery.SqlQuery"
  val io_exoquery_SqlAction = "io.exoquery.SqlAction"
  val io_exoquery_SqlActionBatch = "io.exoquery.SqlActionBatch"
  // TODO small optimization: a bunch of times ClassId is created from this, create one here up front once
  val io_exoquery_SqlExpression = "io.exoquery.SqlExpression"
  // this matches the function-call Runtimes.Empty which is needed for an SqlQuery/SqlExpression to be uprootable
  val EmptyRuntimes = "Empty"
}
