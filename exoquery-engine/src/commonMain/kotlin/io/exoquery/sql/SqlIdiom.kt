package io.exoquery.sql

import io.decomat.*
import io.exoquery.printing.HasPhasePrinting
import io.exoquery.util.*
import io.exoquery.xr.*
import io.exoquery.xr.OP.*
import io.exoquery.xr.OP.And
import io.exoquery.xr.OP.Or
import io.exoquery.xr.XR.BinaryOp
import io.exoquery.xr.XR.Ordering.*
import io.exoquery.xr.XR.Visibility.*
import io.exoquery.xr.XR.JoinType.*
import io.exoquery.xr.XR.Ident
import io.exoquery.xr.XR.Const.Null
import io.exoquery.xr.XR.ParamType
import io.exoquery.xrError

interface SqlIdiom : HasPhasePrinting {
  companion object {
    val DefaultMethodMappings =
      mapOf(
        XR.FqName("lowercase") to "LOWER",
        XR.FqName("uppercase") to "UPPER",
        XR.FqName("length") to "LEN"
      )
  }

  override val traceType: TraceType get() = TraceType.SqlNormalizations
  abstract val useActionTableAliasAs: ActionTableAliasBehavior

  val reservedKeywords: Set<String> get() = setOf()

  val concatFunction: String get() = "UNNEST"
  val aliasSeparator: String get() = "_"
  open fun joinAlias(alias: List<String>): String = alias.joinToString(aliasSeparator)

  fun normalizeQuery(xr: XR.Query) =
    SqlNormalize(traceConf = traceConf, disableApplyMap = false)(xr)

  // If we want to inline this we would need move it outisde of SqlIdiom and make it a top-level function, then we would need to pass traceConf to every invocation
  fun ((SqlQueryModel) -> SqlQueryModel).andThen(phaseTitle: String, f: (SqlQueryModel) -> SqlQueryModel): (SqlQueryModel) -> SqlQueryModel =
    { qRaw ->
      val q = this(qRaw)
      val label = traceConf.phaseLabel?.let { " (${it})" } ?: ""
      demarcate("Phase: ${phaseTitle}${label}", q)
      val output = f(q)
      output
    }

  fun <T> tryOrFail(msg: String, f: () -> T) =
    try {
      f()
    } catch (e: Throwable) {
      throw RuntimeException(msg, e)
    }

  fun prepareQuery(xr: XR.Query): SqlQueryModel {
    val q = tryOrFail("Error Normalizing Query") { normalizeQuery(xr) }
    val sqlQuery = tryOrFail("Error in SqlQueryApply") { SqlQueryApply(traceConf).of(q) }
    val root = { q: SqlQueryModel -> q }
    val output =
      root
        .andThen("SqlQueryApply") { it -> it }
        .andThen("ValueizeSingleSelects") { ValueizeSingleLeafSelects()(it, q.type) }
        .andThen("ExpandNestedQueries") { ExpandNestedQueries(::joinAlias)(it) }
        .andThen("RemoveExtraAlias") { RemoveExtraAlias()(it) }
        .invoke(sqlQuery)

    // TODO need to implement free-variables checking

    return output
  }

  fun processQuery(xr: XR.Query): Pair<Token, SqlQueryModel> {
    val q = prepareQuery(xr)
    val token = tryOrFail("Error tokenizing prepared query: ${q.showRaw()}") { q.token }
    return token to q
  }

  fun processAction(xr: XR.Action): Token {
    return xr.token
  }


  fun translate(xr: XR.Query) =
    prepareQuery(xr).token.renderWith(Renderer(true, true, null))

  private fun translateBasic(xr: XR): Token {
    // TODO caching
    return when {
      xr is XR.Query -> {
        val normed = normalizeQuery(xr)
        val tokenized = normed.token
        trace("Tokenized SQL: ${tokenized}").andLog()
        tokenized
      }
      else ->
        xr.token
    }
  }

  fun show(xr: XR, pretty: Boolean = false): String {
    val sqlString = translateBasic(xr).token.toString()
    // TODO KMP-out an the vertical-blank sql formatter for JVM via `expect`
    val formatted = sqlString
    return formatted
  }


  val productAggregationToken: ProductAggregationToken get() = ProductAggregationToken.Star


  sealed interface ActionTableAliasBehavior {
    object UseAs : ActionTableAliasBehavior
    object SkipAs : ActionTableAliasBehavior
    object Hide : ActionTableAliasBehavior
  }

  // Very few cases actually end-up going to this top level i.e.
  // the only case so far is the params of an infix. This is because typically
  // the only XR things remaining by this point are the expressions inside the SelectValues
  val XR.token
    get(): Token =
      when (this) {
        is XR.Expression -> token
        is XR.Query -> token
        // is XR.Action -> this.lift()
        is XR.Branch ->
          xrError("All instances of XR.Branch should have been beta-reduced out by now.")
        is XR.Variable ->
          xrError("All instances of XR.Variable should have been beta-reduced out by now.")
        is XR.Action -> token
        is XR.Assignment -> token
        is XR.Batching ->
          xrError("XR.Batching should have been spliced out by now and replaced with regular XR.Action instances.")
      }

  val XR.Expression.token get(): Token = xrExpressionTokenImpl(this)

