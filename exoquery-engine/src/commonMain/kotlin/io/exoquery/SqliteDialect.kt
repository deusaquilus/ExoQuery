package io.exoquery

import io.exoquery.sql.*
import io.exoquery.util.TraceConfig
import io.exoquery.util.Tracer
import io.exoquery.util.unaryPlus
import io.exoquery.xr.XR

class SqliteDialect(override val traceConf: TraceConfig = TraceConfig.Companion.empty) : SqlIdiom, BooleanLiteralSupport {
  override val concatFunction: String = "||"
  override val useActionTableAliasAs = SqlIdiom.ActionTableAliasBehavior.UseAs
  override val trace: Tracer by lazy { Tracer(traceType, traceConf, 1) }
  override val reservedKeywords: Set<String> = setOf(
    "abort",
    "action",
    "add",
    "after",
    "all",
    "alter",
    "analyze",
    "and",
    "as",
    "asc",
    "attach",
    "autoincrement",
    "before",
    "begin",
    "between",
    "by",
    "cascade",
    "case",
    "cast",
    "check",
    "collate",
    "column",
    "commit",
    "conflict",
    "constraint",
    "create",
    "cross",
    "current_date",
    "current_time",
    "current_timestamp",
    "database",
    "default",
    "deferrable",
    "deferred",
    "delete",
    "desc",
    "detach",
    "distinct",
    "drop",
    "each",
    "else",
    "end",
    "escape",
    "except",
    "exclusive",
    "exists",
    "explain",
    "fail",
    "for",
    "foreign",
    "from",
    "full",
    "glob",
    "group",
    "having",
    "if",
    "ignore",
    "immediate",
    "in",
    "index",
    "indexed",
    "initially",
    "inner",
    "insert",
    "instead",
    "intersect",
    "into",
    "is",
    "isnull",
    "join",
    "key",
    "left",
    "like",
    "limit",
    "match",
    "natural",
    "no",
    "not",
    "notnull",
    "null",
    "of",
    "offset",
    "on",
    "or",
    "order",
    "outer",
    "plan",
    "pragma",
    "primary",
    "query",
    "raise",
    "recursive",
    "references",
    "regexp",
    "reindex",
    "release",
    "rename",
    "replace",
    "restrict",
    "right",
    "rollback",
    "row",
    "savepoint",
    "select",
    "set",
    "table",
    "temp",
    "temporary",
    "then",
    "to",
    "transaction",
    "trigger",
    "union",
    "unique",
    "update",
    "using",
    "vacuum",
    "values",
    "view",
    "virtual",
    "when",
    "where",
    "with",
    "without"
  )

  override fun xrOrderByCriteriaTokenImpl(orderByCriteriaImpl: XR.OrderField): Token = with(orderByCriteriaImpl) {
    when {
      orderingOpt == null -> +"${scopedTokenizer(field)}"
      orderingOpt is XR.Ordering.Asc -> +"${scopedTokenizer(field)} ASC"
      orderingOpt is XR.Ordering.Desc -> +"${scopedTokenizer(field)} DESC"
      orderingOpt is XR.Ordering.AscNullsFirst -> +"${scopedTokenizer(field)} ASC /* NULLS FIRST */"
      orderingOpt is XR.Ordering.DescNullsFirst -> +"${scopedTokenizer(field)} DESC /* NULLS FIRST */"
      orderingOpt is XR.Ordering.AscNullsLast -> +"${scopedTokenizer(field)} ASC /* NULLS LAST */"
      orderingOpt is XR.Ordering.DescNullsLast -> +"${scopedTokenizer(field)} DESC /* NULLS LAST */"
      else -> xrError("Unsupported ordering: $orderingOpt")
    }
  }

  // Sqlite doesn't like parans around union clauses
  override fun xrSqlQueryModelTokenImpl(queryImpl: SqlQueryModel): Token = with(queryImpl) {
    when (this) {
      is FlattenSqlQuery -> token
      is SetOperationSqlQuery -> +"${a.token} ${op.token} ${b.token}"
      is UnaryOperationSqlQuery -> +"SELECT ${op.token} (${query.token})"
      is TopLevelFree -> this.value.token
    }
  }

  /**
   * Postgres OFFSET needs to be preceded by LIMIT
   * See here: https://sqlite.org/lang_select.html#simple_select_processing
   */
  override fun limitOffsetToken(query: Statement, limit: XR.Expression?, offset: XR.Expression?): Token =
    when {
      limit == null && offset == null -> query
      limit != null && offset == null -> +"$query LIMIT ${limit.token}"
      limit != null && offset != null -> +"$query LIMIT ${limit.token} OFFSET ${offset.token}"
      limit == null && offset != null -> +"$query LIMIT -1 OFFSET ${offset.token}"
      else -> throw IllegalStateException("Invalid limit/offset combination")
    }

  override fun XR.U.QueryOrExpression.stringConversionMapping(name: String): Token = run {
    val head = this
    when (name) {
      "toLong" -> +"CAST(${head.token} AS BIGINT)"
      "toInt" -> +"CAST(${head.token} AS INTEGER)"
      "toShort" -> +"CAST(${head.token} AS SMALLINT)"
      "toDouble" -> +"CAST(${head.token} AS DOUBLE PRECISION)"
      "toFloat" -> +"CAST(${head.token} AS REAL)"
      "toBoolean" -> +"CASE WHEN ${head.token} = 'true' THEN 1 ELSE 0 END"
      "toString" -> +"${head.token}"
      else -> throw IllegalArgumentException("Unknown conversion function: ${name}")
    }
  }
}


//
//  private val _emptySetContainsToken = StringToken("0")
//
//  override def emptySetContainsToken(field: Token): Token = _emptySetContainsToken
//
//  override def prepareForProbing(string: String): String = s"sqlite3_prepare_v2($string)"
//
//  override def astTokenizer(implicit
//    astTokenizer: Tokenizer[Ast],
//    strategy: NamingStrategy,
//    idiomContext: IdiomContext
//  ): Tokenizer[Ast] =
//  Tokenizer[Ast] {
//    case c: OnConflict => conflictTokenizer.token(c)
//    case ast           => super.astTokenizer.token(ast)
//  }
//
//  private[this] val omittedNullsOrdering = stmt"omitted (not supported by sqlite)"
//  private[this] val omittedNullsFirst    = stmt"/* NULLS FIRST $omittedNullsOrdering */"
//  private[this] val omittedNullsLast     = stmt"/* NULLS LAST $omittedNullsOrdering */"
//
//  override implicit def orderByCriteriaTokenizer(implicit
//    astTokenizer: Tokenizer[Ast],
//  strategy: NamingStrategy
//  ): Tokenizer[OrderByCriteria] = Tokenizer[OrderByCriteria] {
//    case OrderByCriteria(ast, Asc) =>
//    stmt"${scopedTokenizer(ast)} ASC"
//    case OrderByCriteria(ast, Desc) =>
//    stmt"${scopedTokenizer(ast)} DESC"
//    case OrderByCriteria(ast, AscNullsFirst) =>
//    stmt"${scopedTokenizer(ast)} ASC $omittedNullsFirst"
//    case OrderByCriteria(ast, DescNullsFirst) =>
//    stmt"${scopedTokenizer(ast)} DESC $omittedNullsFirst"
//    case OrderByCriteria(ast, AscNullsLast) =>
//    stmt"${scopedTokenizer(ast)} ASC $omittedNullsLast"
//    case OrderByCriteria(ast, DescNullsLast) =>
//    stmt"${scopedTokenizer(ast)} DESC $omittedNullsLast"
//  }
//
