package io.exoquery.sql

import io.exoquery.sql.SqlIdiom.Companion.DefaultMethodMappings
import io.exoquery.util.TraceConfig
import io.exoquery.util.Tracer
import io.exoquery.util.unaryPlus
import io.exoquery.xr.BetaReduction
import io.exoquery.xr.OP
import io.exoquery.xr.XR
import io.exoquery.xrError

class MySqlDialect(override val traceConf: TraceConfig = TraceConfig.empty) : SqlIdiom {
  override val useActionTableAliasAs = SqlIdiom.ActionTableAliasBehavior.UseAs

  override val trace: Tracer by lazy { Tracer(traceType, traceConf, 1) }

  override fun varcharType(): Token = "CHAR".token

  override fun xrBinaryOpTokenImpl(binaryOpImpl: XR.BinaryOp): Token = with(binaryOpImpl) {
    when {
      op is OP.StrPlus -> +"CONCAT(${a.token}, ${b.token})"
      else -> super.xrBinaryOpTokenImpl(binaryOpImpl)
    }
  }

  // TODO replace LEN with LENGTH
  override val methodMappings: Map<XR.FqName, String> =
    DefaultMethodMappings

  override fun xrOrderByCriteriaTokenImpl(orderByCriteriaImpl: XR.OrderField): Token = with(orderByCriteriaImpl) {
    when {
      orderingOpt == null -> field.token
      orderingOpt == XR.Ordering.AscNullsFirst -> +"${field.token} ASC"
      orderingOpt == XR.Ordering.DescNullsFirst -> +"ISNULL(${field.token}) DESC, ${field.token} DESC"
      orderingOpt == XR.Ordering.AscNullsLast -> +"ISNULL(${field.token}) ASC, ${field.token} ASC"
      orderingOpt == XR.Ordering.DescNullsLast -> +"${field.token} DESC"
      else -> super.xrOrderByCriteriaTokenImpl(orderByCriteriaImpl)
    }
  }

  // MySQL does not allow limit without offset. See: https://stackoverflow.com/questions/255517/mysql-offset-infinite-rows
  override fun limitOffsetToken(query: Statement, limit: XR.Expression?, offset: XR.Expression?): Token =
    when {
      limit == null && offset != null -> +"$query LIMIT 18446744073709551610 OFFSET ${offset.token}"
      else -> super.limitOffsetToken(query, limit, offset)
    }

  override fun prepareForTokenization(onConflictRaw: XR.OnConflict): XR.OnConflict = run {
    // In MySQL the non-excluded part needs to be the entity name, for example:
    //  INSERT INTO Person ... ON DUPLICATE KEY UPDATE firstName = (CONCAT(CONCAT(Person.firstName, '_'), x.firstName))
    // That means that: excludedIdent -> "x"
    // and: existingIdent -> "Person" (i.e. EntityName)

    if (onConflictRaw.resolution is XR.OnConflict.Resolution.Update) {
      val actionAlias = onConflictRaw.insert.alias
      val x = actionAlias.copy(name = "x")
      val onConflict =
        onConflictRaw.copy(
          insert = onConflictRaw.insert.copy(
            // NOTE that beta reduction won't replace aliases on declarations, only inside of properties. This is by design.
            alias = x,
            assignments = onConflictRaw.insert.assignments.map { asi ->
              asi.copy(
                // `UPDATE actionAlias.firstName = ... actionAlias.lastName = ...` need to turn into:
                // `UPDATE hiddenref.firstName = ... hiddenref.lastName = ...`
                property = BetaReduction(asi.property, actionAlias to actionAlias.copy(XR.Ident.HiddenRefName)) as XR.Property
              )
            }
          ),
          resolution = onConflictRaw.resolution.copy(
            existingParamIdent = x
          )
        )
      val entityName = onConflictRaw.resolution.existingParamIdent.copy(name = onConflictRaw.insert.query.name)
      val newOnConflict = BetaReduction.ofXR(onConflict, actionAlias to x, onConflictRaw.resolution.existingParamIdent to entityName) as XR.OnConflict
      newOnConflict
    } else {
      onConflictRaw
    }
  }