  // See KT-11488 When overriding a member extension function, cannot call superclass’ implementation
  fun xrExpressionTokenImpl(exprImpl: XR.Expression) = with(exprImpl) {
    when (this) {
      is BinaryOp -> token
      is XR.UnaryOp -> token
      is XR.Const -> token
      is XR.Free -> token
      is XR.Product -> token
      is XR.Property -> token
      is Ident -> token
      is XR.When -> token
      is XR.GlobalCall -> token
      is XR.MethodCall -> token
      is XR.QueryToExpr -> token
      is XR.FunctionN, is XR.FunctionApply, is XR.Block ->
        xrError("Malformed or unsupported construct: ${this.showRaw()}.")
      is XR.TagForParam ->
        when (this.paramType) {
          is ParamType.Single -> ParamSingleToken(this.id)
          is ParamType.Multi -> ParamMultiToken(this.id)
          is ParamType.Batch -> ParamBatchToken(this.id)
        }
      is XR.TagForSqlExpression ->
        xrError("Internal error. All instance of TagFOrSqlExpressio should have been spliced earlier.")
      is XR.Window -> token
    }
  }

  val XR.Window.token get()  = xrWindowTokenImpl(this)
  fun xrWindowTokenImpl(windowImpl: XR.Window) = with(windowImpl) {
    val partitionBy = partitionBy.map { it.token }
    val orderBy = orderBy.map { it.token }
    val frameTok = over.token
    val partitionTok = if (partitionBy.isNotEmpty()) +"PARTITION BY ${partitionBy.mkStmt()}" else +""
    val orderTok = if (orderBy.isNotEmpty()) +"ORDER BY ${orderBy.mkStmt()}" else +""
    val spaceTok = if (partitionBy.isNotEmpty() && orderBy.isNotEmpty()) +" " else +""
    +"${frameTok} OVER(${partitionTok}${spaceTok}${orderTok})"
  }

  // All previous sanitization focused on doing things like removing "<" and ">" from variables like "<init>"
  // at this point we need to get rid of the dollar signs.
  private fun String.sane() = run {
    val dol = '$' + ""
    this.replace(dol, "")
  }

  val XR.Ident.token
    get(): Token =
      this.name.sane().token

  /**
   * For example something like `people.map(_.age).avg` would be something like `people.map(_.age).value.avg`
   * This should in-turn take advantage of GlobalCall so it shold really be something like: `people.map(_.age).value.globalCall("kotlin.Int", "avg")`.
   * In the AST this would be something like: `GlobalCall("kotlin.Int", "avg", listOf(QueryToExpr( <people.map(_.age)> )))`.
   * Now on an SQL level we don't
   */
  val XR.QueryToExpr.token get(): Token = +"(${head.token})"

  fun stringStartsWith(str: XR.U.QueryOrExpression, prefix: XR.U.QueryOrExpression): Token =
    +"starts_with(${str.token}, ${prefix.token})"

  fun XR.U.QueryOrExpression.stringConversionMapping(name: String): Token = run {
    val head = this
    when (name) {
      "toLong" -> +"CAST(${head.token} AS BIGINT)"
      "toInt" -> +"CAST(${head.token} AS INTEGER)"
      "toShort" -> +"CAST(${head.token} AS SMALLINT)"
      "toDouble" -> +"CAST(${head.token} AS DOUBLE PRECISION)"
      "toFloat" -> +"CAST(${head.token} AS REAL)"
      "toBoolean" -> +"CAST(${head.token} AS BOOLEAN)"
      "toString" -> +"${head.token}"
      else -> throw IllegalArgumentException("Unknown conversion function: ${name}")
    }
  }

  fun XR.U.QueryOrExpression.wholeNumberConversionMapping(name: String, isKotlinSynthetic: Boolean): Token = run {
    val head = this
    when {
      // Do numeric casts to decimal types, but only if the user explicitly does it
      // we do not want to do it implicitly because Kotlin frequently does this and most DBs
      // do implicit float<->int conversions just as well
      name == "toDouble" && !isKotlinSynthetic -> +"CAST(${head.token} AS DOUBLE PRECISION)"
      name == "toFloat" && !isKotlinSynthetic -> +"CAST(${head.token} AS REAL)"
      name == "toBoolean" -> +"CAST(${head.token} AS BOOLEAN)"
      name == "toString" -> +"CAST(${head.token} AS ${varcharType()})"
      // toInt, toLong, toShort reply in implicit casting
      else -> +"${head.token}"
    }
  }

  fun XR.U.QueryOrExpression.floatConversionMapping(name: String, isKotlinSynthetic: Boolean): Token = run {
    val head = this
    when {
      name == "toLong" && !isKotlinSynthetic -> +"CAST(${head.token} AS BIGINT)"
      name == "toInt" && !isKotlinSynthetic -> +"CAST(${head.token} AS INTEGER)"
      name == "toShort" && !isKotlinSynthetic -> +"CAST(${head.token} AS SMALLINT)"
      name == "toBoolean" -> +"CAST(${head.token} AS BOOLEAN)"
      name == "toString" -> +"CAST(${head.token} AS ${varcharType()})"
      // toFloat, toDouble reply in implicit casting
      else -> +"${head.token}"
    }
  }

