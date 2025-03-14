package io.exoquery.plugin.trees

import io.decomat.Is
import io.decomat.case
import io.decomat.on
import io.exoquery.BID
import io.exoquery.CapturedBlock
import io.exoquery.SqlAction
import io.exoquery.annotation.ExoInsert
import io.exoquery.annotation.ExoUpdate
import io.exoquery.innerdsl.setParams
import io.exoquery.parseError
import io.exoquery.plugin.loc
import io.exoquery.plugin.location
import io.exoquery.plugin.logging.Messages
import io.exoquery.plugin.ownerHasAnnotation
import io.exoquery.plugin.source
import io.exoquery.plugin.symName
import io.exoquery.plugin.transform.CX
import io.exoquery.xr.XR
import org.jetbrains.kotlin.ir.backend.js.utils.typeArguments
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isSubtypeOf
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

object ParseAction {
  context(CX.Scope, CX.Parsing, CX.Symbology, CX.Builder)
  fun parse(expr: IrExpression): XR.Action =
    on(expr).match<XR.Action> (
      // the `insert` part of capture { insert<Person> { set ... } }
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<CapturedBlock>(), Is.Companion.of("insert", "update"), Is.Companion()]).thenIfThis { _, _ -> ownerHasAnnotation<ExoInsert>() || ownerHasAnnotation<ExoUpdate>() }.thenThis { reciever, lambdaRaw ->
        val insertType = this.typeArguments.first() ?: parseError("Could not find the type argument of the insert/update call", expr)
        val compositeType = CompositeType.from(symName) ?: parseError("Unknown composite type: ${symName}", expr)

        on(lambdaRaw).match(
          case(Ir.FunctionExpression.withReturnOnlyBlock[Is.Companion()]).thenThis { blockBody ->
            val extensionParam = this.function.symbol.owner.extensionReceiverParameter
            val actionAlias = extensionParam?.makeIdent() ?: parseError("Could not find the extension receiver parameter of the insert/update call", expr)
            parseActionComposite(blockBody, insertType, actionAlias, compositeType)
          }
        ) ?: parseError("The statement inside of a insert/update block must be a single `set` or `setParams` expression followed by excluded, returning/Keys, or onConflict", lambdaRaw)
      },
      case(Ir.Call.FunctionMem1[Ir.Expr.ClassOf<SqlAction<*, *>>(), Is("returning"), Ir.FunctionExpression.withBlock[Is(), Is()]]).thenThis { actionExpr, (args, lambdaBody) ->
        val returningAlias = args.first().makeIdent()
        val returningExpr = ParseExpression.parseFunctionBlockBody(lambdaBody)
        val core = parse(actionExpr) as? XR.U.CoreAction ?: parseError("The `.returning` function can only be called on a basic action i.e. insert, update, ro delete", actionExpr)
        XR.Returning(core, XR.Returning.Kind.Expression(returningAlias, returningExpr), expr.loc)
      }
      // TODO parse returning columns
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
            val param = XR.TagForParam(id, XR.ParamType.Single, tpe, epath.invocation.loc)
            binds.addParam(id, epath.invocation, ParamBind.Type.auto(epath.invocation))
            XR.Assignment(prop, param, epath.invocation.loc)
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

        XR.Assignment(property, ParseExpression.parse(right), expr.loc)
      }
    ) ?: parseError("Could not parse the assignment", expr)

  sealed interface CompositeType {
    object Insert: CompositeType; object Update: CompositeType

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
