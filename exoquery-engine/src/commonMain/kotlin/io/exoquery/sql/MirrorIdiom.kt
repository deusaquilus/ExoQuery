package io.exoquery.sql

import io.exoquery.sql.mkStmt
import io.exoquery.util.interleaveWith
import io.exoquery.util.stmt
import io.exoquery.xr.AggregationOperator
import io.exoquery.xr.BinaryOperator
import io.exoquery.xr.OP
import io.exoquery.xr.PostfixUnaryOperator
import io.exoquery.xr.PrefixUnaryOperator
import io.exoquery.xr.SX
import io.exoquery.xr.SelectClause
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.xr.id


fun String.uncapitalize() = replaceFirstChar { it.lowercaseChar() }

class MirrorIdiom(val renderOpts: RenderOptions = RenderOptions()) {
  data class RenderOptions(val showProductConstructorFields: Boolean = true, val showMethodCallSuffixes: Boolean = true, val showFullBids: Boolean = false)

  val <T : XR> T.tokenScoped: Token
    get() =
      when (this) {
        is XR.BinaryOp -> stmt("(${this.token})")
        else -> this.token
      }

  fun <T : XR> List<T>.token(elementTokenizer: (T) -> Token): Token =
    if (this.isEmpty())
      stmt("emptyList()")
    else
      stmt("listOf(${this.map(elementTokenizer).mkStmt()})")


  val XR.token: Token
    get() =
      when (this) {
        is XR.Query -> this.token
        is XR.Expression -> this.token
        is XR.Branch -> this.token
        is XR.Variable -> this.token
        is XR.Action -> this.token
        is XR.Assignment -> this.token
        is XR.Batching -> this.token
      }

//  implicit final def actionTokenizer(implicit externalTokenizer: Tokenizer[External]): Tokenizer[AstAction] =
//  Tokenizer[AstAction] {
//    case Update(query, assignments)    => stmt"${query.token}.update(${assignments.token})"
//    case Insert(query, assignments)    => stmt"${query.token}.insert(${assignments.token})"
//    case Delete(query)                 => stmt"${query.token}.delete"
//    case Returning(query, alias, body) => stmt"${query.token}.returning((${alias.token}) => ${body.token})"
//    case ReturningGenerated(query, alias, body) =>
//    stmt"${query.token}.returningGenerated((${alias.token}) => ${body.token})"
//    case Foreach(query, alias, body) => stmt"${query.token}.foreach((${alias.token}) => ${body.token})"
//    case c: OnConflict               => stmt"${c.token}"
//  }

  val XR.Batching.token: Token
    get() =
      stmt("batch { ${alias.token} -> ${action.token} }")

  val XR.Insert.exclusionsClause: Token
    get() =
      if (this.exclusions.isEmpty())
        stmt("")
      else
        stmt(".excluding(${this.exclusions.token { it.token }})")

  val XR.Update.exclusionsClause: Token
    get() =
      if (this.exclusions.isEmpty())
        stmt("")
      else
        stmt(".excluding(${this.exclusions.token { it.token }})")

  val XR.Action.token: Token
    get() =
      when (this) {
        is XR.Update -> stmt("update<${query.name.token}> { set(${assignments.map { it.token }.mkStmt()})${exclusionsClause} }")
        is XR.Insert -> stmt("insert<${query.name.token}> { set(${assignments.map { it.token }.mkStmt()})${exclusionsClause} }")
        is XR.Delete -> stmt("delete<${query.name.token}>")
        is XR.Returning -> this.token
        is XR.FilteredAction -> stmt("${action.token}.filter { ${alias.token} -> ${filter.token} }")
        is XR.OnConflict -> stmt("${this.token}")
        is XR.Free -> this.token
        is XR.TagForSqlAction -> stmt("""TagA("${id.value.trimId()}")""")
      }

  val XR.Assignment.token: Token
    get() =
      stmt("${property.token} to ${value.token}")

//  implicit final def assignmentTokenizer(implicit externalTokenizer: Tokenizer[External]): Tokenizer[Assignment] =
//  Tokenizer[Assignment] { case Assignment(ident, property, value) =>
//    stmt"${ident.token} => ${property.token} -> ${value.token}"
//  }

  val XR.Returning.token: Token
    get() =
      when (this.kind) {
        is XR.Returning.Kind.Expression -> stmt("${action.token}.returning { ${this.kind.alias.token} -> ${this.kind.expr.token} }")
        is XR.Returning.Kind.Keys -> stmt("${action.token}.returningKeys { ${this.kind.keys.token { it.token }} }")
      }