  override fun xrOnConflictTokenImpl(onConflictImpl: XR.OnConflict): Token = with(prepareForTokenization(onConflictImpl)) {
    when {
      target == XR.OnConflict.Target.NoTarget && resolution == XR.OnConflict.Resolution.Update -> xrError("'DO UPDATE' statement requires explicit conflict target")
      target == XR.OnConflict.Target.NoTarget && resolution == XR.OnConflict.Resolution.Ignore -> {
        with(insert) {
          val (columns, values) = columnsAndValues(assignments, exclusions).unzip()
          +"INSERT IGNORE INTO ${query.token} (${columns.mkStmt(", ")}) VALUES ${tokenizeInsertAssignemnts(values)}"
        }
      }

      resolution is XR.OnConflict.Resolution.Update -> {
        // TODO warn if onConflict target is Properties, they are not rendered in MySQL
        val insertAlias = insert.alias
        val updateAssignments = resolution.assignments.map { BetaReduction.ofXR(it, resolution.excludedId to resolution.excludedId.copy(insertAlias.name)) as XR.Assignment }
        doUpdateStmt(insert, updateAssignments)
      }

      target is XR.OnConflict.Target.Properties && resolution is XR.OnConflict.Resolution.Ignore -> {
        xrError("OnConflict with specified properties and IGNORE resolution is not supported for MySQL")
      }

      else -> xrError("Unsupported OnConflict form: ${onConflictImpl.showRaw()}")
    }
  }

  fun doUpdateStmt(insert: XR.Insert, updateAssignments: List<XR.Assignment>): Token = with(insert) {
    val updateAssignmentsToken = updateAssignments.map { it.token }.mkStmt()
    val query = this.query as? XR.Entity ?: xrError("Insert query must be an entity but found: ${this.query}")
    val (columns, values) = columnsAndValues(assignments, exclusions).unzip()
    +"INSERT INTO ${query.token} (${columns.mkStmt(", ")}) VALUES ${tokenizeInsertAssignemnts(values)} AS ${alias.token} ON DUPLICATE KEY UPDATE ${updateAssignmentsToken}"
  }

  override fun XR.U.QueryOrExpression.stringConversionMapping(name: String): Token = run {
    val head = this
    when (name) {
      "toLong" -> +"CAST(${head.token} AS SIGNED)"
      "toInt" -> +"CAST(${head.token} AS SIGNED)"
      "toShort" -> +"CAST(${head.token} AS SIGNED)"
      "toDouble" -> +"CAST(${head.token} AS DOUBLE PRECISION)"
      "toFloat" -> +"CAST(${head.token} AS REAL)"
      "toBoolean" -> +"CASE WHEN ${head.token} = 'true' THEN 1 ELSE 0 END"
      "toString" -> +"${head.token}"
      else -> throw IllegalArgumentException("Unknown conversion function: ${name}")
    }
  }
}

//
//  override def defaultAutoGeneratedToken(field: Token) = stmt"($field) VALUES (DEFAULT)"
//
//  override implicit def operationTokenizer(implicit
//    astTokenizer: Tokenizer[Ast],
//    strategy: NamingStrategy
//  ): Tokenizer[Operation] =
//    Tokenizer[Operation] {
//      case BinaryOperation(a, StringOperator.`+`, b) => stmt"CONCAT(${a.token}, ${b.token})"
//      case other                                     => super.operationTokenizer.token(other)
//    }
//
//  override implicit def orderByCriteriaTokenizer(implicit
//    astTokenizer: Tokenizer[Ast],
//    strategy: NamingStrategy
//  ): Tokenizer[OrderByCriteria] = Tokenizer[OrderByCriteria] {
//    case OrderByCriteria(prop, AscNullsFirst | Asc)  => stmt"${prop.token} ASC"
//    case OrderByCriteria(prop, DescNullsFirst)       => stmt"ISNULL(${prop.token}) DESC, ${prop.token} DESC"
//    case OrderByCriteria(prop, AscNullsLast)         => stmt"ISNULL(${prop.token}) ASC, ${prop.token} ASC"
//    case OrderByCriteria(prop, DescNullsLast | Desc) => stmt"${prop.token} DESC"
//  }
//
//  override protected def limitOffsetToken(
//    query: Statement
//  )(implicit astTokenizer: Tokenizer[Ast], strategy: NamingStrategy) =
//    Tokenizer[(Option[Ast], Option[Ast])] {
//      case (None, Some(offset)) => stmt"$query LIMIT 18446744073709551610 OFFSET ${offset.token}"
//      case other                => super.limitOffsetToken(query).token(other)
//    }
//}
//
//object MySQLDialect extends MySQLDialect
//
//trait MySQL5Dialect extends MySQLDialect {
//  override def useActionTableAliasAs: ActionTableAliasBehavior = ActionTableAliasBehavior.Hide
//}
//
//object MySQL5Dialect extends MySQL5Dialect
