package io.exoquery.plugin.trees

import io.decomat.Is
import io.decomat.case
import io.decomat.on
import io.exoquery.BID
import io.exoquery.CapturedBlock
import io.exoquery.SqlAction
import io.exoquery.annotation.ExoDelete
import io.exoquery.annotation.ExoInsert
import io.exoquery.annotation.ExoUpdate
import io.exoquery.innerdsl.SqlActionFilterable
import io.exoquery.innerdsl.setParams
import io.exoquery.parseError
import io.exoquery.plugin.*
import io.exoquery.plugin.logging.Messages
import io.exoquery.plugin.transform.CX
import io.exoquery.plugin.transform.containsBatchParam
import io.exoquery.xr.XR
import io.exoquery.xr.of
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isSubtypeOf
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

object ParseAction {
  context(CX.Scope, CX.Parsing, CX.Symbology, CX.Builder)
  // Parse an action, normally dynamic splicing of actions is not allowed, the only exception to this is from a free(... action ...) call where `action` is dynamic (see the ParseFree in ParserOps)
  fun parse(expr: IrExpression, dynamicCallsAllowed: Boolean = false): XR.Action =
    on(expr).match<XR.Action>(
      // the `insert` part of capture { insert<Person> { set ... } }
      case(ParseFree.match()).thenThis { (components), _ ->
        ParseFree.parse(expr, components, funName)
      },

      case(SqlActionExpr.Uprootable[Is()]).thenThis { uprootable ->
        val sqlActionIr = this
        // Add all binds from the found SqlQuery instance, this will be truned into something like `currLifts + SqlQuery.lifts` late
        binds.addAllParams(sqlActionIr)
        // Then unpack and return the XR
        uprootable.unpackOrErrorXR().successOrParseError(sqlActionIr)
      },

      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<CapturedBlock>(), Is.of("insert", "update"), Is.Companion()]).thenIfThis { _, _ -> ownerHasAnnotation<ExoInsert>() || ownerHasAnnotation<ExoUpdate>() }
        .thenThis { reciever, lambdaRaw ->
          val insertType = this.typeArguments.first() ?: parseError("Could not find the type argument of the insert/update call", expr)
          val compositeType = CompositeType.from(symName) ?: parseError("Unknown composite type: ${symName}", expr)

          on(lambdaRaw).match(
            case(Ir.FunctionExpression.withReturnOnlyBlock[Is()]).thenThis { blockBody ->
              val extensionParam = this.function.symbol.owner.extensionReceiverParameter
              val actionAlias = extensionParam?.makeIdent() ?: parseError("Could not find the extension receiver parameter of the insert/update call", expr)
              parseActionComposite(blockBody, insertType, actionAlias, compositeType)
            }
          ) ?: parseError("The statement inside of a insert/update block must be a single `set` or `setParams` expression followed by excluded, returning/Keys, or onConflict", lambdaRaw)
        },
      case(Ir.Call.FunctionMem0[Ir.Expr.ClassOf<CapturedBlock>(), Is("delete")]).thenIfThis { _, _ -> ownerHasAnnotation<ExoDelete>() }.thenThis { reciever, _ ->
        val deleteType = this.typeArguments.first() ?: parseError("Could not find the type argument of the delete call", expr)
        val deleteTypeXR = TypeParser.ofTypeAt(deleteType, expr.location())
        val dol = '$'
        // Create a synthetic alias for the delete. Normally we get the alias (i.e. in inserts and udates) from the lambda that contains the set(...) clauses
        // but 'delete' doesn't have such clauses. Since insert/update aliasses typically have the look $this$insert/update we'll do the same for delete synthetically.
        val actionAlias = XR.Ident("${dol}this${dol}delete", deleteTypeXR)
        val ent = ParseQuery.parseEntity(deleteType, expr.location())
        XR.Delete(ent, actionAlias, expr.loc)
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlActionFilterable<*, *>>(), Is.of("filter", "where"), Ir.FunctionExpression.withBlock[Is(), Is()]]).then { actionExpr, (args, lambdaBody) ->
        val funName = comp.funName
        val filterAlias =
          when (funName) {
            "filter" -> args.first().makeIdent()
            "where" -> compRight.function.symbol.owner.extensionReceiverParameter?.makeIdent() ?: parseError("Could not find the extension receiver parameter of the `where` call.", compRight)
            else -> parseError("Unknown function name: ${funName}", expr)
          }
        val filterExpr = ParseExpression.parseFunctionBlockBody(lambdaBody)
        val core = parse(actionExpr).let { it as? XR.U.CoreAction ?: parseError("The `.filter` function can only be called on a basic action i.e. insert, update, or delete but got:\n${it.showRaw()}", actionExpr) }
        XR.FilteredAction(core, filterAlias, filterExpr, expr.loc)
      },
      // The .all() function just means perform the update/delete for all rows. Don't need to have a construct for that in the DSL because we have a construct for the other case i.e. SqlActionFiltereable
      case(Ir.Call.FunctionMem0[Ir.Expr.ClassOf<SqlActionFilterable<*, *>>(), Is("all")]).thenThis { actionExpr, _ ->
        parse(actionExpr)
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlAction<*, *>>(), Is("returning"), Ir.FunctionExpression.withBlock[Is(), Is()]]).thenThis { actionExpr, (args, lambdaBody) ->
        val returningAlias = args.first().makeIdent()
        val returningExpr = ParseExpression.parseFunctionBlockBody(lambdaBody)
        val core =
          when (val parsed = parse(actionExpr)) {
            is XR.U.CoreAction -> parsed
            is XR.FilteredAction -> parsed
            else -> parseError("The `.returning` function can only be called on a basic action i.e. insert, update, or delete or a basic-action with a filter but got:\n${parsed.showRaw()}", actionExpr)
          }
        XR.Returning(core, XR.Returning.Kind.Expression(returningAlias, returningExpr), expr.loc)
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlAction<*, *>>(), Is("returningKeys"), Ir.FunctionExpression.withBlock[Is(), Is()]]).then { actionExpr, (_, lambdaBody) ->
        val explain = "\n${Messages.ReturningKeysExplanation}"
        val alias =
          (compRight as IrFunctionExpression).function.symbol.owner.extensionReceiverParameter?.makeIdent() ?: parseError("Could not find the extension receiver parameter of the returningKeys call.${explain}", expr)

        fun validateProperty(prop: XR.Property) {
          if (prop.core() != alias)
            parseError("The returningKeys used a value that was not a column of the entity: ${prop.show(sanitzeIdents = false)} (it's core should have been ${alias.show(sanitzeIdents = false)}).${explain}", lambdaBody)
        }

        val returningExpr = ParseExpression.parseFunctionBlockBody(lambdaBody)
        val props =
          when (val ret = returningExpr) {
            is XR.Product ->
              ret.fields.map {
                val prop = it.second as? XR.Property ?: parseError("Invalid returning-keys value `${ret.show()}`${explain}", lambdaBody)
                validateProperty(prop)
                prop
              }
            is XR.Property -> {
              validateProperty(ret)
              listOf(ret)
            }
            else -> parseError("The returningKeys block must return a product type or a single property.${explain}", lambdaBody)
          }
        val core =
          when (val parsed = parse(actionExpr)) {
            is XR.U.CoreAction -> parsed
            is XR.FilteredAction -> parsed
            else -> parseError("The `.returningKeys` function can only be called on a basic action i.e. insert, update, or delete or a basic-action with a filter but got:\n${parsed.showRaw()}", actionExpr)
          }
        XR.Returning(core, XR.Returning.Kind.Keys(alias, props), expr.loc)
      },
      case(ExtractorsDomain.DynamicActionCall[Is()]).thenIf { _ -> dynamicCallsAllowed }.then { call ->
        val bid = BID.Companion.new()
        binds.addRuntime(bid, expr)
        XR.TagForSqlAction(bid, TypeParser.of(expr), expr.loc)
      }
    ) ?: parseError("Could not parse the action", expr)

  // TODO when going back to the Expression parser the 'this' pointer needs to be on the list of local symbols
  context(CX.Scope, CX.Parsing, CX.Symbology, CX.Builder)
  private fun parseActionComposite(expr: IrExpression, inputType: IrType, actionAlias: XR.Ident, compositeType: CompositeType): XR.Action =
    // the i.e. insert { set(...) } or update { set(...) }
    on(expr).match<XR.Action>(
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<CapturedBlock>(), Is("set"), Ir.Vararg[Is()]]).then { _, (assignments) ->
        val ent = ParseQuery.parseEntity(inputType, expr.location())
        val parsedAssignments = assignments.map { parseAssignment(it) }
        when (compositeType) {
          CompositeType.Insert -> XR.Insert(ent, actionAlias, parsedAssignments, listOf(), expr.loc)
          CompositeType.Update -> XR.Update(ent, actionAlias, parsedAssignments, listOf(), expr.loc)
        }
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<CapturedBlock>(), Is("setParams"), Is()]).thenThis { _, param ->
        val paths = Elaborate.invoke(param)
        val assignments =
          paths.map { epath ->
            val prop = XR.Property.fromCoreAndPaths(actionAlias, epath.path) as? XR.Property ?: parseError("Could not parse empty property path of the entity", epath.invocation)
            val id = BID.new()
            val tpe = TypeParser.of(epath.invocation)
            val (bind, paramType) = run {
              val rawParam = ParamBind.Type.auto(epath.invocation)
              // If it's a batch param need an additional layer of wrapping so that the expr-model knows to create a io.exoquery.ParamBatchRefiner instead of a regular io.exoquery.ParamSingle
              if (batchAlias != null && param.containsBatchParam())
                ParamBind.Type.ParamUsingBatchAlias(batchAlias, rawParam, "_" + epath.path.joinToString("_")) to XR.ParamType.Batch
              else
                rawParam to XR.ParamType.Single
            }
            val tag = XR.TagForParam(id, paramType, tpe, epath.invocation.loc)
            binds.addParam(id, epath.invocation, bind)
            XR.Assignment(prop, tag, epath.invocation.loc)
          }
        val ent = ParseQuery.parseEntity(inputType, expr.location())
        when (compositeType) {
          CompositeType.Insert -> XR.Insert(ent, actionAlias, assignments, listOf(), expr.loc)
          CompositeType.Update -> XR.Update(ent, actionAlias, assignments, listOf(), expr.loc)
        }
      },
      case(Ir.Call.FunctionMemVararg[Ir.Expr.ClassOf<setParams<*>>(), Is("excluding"), Is(), Is()]).thenThis { head, columnExprs ->
        val headAction = parseActionComposite(head, inputType, actionAlias, compositeType)
        val columns =
          columnExprs.map { columnExpr ->
            ParseExpression.parse(columnExpr).let { it as? XR.Property ?: parseError(Messages.InvalidColumnExclusions, columnExpr) }
          }
        when (headAction) {
          is XR.Insert -> headAction.copy(exclusions = columns)
          is XR.Update -> headAction.copy(exclusions = columns)
          else -> parseError("The `excluding` is only allowed for Insert and Update actions", expr)
        }
      }
    ) ?: parseError("Could not parse the expression inside of the action", expr)

  context(CX.Scope, CX.Parsing, CX.Symbology)
  private fun parseAssignment(expr: IrExpression): XR.Assignment =
    on(expr).match<XR.Assignment>(
      case(ExtractorsDomain.Call.`x to y`[Is.Companion(), Is.Companion()]).thenThis { left, right ->
        val property = ParseExpression.parse(left).let { it as? XR.Property ?: parseError("Could not parse the left side of the assignment: ${it.showRaw()}", left) }
        if (!right.type.isSubtypeOf(left.type, typeSystem))
          parseError("Invalid assignment expression `${expr.source()}`. The left-hand type `${left.type.dumpKotlinLike()}` is different from the right-hand type `${right.type.dumpKotlinLike()}`", expr)
        if (property.type.isProduct())
          parseError("Invalid assignment expression `${expr.source()}`. The left-hand type `${left.type.dumpKotlinLike()}` is a product-type which is not allowed.\n${Messages.ProductTypeInsertInstructions}", expr)

        if (!((property.core() as? XR.Ident)?.let { it.isThisRef() } ?: false))
          parseError("Invalid assignment expression `${expr.source()}`. The left-hand side of the assignment must be a column of the entity\n${Messages.ActionExample}", expr)

        // Can check on this level if there's a batch-param but might have performance implications
        //if (right.containsBatchParam()) parseError(Messages.UsingBatchParam, expr)
        val rhs = ParseExpression.parse(right)
        XR.Assignment(property, rhs, expr.loc)
      }
    ) ?: parseError("Could not parse the assignment", expr)

  sealed interface CompositeType {
    object Insert : CompositeType;
    object Update : CompositeType

    companion object {
      fun from(str: String) =
        when (str) {
          "insert" -> Insert
          "update" -> Update
          else -> null
        }
    }
  }

}