  // Certain dialects require varchar sizes so allow this to be overridden
  fun varcharType(): Token = "VARCHAR".token

  // TODO needs lots of refinement
  val XR.MethodCall.token
    get(): Token = run {
      val argsToken = (listOf(head) + args).map { it -> it.token }.mkStmt()
      when {
        // NOTE: Perhaps we should check that io.exoquery.Params is the host-type? Need to think about various implications of being more strict
        name == "contains" -> +"${args.first().token} ${"IN".token} (${head.token})"

        // rely on implicit-casts for numeric conversion
        originalHostType.isWholeNumber() && name.isConverterFunction() ->
          head.wholeNumberConversionMapping(name, isKotlinSynthetic)

        originalHostType.isFloatingPoint() && name.isConverterFunction() ->
          head.floatConversionMapping(name, isKotlinSynthetic)

        originalHostType == CID.kotlin_String -> {
          when {
            // Cast strings to numeric types if needed
            name.isConverterFunction() -> head.stringConversionMapping(name)
            name == "substring" -> +"SUBSTRING(${head.token}, ${args.first().token}, ${args.last().token})"
            name == "startsWith" -> stringStartsWith(head, args.first())
            name == "uppercase" -> +"UPPER(${head.token})"
            name == "lowercase" -> +"LOWER(${head.token})"
            name == "left" -> +"LEFT(${head.token}, ${args.first().token})"
            name == "right" -> +"RIGHT(${head.token}, ${args.first().token})"
            name == "replace" -> +"REPLACE(${head.token}, ${args.first().token}, ${args.last().token})"
            else -> xrError("Unknown or invalid XR.MethodCall method: ${name} in the expression:\n${this.showRaw()}")
          }
        }
        head is XR.Query && name == "isNotEmpty" -> +"EXISTS (${head.token})"
        head is XR.Query && name == "isEmpty" -> +"NOT EXISTS (${head.token})"
        // in correlated-query situations where we have an Query-level aggregator inside of a filter e.g.
        // `people.filter(p => addresses.map(a => a.ownerId).min == p.id)`. In that case we need ot go back
        // to the top-level with the inner-query and run the SqlQuery expasion on the head XR.Query object
        // This will go into XR.Query.token and which will call the Query expansion into SqlQuery.
        // Doing all of this runs the potential risk of recursing forever if SqlQuery returns a SelectValue with the full
        // query expression so need make sure that doesn't happen there in SqlQuery.
        head is XR.Query && callType == XR.CallType.QueryAggregator -> {
          scopedQueryTokenizer(this as XR.Query)
        }
        // Need to think about situations where it is a CallType.PureFunction/ImpureFunction and see how it needs to be expanded. Something with sub-expansion
        // might be necessary e.g. sub-expading the XR.Query with SqlQuery if needed

        else -> xrError("Unknown or invalid XR.MethodCall method: ${name} in the expression:\n${this.showRaw()}")
      }
    }

  fun tokenizeSelectAggregator(call: XR.MethodCall): Statement {
    val op = call.name
    val expr = call.head
    return when {
      // Aggregation(op, Ident(id, _: Quat.Product))
      expr is Ident && expr.type is XRType.Product -> +"${op.token}(${makeProductAggregationToken(expr.name)})"
      // Not too many cases of this. Can happen if doing a leaf-level infix inside of a select clause. For example in postgres:
      // `sql"unnest(array['foo','bar'])".as[Query[Int]].groupBy(p => p).map(ap => ap._2.max)` which should yield:
      // SELECT MAX(inf) FROM (unnest(array['foo','bar'])) AS inf GROUP BY inf
      expr is XR.Ident -> +"${op.token}(${expr.token})"
      expr is XR.Product -> +"${op.token}(*)"
      // In ExoQuery Distinct is a type of Query so it cannot occur in an aggregation expression
      //expr is XR.Distinct -> +"${op.token}(DISTINCT ${expr.ast.token})"
      else -> +"${op.token}(${expr.token})"
    }
  }

  val methodMappings: Map<XR.FqName, String> get() = DefaultMethodMappings

  val XR.GlobalCall.token
    get(): Token = run {
      val argsToken = args.map { it -> it.token }.mkStmt()
      // The parser translate casting operators into a XR.GlobalCall named "kotlinCast" with one argument.
      // In this case for now we want to just assume SQL will do an implicit cast. May want to change this in the future.
      if (this.name == XR.FqName.Cast && args.size == 1)
        argsToken
      else if (this.name.name == "COUNT_STAR")
        +"count(*)"
      else if (this.name == XR.FqName.CountDistinct)
        +"count(DISTINCT ${argsToken})"
      else {
        val functionName = methodMappings[this.name] ?: this.name.name
        +"${functionName}(${argsToken})"
      }
    }


  val XR.When.token
    get(): Token = run {
      val whenThens = branches.map { it -> +"WHEN ${it.cond.token} THEN ${it.then.token}" }
      +"CASE ${whenThens.mkStmt(" ")} ELSE ${orElse.token} END"
    }