  val XR.OnConflict.token: Token
    get() =
      when (this.resolution) {
        is XR.OnConflict.Resolution.Update -> stmt("${insert.token}.onConflictUpdate${target.token}(${this.resolution.assignments.token { it.token }})")
        is XR.OnConflict.Resolution.Ignore -> stmt("${insert.token}.onConflictIgnore${target.token}")
      }

//    Tokenizer[OnConflict] {
//      case OnConflict(i, t, OnConflict.Update(assign)) =>
//        stmt"${i.token}.onConflictUpdate${t.token}(${assign.map(updateAssignsTokenizer.token).mkStmt()})"
//      case OnConflict(i, t, OnConflict.Ignore) =>
//        stmt"${i.token}.onConflictIgnore${t.token}"
//    }

  val XR.OnConflict.Target.token: Token
    get() =
      when (this) {
        is XR.OnConflict.Target.NoTarget -> stmt("")
        is XR.OnConflict.Target.Properties -> stmt("(${props.token { it.token }})")
      }

//  implicit val conflictTargetTokenizer: Tokenizer[OnConflict.Target] = Tokenizer[OnConflict.Target] {
//    case OnConflict.NoTarget          => emptyStatement
//    case OnConflict.Properties(props) => stmt"(${targetProps(props).token})"
//  }


  val XR.Variable.token: Token get() = stmt("val ${name.token} = ${this.rhs.token}")
  val XR.Branch.token: Token get() = stmt("${cond.token} -> ${then.token}")

  val XR.Ident.token: Token
    get() = name
      .token

  val XR.JoinType.token: Token get() = simpleName.token

  val BinaryOperator.token: Token get() = symbol.token
  val AggregationOperator.token: Token get() = symbol.token

  // stmt"${scopedTokenizer(function)}.apply(${values.token})"
  val XR.FunctionApply.token: Token get() = stmt("${function.tokenScoped}.apply(${args.map { it.token }.mkStmt(", ")})")
  val XR.FunctionN.token: Token get() = stmt("{ ${params.map { it.name.token }.mkStmt(", ")} -> ${body.token} }")
  val XR.Const.token: Token
    get() =
      when (this) {
        is XR.ConstType<*> -> stmt("${value.toString().token}")
        is XR.Const.Null -> stmt("null")
      }

  val XR.U.QueryOrExpression.token: Token
    get() =
      when (this) {
        is XR.Query -> this.token
        is XR.Expression -> this.token
      }

  fun productKV(k: String, v: XR.Expression): Token =
    if (renderOpts.showProductConstructorFields)
      stmt("${k.token} = ${v.token}")
    else
      stmt("${v.token}")

  fun XR.MethodCall.suffix() =
    if (renderOpts.showMethodCallSuffixes && this.isKotlinSynthetic)
      stmt("_MCS")
    else if (renderOpts.showMethodCallSuffixes)
      stmt("_MC")
    else
      stmt("")

  fun XR.GlobalCall.suffix() =
    if (renderOpts.showMethodCallSuffixes && this.isKotlinSynthetic)
      stmt("_GCS")
    else if (renderOpts.showMethodCallSuffixes)
      stmt("_GC")
    else
      stmt("")

  val XR.Ordering.token: Token get() = (this::class.simpleName ?: "UNKNOWN").token

  val XR.GlobalCall.token: Token
    get() =
      stmt("${name.name.token}${suffix().token}(${args.map { it.token }.mkStmt(", ")})")
  val XR.MethodCall.token: Token
    get() =
      stmt("${head.tokenScoped}.${name.token}${suffix().token}(${args.map { it.token }.mkStmt(", ")})")

