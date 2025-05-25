package io.exoquery.plugin.trees

import io.decomat.Is
import io.decomat.case
import io.decomat.match
import io.exoquery.Ord
import io.exoquery.parseError
import io.exoquery.plugin.printing.dumpSimple
import io.exoquery.plugin.transform.CX
import io.exoquery.xr.XR
import org.jetbrains.kotlin.ir.expressions.IrExpression

object ParseOrder {
  // Can either be `x to Ord` or Pair(x, Ord)
  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseOrdTuple(expr: IrExpression): Pair<XR.Expression, XR.Ordering> =
    expr.match(
      case(ExtractorsDomain.Call.`x to y`[Is.Companion(), Is.Companion()]).thenThis { property, ord ->
        val propertyXR = ParseExpression.parse(property)
        if (propertyXR.type.isProduct()) {
          parseError("You cannot order by `${propertyXR.show()}` because this is composite type (i.e. a type that consists of multiple columns). You must order by a single column.", property)
        }
        propertyXR to parseOrd(ord)
      }
    ) ?: parseError("Could not parse a proper ordering from the expression: ${expr.dumpSimple()}. Orderings must always come in the form `property to Ord` for example `person.name to Desc`.", expr)

  context(CX.Scope, CX.Parsing, CX.Symbology) fun parseOrd(expr: IrExpression): XR.Ordering =
    expr.match(
      case(Ir.Expr.ClassOf<Ord.Asc>()).then { XR.Ordering.Asc },
      case(Ir.Expr.ClassOf<Ord.Desc>()).then { XR.Ordering.Desc },
      case(Ir.Expr.ClassOf<Ord.AscNullsFirst>()).then { XR.Ordering.AscNullsFirst },
      case(Ir.Expr.ClassOf<Ord.DescNullsFirst>()).then { XR.Ordering.DescNullsFirst },
      case(Ir.Expr.ClassOf<Ord.AscNullsLast>()).then { XR.Ordering.AscNullsLast },
      case(Ir.Expr.ClassOf<Ord.DescNullsLast>()).then { XR.Ordering.DescNullsLast },
    ) ?: parseError(
      "Could not parse an ordering from the expression: ${expr.dumpSimple()}. Orderings must be specified as one of the following compile-time constant values: Asc, Desc, AscNullsFirst, DescNullsFirst, AscNullsLast, DescNullsLast",
      expr
    )
}