  val XR.Const.token get(): Token = xrConstTokenImpl(this)
  fun xrConstTokenImpl(constImpl: XR.Const): Token = with(constImpl) {
    when (this) {
      is XR.Const.Boolean -> +"${value.toString().token}"
      is XR.Const.Byte -> +"${value.toString().token}" // use kotlin 1.9.22 stlib to have this: +"${value.toHexString()}"
      is XR.Const.Char -> +"'${value.toString().token}'"
      is XR.Const.Double -> +"${value.toString().token}"
      is XR.Const.Float -> +"${value.toString().token}"
      is XR.Const.Int -> +"${value.toString().token}"
      is XR.Const.Long -> +"${value.toString().token}"
      is XR.Const.Null -> +"null"
      is XR.Const.Short -> +"${value.toString().token}"
      is XR.Const.String -> +"'${value.toString().token}'"
    }
  }

  // Typically this will be a tuple of some sort, just converts the elements into a list
  // For dialects of SQL like Spark that select structured data this needs special handling
  val XR.Product.token get(): Token = xrProductTokenImpl(this)
  fun xrProductTokenImpl(productImpl: XR.Product) = with(productImpl) {
    fields.map { it -> it.second.token }.mkStmt()
  }

  val XR.Query.token get(): Token = xrQueryTokenImpl(this)
  fun xrQueryTokenImpl(queryImpl: XR.Query): Token = with(queryImpl) {
    when (this) {
      is XR.ExprToQuery -> head.token
      else -> {
        // This case typically happens when you have a select inside of an insert
        // infix or a set operation (e.g. query[Person].exists).
        // have a look at the SqlDslSpec `forUpdate` and `insert with subselects` tests
        // for more details.
        // Right now we are not removing extra select clauses here (via RemoveUnusedSelects) since I am not sure what
        // kind of impact that could have on selects. Can try to do that in the future.
        if (Globals.querySubexpand) {
          val nestedExpanded = ExpandNestedQueries(::joinAlias)(SqlQueryApply(traceConf).of(this))
          // TODO Need to implement
          //RemoveExtraAlias(strategy)(nestedExpanded).token
          nestedExpanded.token
        } else
          SqlQueryApply(traceConf).of(this).token
      }
    }
  }


  private val ` AS`
    get() =
      when (useActionTableAliasAs) {
        ActionTableAliasBehavior.UseAs -> +" AS"
        else -> emptyStatement
      }

  val FlattenSqlQuery.token get(): Token = flattenSqlQueryTokenImpl(this)
  fun flattenSqlQueryTokenImpl(query: FlattenSqlQuery): Token =
    with(query) {
      val selectTokenizer by lazy {
        when {
          select.isEmpty() -> +"*"
          else -> select.token { it.token }
        }
      }
      val distinctTokenizer by lazy {
        when (distinct) {
          is DistinctKind.Distinct -> +"DISTINCT "
          is DistinctKind.DistinctOn -> +"DISTINCT ON (${distinct.props.token { it.token }}) "
          is DistinctKind.None -> +""
        }
      }
      val withDistinct by lazy { +"${distinctTokenizer}${selectTokenizer}" }
      val withFrom by lazy {
        when {
          from.isEmpty() -> withDistinct
          else -> {
            val t =
              from.drop(1).fold(+"${from.first().token}") { a, b ->
                when (b) {
                  is FlatJoinContext -> +"$a ${(b as FromContext).token}"
                  else -> +"$a, ${b.token}"
                }
              }

            +"$withDistinct FROM $t"
          }
        }
      }
      val withWhere by lazy {
        when {
          where != null -> +"$withFrom WHERE ${where.token}"
          else -> withFrom
        }
      }
      val withGroupBy by lazy {
        when {
          groupBy != null -> +"$withWhere GROUP BY ${tokenizeGroupBy(groupBy)}"
          else -> withWhere
        }
      }
      val withOrderBy by lazy {
        when {
          orderBy.isEmpty() -> withGroupBy
          else -> +"$withGroupBy ${tokenOrderBy(orderBy)}"
        }
      }
      val withLimitOffset by lazy { limitOffsetToken(withOrderBy, limit, offset) }

      +"SELECT ${withLimitOffset}"
    }

  fun tokenizeGroupBy(values: XR.Expression): Token = values.token
  fun tokenOrderBy(criteria: List<XR.OrderField>) = +"ORDER BY ${criteria.token { it.token }}"

  fun escapeIfNeeded(name: String): Token =
    if (reservedKeywords.contains(name.lowercase()))
      "\"${name}\"".token
    else
      name.token

  fun tokenizeTable(name: String): Token = escapeIfNeeded(name)
  fun tokenizeAlias(alias: List<String>): Token = escapeIfNeeded(this.joinAlias(alias))
  fun tokenizeColumn(name: String): Token = escapeIfNeeded(name)