  val XR.Expression.token: Token
    get() =
      when (this) {
        is XR.BinaryOp -> stmt("${a.token} ${op.token} ${b.token}")

        is XR.UnaryOp ->
          when (op) {
            is PrefixUnaryOperator -> stmt("${op.symbol.token}${expr.token}")
          }

        is XR.Block -> stmt("{ ${stmts.map { it.token }.mkStmt("; ").token}; ${output.token} }")

        is XR.FunctionN -> this.token
        is XR.GlobalCall -> this.token
        is XR.MethodCall -> this.token
        is XR.When ->
          if (this.branches.size == 1)
          // for only one branch, make it a if-statement
            stmt("if (${this.branches.first().cond.token}) ${this.branches.first().then.token} else ${orElse.token}")
          else
            stmt("when { ${this.branches.map { it.token }.mkStmt("; ").token}; else ->- ${orElse.token} }")

        is XR.FunctionApply -> this.token
        is XR.Ident -> this.token
        is XR.Free -> this.token
        is XR.Const -> this.token

        is XR.Window -> stmt("window().partitionBy(${partitionBy.map { it.token }.mkStmt(", ")}).orderBy(${orderBy.map { it.token }.mkStmt(", ")}).over(${over.token})")

        // scala: stmt"${name.token}(${values.map { case (k, v) -> s"${k.token}: ${v.token}" }.mkString(", ").token})"
        is XR.Product ->
          // TODO should have a 'showFieldNames' setting to disable display of field-names in products
          stmt("${name.token}(${fields.map { (k, v) -> productKV(k, v) }.mkStmt()})")
        is XR.Property ->
          stmt("${of.tokenScoped}.${name.token}")
        is XR.QueryToExpr ->
          stmt("${head.token}.toExpr")
        is XR.TagForParam ->
          stmt("""TagP("${id.value.trimId()}")""")
        is XR.TagForSqlExpression ->
          stmt("""TagE("${id.value.trimId()}")""")
      }

  private val String.lastPart get() = this.split('.').last()


  val XR.OrderField.token: Token
    get() =
      when (this) {
        is XR.OrderField.By -> stmt("${field.token} to ${ordering.token}")
        is XR.OrderField.Implicit -> stmt("${field.token}")
      }

  val XR.Query.token: Token
    get() =
      when (this) {
        is XR.Entity ->
          stmt("${"Table".token}(${name.token})")
        is XR.Filter ->
          stmt("${head.token}.filter { ${id.token} -> ${body.token} }")
        is XR.Map ->
          stmt("${head.token}.map { ${id.token} -> ${body.token} }")
        is XR.FlatMap ->
          stmt("${head.token}.flatMap { ${id.token} -> ${body.token} }")
        is XR.ConcatMap ->
          stmt("${head.token}.concatMap { ${id.token} -> ${body.token} }")
        is XR.SortBy ->
          stmt("${head.token}.sortBy { ${criteria.map { it.token }.mkStmt()} }")
        is XR.Take ->
          stmt("${head.token}.take(${num.token})")
        is XR.Drop ->
          stmt("${head.token}.drop(${num.token})")
        is XR.Union ->
          stmt("${a.token}.union(${b.token})")
        is XR.UnionAll ->
          stmt("${a.token}.unionAll(${b.token})")
        is XR.FlatJoin ->
          stmt("${head.token}.${joinType.token} { ${id.token} -> ${on.token} }")
        is XR.FlatFilter ->
          stmt("where(${by.token})")
        is XR.FlatGroupBy ->
          stmt("groupBy(${by.token})")
        is XR.FlatSortBy ->
          stmt("sortBy(${criteria.map { it.token }.mkStmt()})")
        is XR.Distinct ->
          stmt("${head.token}.distinct")
        is XR.DistinctOn ->
          stmt("${head.token}.distinctOn { ${id.token} -> ${by.token} }")
        is XR.Nested ->
          stmt("${head.token}.nested")
        is XR.CustomQueryRef -> this.token
        is XR.ExprToQuery -> stmt("${head.tokenScoped}.toQuery")
        is XR.FunctionApply -> this.token
        is XR.Ident -> this.token
        is XR.Free -> this.token
        is XR.TagForSqlQuery -> stmt("""TagQ("${id.value.trimId()}")""")
        is XR.GlobalCall -> this.token
        is XR.MethodCall -> this.token
      }

