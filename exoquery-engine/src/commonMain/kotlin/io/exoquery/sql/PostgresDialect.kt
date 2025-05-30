package io.exoquery.sql

import io.exoquery.util.TraceConfig
import io.exoquery.util.Tracer
import io.exoquery.xr.BetaReduction
import io.exoquery.xr.BetaReduction.Companion.invoke
import io.exoquery.xr.XR

class PostgresDialect(override val traceConf: TraceConfig = TraceConfig.Companion.empty) : SqlIdiom {
  override val useActionTableAliasAs = SqlIdiom.ActionTableAliasBehavior.UseAs
  override val reservedKeywords: Set<String> = setOf(
    "all",
    "analyse",
    "analyze",
    "and",
    "any",
    "array",
    "as",
    "asc",
    "asymmetric",
    "authorization",
    "binary",
    "both",
    "case",
    "cast",
    "check",
    "collate",
    "column",
    "constraint",
    "create",
    "cross",
    "current_catalog",
    "current_date",
    "current_role",
    "current_schema",
    "current_time",
    "current_timestamp",
    "current_user",
    "default",
    "deferrable",
    "desc",
    "distinct",
    "do",
    "else",
    "end",
    "except",
    "false",
    "fetch",
    "for",
    "foreign",
    "from",
    "grant",
    "group",
    "having",
    "in",
    "initially",
    "intersect",
    "into",
    "lateral",
    "leading",
    "limit",
    "localtime",
    "localtimestamp",
    "not",
    "null",
    "offset",
    "on",
    "only",
    "or",
    "order",
    "placing",
    "primary",
    "references",
    "returning",
    "select",
    "session_user",
    "some",
    "symmetric",
    "table",
    "then",
    "to",
    "trailing",
    "true",
    "union",
    "unique",
    "user",
    "using",
    "variadic",
    "when",
    "where",
    "window",
    "with"
  )

  override val trace: Tracer by lazy { Tracer(traceType, traceConf, 1) }
}