  val SelectValue.token get(): Token = selectValueTokenImpl(this)
  fun selectValueTokenImpl(exprImpl: SelectValue): Token = with(exprImpl) {
    when {
      // SelectValue(Ident(? or name, _), _, _)
      expr is Ident -> expr.name.token
      // Typically these next two will be for Ast Property where we have an alias:
      // SelectValue(ast, Some(alias), concat: true)
      alias.isNotEmpty() && concat == false -> +"${expr.token} AS ${tokenizeAlias(alias)}" // in this case `alias` is the column name
      // SelectValue(ast, Some(alias), concat: false)
      alias.isNotEmpty() && concat == true -> +"${concatFunction.token}(${expr.token}) AS ${tokenizeAlias(alias)}"
      // Where we don't have an alias...
      // SelectValue(ast, None, concat: true)
      alias.isEmpty() && concat == true -> +"${concatFunction.token}(${expr.token}) AS ${expr.token}"
      // SelectValue(ast, None, concat: false)
      alias.isEmpty() && concat == false -> expr.token

      // NOTE: In Quill there was an aggregation-tokenizer here because Aggregation was a subtype of Query
      // (because aggregations were monadic). This is not the case in ExoQuery where aggregations
      // are just a type of expression. So this case is a subtype of XR.Expression.

      else -> xrError("Illegal SelectValue clause: ${this}")
    }
  }

  fun makeProductAggregationToken(id: String) =
    when (productAggregationToken) {
      ProductAggregationToken.Star -> +"*"
      ProductAggregationToken.VariableDotStar -> +"${id.token}.*"
    }

  // x is Any are technically useless checks but then entire logic here is much simpler to understand
  // with them enabled.  I'm not sure if the compiler will optimize them out or not but I
  // do not thing it will make a significant performance penalty.
  @Suppress("USELESS_IS_CHECK")
  val XR.BinaryOp.token get(): Token = xrBinaryOpTokenImpl(this)
  fun xrBinaryOpTokenImpl(binaryOpImpl: XR.BinaryOp): Token = with(binaryOpImpl) {
    when {
      a is Any && op is EqEq && b is Null -> +"${scopedTokenizer(a)} IS NULL"
      a is Null && op is EqEq && b is Any -> +"${scopedTokenizer(b)} IS NULL"
      a is Any && op is NotEq && b is Null -> +"${scopedTokenizer(a)} IS NOT NULL"
      a is Null && op is NotEq && b is Any -> +"${scopedTokenizer(b)} IS NOT NULL"

      a is Any && op is And && b is Any ->
        when {
          // (a1 || a2) && (b1 || b2) i.e. need parens around the a and b
          a is BinaryOp && a.op is Or && b is BinaryOp && b.op is Or ->
            +"${scopedTokenizer(a)} ${op.token} ${scopedTokenizer(b)}"
          // (a1 || a2) && b i.e. need parens around the a
          a is BinaryOp && a.op is Or -> +"${scopedTokenizer(a)} ${op.token} ${b.token}"
          // a && (b1 || b2) i.e. need parens around the b
          b is BinaryOp && b.op is Or -> +"${a.token} ${op.token} ${scopedTokenizer(b)}"
          // i.e. don't need parens around a or b
          else -> +"${a.token} ${op.token} ${b.token}"
        }
      a is Any && op is Or && b is Any -> +"${a.token} ${op.token} ${b.token}"
      else -> +"${scopedTokenizer(a)} ${op.token} ${scopedTokenizer(b)}"
    }
  }

  // In SQL unary operators will always be in prefix position. Also, if it's just a minus
  // it will be part of the int/long/float/double constant and not a separate unary operator
  // so we don't need to consider that case here.
  val XR.UnaryOp.token get(): Token = xrUnaryOpTokenImpl(this)
  fun xrUnaryOpTokenImpl(unaryOpImpl: XR.UnaryOp): Token =
    on(unaryOpImpl).match(
      // NOT (x IS NULL) => x IS NOT NULL
      case(XR.UnaryOp[Is<OP.Not>(), BinaryOp[Is(), Is<OP.EqEq>(), Is()]]).thenThis { (a, b) ->
        xrBinaryOpTokenImpl(XR.BinaryOp(a, OP.NotEq, b))
      }
    ) ?: run {
      with(unaryOpImpl) {
        +"${op.token} (${expr.token})"
      }
    }

  val UnaryOperator.token
    get(): Token =
      when (this) {
        is OP.Minus -> +"-"
        is OP.Not -> +"NOT"
      }

  val BinaryOperator.token get(): Token = opBinaryTokenImpl(this)
  fun opBinaryTokenImpl(opImpl: BinaryOperator): Token = with(opImpl) {
    when (this) {
      is OP.EqEq -> +"="
      is OP.NotEq -> +"<>"
      is OP.And -> +"AND"
      is OP.Or -> +"OR"
      is OP.StrPlus -> +"||" // String concat is `||` is most dialects (SQL Server uses + and MySQL only has the `CONCAT` function)
      is OP.Minus -> +"-"
      is OP.Plus -> +"+"
      is OP.Mult -> +"*"
      is OP.Gt -> +">"
      is OP.GtEq -> +">="
      is OP.Lt -> +"<"
      is OP.LtEq -> +"<="
      is OP.Div -> +"/"
      is OP.Mod -> +"%"
    }
  }

  val FromContext.token
    get(): Token =
      when (this) {
        is TableContext -> +"${entity.token} ${alias.sane().token}"
        is QueryContext -> +"(${query.token})${` AS`} ${alias.sane().token}"
        is ExpressionContext -> +"(${(infix as XR.Expression).token})${` AS`} ${alias.sane().token}"
        is FlatJoinContext -> +"${joinType.token} ${from.token} ON ${on.token}"
      }