  private fun String.trimId() =
    if (renderOpts.showFullBids)
      this
    else
      this.takeLast(5)

// Scala:
//  implicit final def queryTokenizer(implicit externalTokenizer: Tokenizer[External]): Tokenizer[AstQuery] =
//    Tokenizer[AstQuery] {
//
//      case Entity.Opinionated(name, Nil, _, renameable) ->
//        stmt"${tokenizeName("querySchema", renameable).token}(${s""""$name"""".token})"
//
//      case Entity.Opinionated(name, prop, _, renameable) ->
//        val properties = prop.map(p -> stmt"""_.${p.path.mkStmt(".")} -> "${p.alias.token}"""")
//        stmt"${tokenizeName("querySchema", renameable).token}(${s""""$name"""".token}, ${properties.token})"
//
//      case Filter(source, alias, body) ->
//        stmt"${source.token}.filter(${alias.token} -> ${body.token})"
//
//      case Map(source, alias, body) ->
//        stmt"${source.token}.map(${alias.token} -> ${body.token})"
//
//      case FlatMap(source, alias, body) ->
//        stmt"${source.token}.flatMap(${alias.token} -> ${body.token})"
//
//      case ConcatMap(source, alias, body) ->
//        stmt"${source.token}.concatMap(${alias.token} -> ${body.token})"
//
//      case SortBy(source, alias, body, ordering) ->
//        stmt"${source.token}.sortBy(${alias.token} -> ${body.token})(${ordering.token})"
//
//      case GroupBy(source, alias, body) ->
//        stmt"${source.token}.groupBy(${alias.token} -> ${body.token})"
//
//      case GroupByMap(source, byAlias, byBody, mapAlias, mapBody) ->
//        stmt"${source.token}.groupByMap(${byAlias.token} -> ${byBody.token})(${mapAlias.token} -> ${mapBody.token})"
//
//      case Aggregation(op, ast) ->
//        stmt"${scopedTokenizer(ast)}.${op.token}"
//
//      case Take(source, n) ->
//        stmt"${source.token}.take(${n.token})"
//
//      case Drop(source, n) ->
//        stmt"${source.token}.drop(${n.token})"
//
//      case Union(a, b) ->
//        stmt"${a.token}.union(${b.token})"
//
//      case UnionAll(a, b) ->
//        stmt"${a.token}.unionAll(${b.token})"
//
//      case Join(t, a, b, iA, iB, on) ->
//        stmt"${a.token}.${t.token}(${b.token}).on((${iA.token}, ${iB.token}) -> ${on.token})"
//
//      case FlatJoin(t, a, iA, on) ->
//        stmt"${a.token}.${t.token}((${iA.token}) -> ${on.token})"
//
//      case Distinct(a) ->
//        stmt"${a.token}.distinct"
//
//      case DistinctOn(source, alias, body) ->
//        stmt"${source.token}.distinctOn(${alias.token} -> ${body.token})"
//
//      case Nested(a) ->
//        stmt"${a.token}.nested"
//    }


  val XR.Free.token: Token
    get() = run {
      val dol = ('$' + "").token
      fun tokenParam(ast: XR): Token =
        when (ast) {
          is XR.Ident -> stmt("${dol}${ast.token}")
          else -> stmt("${dol}{${ast.token}}")
        }

      val pt = parts.map { it.token }
      val pr = params.map { tokenParam(it) }
      val body = pt.interleaveWith(pr)

      val suffix =
        when {
          this.pure && this.type == XRType.BooleanExpression -> stmt(".asPureCondition()")
          !this.pure && this.type == XRType.BooleanExpression -> stmt(".asCondition()")
          this.pure -> stmt(".asPure()")
          else -> stmt(".invoke()")
        }

      stmt("free(\"${body.token { it }}\")${suffix.token}")
    }


// Scala:
//  implicit final def infixTokenizer(implicit externalTokenizer: Tokenizer[External]): Tokenizer[Free] =
//  Tokenizer[Free] { case Free(parts, params, _, _, _) ->
//    def tokenParam(ast: Ast) =
//    ast match {
//      case ast: Ident -> stmt"$$${ast.token}"
//      case _          -> stmt"$${${ast.token}}"
//    }
//
//    val pt   = parts.map(_.token)
//    val pr   = params.map(tokenParam)
//    val body = Statement(Interleave(pt, pr))
//    stmt"""sql"${body.token}""""
//  }


  // ----------------- Tokenizer for SelectClause and its fields -----------------
  val XR.CustomQueryRef.token: Token
    get() =
      when (this.customQuery) {
        is SelectClause -> customQuery.token
        else -> stmt("CustomQueryRef(${this.customQuery.toString()})")
      }

  val SelectClause.token: Token
    get() = run {
      val components = allComponents().map { it.token }.mkStmt("; ")
      val select =
        if (allComponents().isNotEmpty()) stmt("; ${select.token}") else stmt("${select.token}")
      stmt("select { ${components}${select} }")
    }

  val SX.token: Token
    get() =
      when (this) {
        is SX.From -> stmt("val ${variable.token} = from(${xr.token})")
        is SX.Join -> stmt("val ${variable.token} = ${joinType.simpleName.token}(${onQuery.token}) { ${condition.token} }")
        is SX.ArbitraryAssignment -> stmt("val ${variable.token} = /*ASI*/ ${expression.token}")
        is SX.GroupBy -> stmt("groupBy(${grouping.token})")
        is SX.SortBy -> stmt("sortBy(${criteria.map { it.token }.mkStmt()})")
        is SX.Where -> stmt("where(${condition.token})")
      }

}
