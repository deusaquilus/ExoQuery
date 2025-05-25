package io.exoquery.norm.verify

import io.exoquery.norm.verify.FreeVariables.Companion.invoke
import io.exoquery.sql.*
import io.exoquery.xr.XR

class VerifySqlQuery {
  fun invoke(query: SqlQueryModel): FreeVariables.Result =
    verify(query)

  private fun verify(query: SqlQueryModel): FreeVariables.Result =
    when (query) {
      is FlattenSqlQuery -> verifyFlatJoin(query)
      is SetOperationSqlQuery -> verify(query.a) + verify(query.b)
      is UnaryOperationSqlQuery -> verify(query.query)
      is TopLevelFree -> FreeVariables(query.value).toFreeVarsResult()
    }

  private fun verifyFlatJoinDeep(q: FlattenSqlQuery): FreeVariables.Result {
    val invalidAliases = mutableSetOf<IdentName>()
    fun loop(l: List<FromContext>, available: Set<IdentName>): Set<IdentName> =
      l.fold(available) { av, ctx ->
        when (ctx) {
          is TableContext -> setOf(ctx.aliasIdent().asIdName())
          is ExpressionContext -> setOf(ctx.aliasIdent().asIdName())
          is QueryContext -> setOf(ctx.aliasIdent().asIdName())
          is FlatJoinContext -> {
            val nav = av + loop(listOf(ctx.from), av)
            val free = FreeVariables(ctx.on).map { it }
            val invalid = free - nav
            invalidAliases += invalid
            nav
          }
        }
      }
    // run the actual loop
    loop(q.from, setOf())
    return invalidAliases.toFreeVarsResult()
  }

  fun Set<IdentName>.toFreeVarsResult(): FreeVariables.Result =
    when {
      isEmpty() -> FreeVariables.Result.None
      else -> FreeVariables.Result.Detected(toSet())
    }

  private fun verifyFlatJoin(q: FlattenSqlQuery): FreeVariables.Result {
    val invalidWithin = verifyFlatJoinDeep(q)
    val aliases = q.from.flatMap { aliases(it) }

    fun verifyAst(ast: XR): FreeVariables.Result = run {
      val freeVariables = (FreeVariables(ast) - aliases).toList()
      freeVariables.toSet().toFreeVarsResult()
    }

    // Recursively expand children until values are fully flattened. Identities in all these should
    // be skipped during verification.
    fun expandSelect(sv: SelectValue): List<SelectValue> =
      when (val ast = sv.expr) {
        is XR.Product -> ast.fields.map { SelectValue(it.second) }.flatMap { expandSelect(it) }
        else -> listOf(sv)
      }

    fun XR.Expression?.verifyOrSkip(): FreeVariables.Result =
      this?.let { verifyAst(it) } ?: FreeVariables.Result.None

    fun List<XR.Expression>.verifyOrSkip(): FreeVariables.Result =
      this.map { it.verifyOrSkip() }.reduce { a, b -> a + b }

    val freeVariableErrors =
      q.where.verifyOrSkip() +
          q.orderBy.map { ord -> ord.field }.verifyOrSkip() +
          q.limit.verifyOrSkip() +
          q.select
            .flatMap { expandSelect(it) }
            .map { it.expr }
            .filterNot { it is XR.Ident }
            .verifyOrSkip() +
          q.from.map {
            when (it) {
              is FlatJoinContext -> it.on.verifyOrSkip()
              is QueryContext -> verify(it.query)
              else -> FreeVariables.Result.None
            }
          }.reduce { a, b -> a + b }

    return freeVariableErrors
  }

  private fun aliases(s: FromContext): List<IdentName> =
    when (s) {
      is TableContext -> listOf(s.aliasIdent().asIdName())
      is QueryContext -> listOf(s.aliasIdent().asIdName())
      is ExpressionContext -> listOf(s.aliasIdent().asIdName())
      is FlatJoinContext -> aliases(s.from)
    }

}