  val XR.Free.token get(): Token = xrFreeTokenImpl(this)
  fun xrFreeTokenImpl(freeImpl: XR.Free): Token = with(freeImpl) {
    val pt = parts.map { it.token }
    val pr = params.map { it.token }
    Statement(pt.intersperseWith(pr))
  }

  val XR.JoinType.token get(): Token = xrJoinTypeTokenImpl(this)
  fun xrJoinTypeTokenImpl(joinTypeImpl: XR.JoinType): Token = with(joinTypeImpl) {
    when (this) {
      is Left -> +"LEFT JOIN"
      is Inner -> +"INNER JOIN"
    }
  }

  val XR.Entity.token get(): Token = xrEntityTokenImpl(this)
  fun xrEntityTokenImpl(entityImpl: XR.Entity): Token = with(entityImpl) {
    tokenizeTable(name)
  }

  val XR.OrderField.token get(): Token = xrOrderByCriteriaTokenImpl(this)
  fun xrOrderByCriteriaTokenImpl(orderByCriteriaImpl: XR.OrderField): Token = with(orderByCriteriaImpl) {
    when {
      // If the ordering is null it is effecitvely implicit and that should manifest on the SQL
      orderingOpt == null -> +"${scopedTokenizer(field)}"
      orderingOpt is Asc -> +"${scopedTokenizer(field)} ASC"
      orderingOpt is Desc -> +"${scopedTokenizer(field)} DESC"
      orderingOpt is AscNullsFirst -> +"${scopedTokenizer(field)} ASC NULLS FIRST"
      orderingOpt is DescNullsFirst -> +"${scopedTokenizer(field)} DESC NULLS FIRST"
      orderingOpt is AscNullsLast -> +"${scopedTokenizer(field)} ASC NULLS LAST"
      orderingOpt is DescNullsLast -> +"${scopedTokenizer(field)} DESC NULLS LAST"
      else -> xrError("Unknown ordering: ${this}")
    }
  }

  fun scopedQueryTokenizer(ast: XR.Query) =
    +"(${ast.token})"

  fun scopedTokenizer(ast: XR.Expression) =
    when (ast) {
      is XR.BinaryOp -> +"(${ast.token})"
      is XR.Product -> +"(${ast.token})"
      else -> ast.token
    }

  fun limitOffsetToken(query: Statement, limit: XR.Expression?, offset: XR.Expression?): Token =
    when {
      limit == null && offset == null -> query
      limit != null && offset == null -> +"$query LIMIT ${limit.token}"
      limit != null && offset != null -> +"$query LIMIT ${limit.token} OFFSET ${offset.token}"
      limit == null && offset != null -> +"$query OFFSET ${offset.token}"
      else -> throw IllegalStateException("Invalid limit/offset combination")
    }

  val SqlQueryModel.token get(): Token = xrSqlQueryModelTokenImpl(this)
  fun xrSqlQueryModelTokenImpl(queryImpl: SqlQueryModel): Token = with(queryImpl) {
    when (this) {
      is FlattenSqlQuery -> token
      is SetOperationSqlQuery -> +"(${a.token}) ${op.token} (${b.token})"
      is UnaryOperationSqlQuery -> +"SELECT ${op.token} (${query.token})"
      is TopLevelFree -> this.value.token
    }
  }

  val SetOperation.token
    get(): Token =
      when (this) {
        is UnionOperation -> +"UNION"
        is UnionAllOperation -> +"UNION ALL"
      }

  val XR.Property.token get(): Token = xrPropertyTokenImpl(this)
  fun xrPropertyTokenImpl(propertyImpl: XR.Property): Token = with(propertyImpl) {
    UnnestProperty(this).let { (ast, prefix) ->
      when {
        // This is the typical case. It happens on the outer (i.e. top-level) clause of a multi-level select e.g.
        // SELECT /*this ->*/ foobar... FROM (SELECT foo.bar AS foobar ...)
        // When it's just a top-level select the prefix will be empty
        ast is Ident && (ast.visibility == Hidden || ast.isThisRef()) ->
          joinAlias(prefix).token
        // This happens when the SQL dialect supports some notion of structured-data
        // and we are selecting something from a nested expression
        // SELECT /*this ->*/ (someExpression).otherStuff FROM (....)
        else ->
          +"${scopedTokenizer(ast)}.${(tokenizeColumn(joinAlias(prefix)).token)}"
      }
    }
  }

  val XR.Action.token get(): Token = xrActionTokenImpl(this)
  fun xrActionTokenImpl(actionImpl: XR.Action): Token = with(actionImpl) {
    when (this) {
      is XR.Insert -> this.token
      is XR.Update -> this.token
      is XR.Delete -> this.token
      is XR.OnConflict -> this.token
      is XR.FilteredAction -> this.token
      is XR.Returning -> this.token
      is XR.Free -> this.token
      is XR.TagForSqlAction -> xrError("TagForSqlAction should have been expanded out before this point")
    }
  }

  fun prepareForTokenization(onConflictRaw: XR.OnConflict): XR.OnConflict = run {
    if (onConflictRaw.resolution is XR.OnConflict.Resolution.Update) {
      // Postgres doesn't know what field-name to use even if there's an EXCLUDING, for example, in something like this:
      // Error executing query: INSERT INTO Person (firstName, lastName, age) VALUES (?, ?, 1234) ON CONFLICT (id) DO UPDATE SET firstName = ((firstName || '_') || EXCLUDED.firstName)), age = EXCLUDED.age
      // it won't know what (firstName || '_') to use. So we need to replace the whole whopping action alias with some arbitrary alias that we assing
      // for the 'existing' column when we parse the set(...) clause ON CONFLICT target (in the parser).
      val actionAlias = onConflictRaw.insert.alias
      val x = actionAlias.copy(name = "x")
      // also, we need to make sure that the real set-clause of the insert function doesn't use the action-alias but our new alias
      // because fields of the set clause i.e. `INSERT INTO person as x SET firstName = ...` should not have an alias i.e. should not be
      // `INSERT INTO person as x SET x.firstName = ...`
      val onConflict =
        onConflictRaw.copy(
          insert = onConflictRaw.insert.copy(
            // NOTE that beta reduction won't replace aliases on declarations, only inside of properties. This is by design.
            alias = x,
            assignments = onConflictRaw.insert.assignments.map { asi ->
              asi.copy(
                property = BetaReduction(asi.property, actionAlias to actionAlias.copy(XR.Ident.HiddenRefName)) as XR.Property
              )
            }
          ),
          resolution = onConflictRaw.resolution.copy(
            existingParamIdent = x
          )
        )
      val newOnConflict = BetaReduction.ofXR(onConflict, actionAlias to x, onConflictRaw.resolution.existingParamIdent to x) as XR.OnConflict
      newOnConflict
    } else {
      onConflictRaw
    }
  }

  val XR.OnConflict.token get(): Token = xrOnConflictTokenImpl(this)
  fun xrOnConflictTokenImpl(onConflictImpl: XR.OnConflict): Token = with(prepareForTokenization(onConflictImpl)) {
    when {
      target == XR.OnConflict.Target.NoTarget && resolution == XR.OnConflict.Resolution.Update -> xrError("'DO UPDATE' statement requires explicit conflict target")
      target == XR.OnConflict.Target.NoTarget && resolution == XR.OnConflict.Resolution.Ignore ->
        +"${insert.token} ON CONFLICT DO NOTHING"

      target is XR.OnConflict.Target.Properties && resolution is XR.OnConflict.Resolution.Update -> {
        val conflictFields = target.props
        val updateAssignments = resolution.assignments.map { BetaReduction.ofXR(it, resolution.excludedId to resolution.excludedId.copy("EXCLUDED")) as XR.Assignment }
        doUpdateStmt(insert, conflictFields, updateAssignments)
      }

      target is XR.OnConflict.Target.Properties && resolution is XR.OnConflict.Resolution.Ignore -> {
        val conflictFields = target.props
        +"${insert.token} ON CONFLICT (${conflictFields.map { it.token }.mkStmt()}) DO NOTHING"
      }

      else -> xrError("Unsupported OnConflict form: ${onConflictImpl.showRaw()}")
    }
  }

  fun doUpdateStmt(insert: XR.Insert, conflictFields: List<XR.Property>, updateAssignments: List<XR.Assignment>): Token = with(insert) {
    val assignments = updateAssignments.map { it.token }.mkStmt()
    +"${insert.token} ON CONFLICT (${conflictFields.map { it.token }.mkStmt()}) DO UPDATE SET ${assignments}"
  }


  val XR.Insert.token get(): Token = xrInsertTokenImpl(this)
  fun xrInsertTokenImpl(insertImpl: XR.Insert): Token = with(insertImpl) {
    val query = this.query as? XR.Entity ?: xrError("Insert query must be an entity but found: ${this.query}")
    tokenizeInsertBase(this)
  }

  fun tokenizeInsertBase(insert: XR.Insert): Token = with(insert) {
    val (columns, values) = columnsAndValues(assignments, exclusions).unzip()
    +"INSERT INTO ${query.token}${`AS_table`(alias)} (${columns.mkStmt(", ")}) VALUES ${tokenizeInsertAssignemnts(values)}"
  }

  fun tokenizeInsertAssignemnts(values: List<Token>) =
    TokenContext(values.mkStmt(", ", "(", ")"), TokenContext.Kind.AssignmentBlock)

  val List<XR.Assignment>.token
    get(): Token =
      this.map { it.token }.mkStmt(", ")

  val XR.FilteredAction.token get(): Token = xrFilteredActionTokenImpl(this)
  fun xrFilteredActionTokenImpl(filteredActionImpl: XR.FilteredAction): Token = with(filteredActionImpl) {
    when {
      action is XR.U.CoreAction -> {
        val reducedExpr = BetaReduction(filter, alias to action.alias).asExpr()
        +"${action.token} WHERE ${reducedExpr.token}"
      }
      else ->
        xrError("Filtered actions are only allowed on the core-actions update, delete but found:\n${showRaw()}")
    }
  }

  fun protractReturning(kind: XR.Returning.Kind.Expression, actionAlias: XR.Ident) = run {
    val reducedExpr = BetaReduction(kind.expr, kind.alias to actionAlias).asExpr()
    when (val tpe = reducedExpr.type) {
      is XRType.Product ->
        // Some crazy things can happen if you do something like
        // data class Name(val first: String, val last: String), data class Person(val id: Int, val name: Name, val age: Int)
        // insert<Person> { ... }.returning { p -> Name(p.name.first + "-stuff", p.name.last + "-otherStuff") } i.e. something like:
        // SELECT ... RETURNING (first + '-stuff', last + '-otherStuff').first, (first + '-stuff', last + '-otherStuff').last
        // So we need to make sure to beta-reduce all of the output clauses individually when a product is expanded
        ProtractQuat(true).invoke(tpe, reducedExpr).map { BetaReduction(it.first).token }.mkStmt(", ")
      else ->
        scopedTokenizer(reducedExpr).token
    }
  }

  // TODO possible variable-shadowing issues might require beta-reducing out the alias of the inner query first.
  //      Do that instead of creating an ExternalIdent like was done in Quill #1509.
  val XR.Returning.token get(): Token = xrReturningTokenImpl(this)
  fun xrReturningTokenImpl(returningImpl: XR.Returning): Token = with(returningImpl) {
    when {
      // In Postgres-style RETURNING clause the RETURNING is always the last thing to be used so we can
      // use the action renderes first. In SQL-server that uses an OUTPUT clause this is not the case
      // and we need to repeat some logic here.
      kind is XR.Returning.Kind.Expression && action is XR.U.CoreAction -> {
        val returningClauseToken = protractReturning(kind, action.coreAlias())
        +"${action.token} RETURNING ${returningClauseToken}"
      }
      kind is XR.Returning.Kind.Expression && action is XR.FilteredAction -> {
        val returningClauseToken = protractReturning(kind, action.coreAlias())
        +"${action.token} RETURNING ${returningClauseToken}"
      }
      kind is XR.Returning.Kind.Expression && action is XR.OnConflict -> {
        val returningClauseToken = protractReturning(kind, action.coreAlias())
        +"${action.token} RETURNING ${returningClauseToken}"
      }

      // This is when an API like insert(...).returningColumns(...) is used.
      // In this case the PrepareStatement.getGeneratedKeys() should be used but there should
      // be no specific RETURNING clause in the SQL.
      kind is XR.Returning.Kind.Keys && (action is XR.U.CoreAction || action is XR.FilteredAction || action is XR.OnConflict) ->
        action.token
      else ->
        xrError("Returning clauses are only allowed on core-actions i.e. insert, update, delete (and insert/onConflict) but found:\n${action.showRaw()}")
    }
  }

  val XR.Delete.token get(): Token = xrDeleteTokenImpl(this)
  fun xrDeleteTokenImpl(deleteImpl: XR.Delete): Token = with(deleteImpl) {
    fun deleteBase() = tokenizeDeleteBase(deleteImpl)
    when {
      query is XR.Filter && query.head is XR.Entity ->
        +"${deleteBase()} WHERE ${query.token}"
      query is XR.Entity ->
        deleteBase()
      else ->
        xrError("Invalid query-clause in a Delete. It can only be a XR Filter or Entity but was:\n${query.showRaw()}")
    }
  }

  fun tokenizeDeleteBase(delete: XR.Delete): Token = with(delete) {
    +"DELETE FROM ${query.token}${`AS_table`(alias)}"
  }

  // TODO specialized logic for Postgres UPDATE to allow setting token-context here
  val XR.Update.token get(): Token = xrUpdateTokenImpl(this)
  fun xrUpdateTokenImpl(updateImpl: XR.Update): Token = with(updateImpl) {
    fun updateBase() = tokenizeUpdateBase(updateImpl)
    when {
      query is XR.Filter && query.head is XR.Entity ->
        +"${updateBase()} WHERE ${query.token}"
      query is XR.Entity ->
        updateBase()
      else ->
        xrError("Invalid query-clause in an Update. It can only be a XR Filter or Entity but was:\n${query.showRaw()}")
    }
  }

  fun tokenizeUpdateBase(update: XR.Update): Token = with(update) {
    +"UPDATE ${query.token}${`AS_table`(alias)} SET ${assignments.filterNot { exclusions.contains(it.property) }.token}"
  }

  // AS [table] specifically for actions (where for some dialects it shouldn't even be there)
  fun `AS_table`(alias: XR.Ident): Token =
    if (alias.isThisRef()) emptyStatement
    else
      when (useActionTableAliasAs) {
        ActionTableAliasBehavior.UseAs -> +" AS ${alias.token}"
        ActionTableAliasBehavior.SkipAs -> +" ${alias.token}"
        ActionTableAliasBehavior.Hide -> emptyStatement
      }


  val XR.Assignment.token
    get(): Token =
      columnAndValue(this).let { (column, value) -> +"${column.token} = ${value.token}" }


  fun columnAndValue(assignment: XR.Assignment): Pair<Token, Token> {
    val column = when (val property = assignment.property) {
      is XR.Property -> property.token
      else -> xrError("Invalid assignment value of ${assignment}. Must be a Property object.")
    }
    val value = scopedTokenizer(assignment.value)
    return column to value
  }

  fun columnsAndValues(assignments: List<XR.Assignment>, exlcusions: List<XR.Property>): List<Pair<Token, Token>> =
    assignments.filterNot { exlcusions.contains(it.property) }.map { columnAndValue(it) }
}
