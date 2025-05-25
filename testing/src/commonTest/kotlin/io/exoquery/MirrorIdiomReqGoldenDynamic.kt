package io.exoquery

import io.exoquery.printing.GoldenResult
import io.exoquery.printing.cr
import io.exoquery.printing.kt

object MirrorIdiomReqGoldenDynamic: GoldenQueryFile {
  override val queries = mapOf<String, GoldenResult>(
    "XR.Expression/XR.Ident" to kt(
      "foo"
    ),
    "XR.Expression/XR.Const.Int" to kt(
      "42"
    ),
    "XR.Expression/XR.Const.Long" to kt(
      "42"
    ),
    "XR.Expression/XR.Const.Short" to kt(
      "42"
    ),
    "XR.Expression/XR.Const.String" to kt(
      "foo"
    ),
    "XR.Expression/XR.Const.Double" to kt(
      "42.0"
    ),
    "XR.Expression/XR.Const.Boolean" to kt(
      "true"
    ),
    "XR.Expression/XR.Const.Char" to kt(
      "a"
    ),
    "XR.Expression/XR.Const.Null" to kt(
      "null"
    ),
    "XR.Expression/XR.Property" to kt(
      "foo.bar"
    ),
    "XR.Expression/XR.Property Nested" to kt(
      "foo.bar.baz"
    ),
    "XR.Expression/XR.When 1-Branch" to kt(
      "if (foo) bar else baz"
    ),
    "XR.Expression/XR.When 1-Branch BinaryOp" to kt(
      "if (a == b) bar else baz"
    ),
    "XR.Expression/XR.When 2-Branch" to kt(
      "when { foo -> bar; baz -> qux; else ->- quux }"
    ),
    "XR.Expression/XR.FunctionN" to kt(
      "{ foo, bar -> baz }"
    ),
    "XR.Expression/XR.FunctionApply" to kt(
      "foo.apply(bar, baz)"
    ),
    "XR.Expression/XR.FunctionApply - FunctionN" to kt(
      "{ a, b -> c }.apply(bar, baz)"
    ),
    "XR.Expression/XR.FunctionApply - Scoped" to kt(
      "(a == b).apply(bar, baz)"
    ),
    "XR.Expression/XR.UnaryOp" to kt(
      "-foo"
    ),
    "XR.Expression/XR.BinaryOp" to kt(
      "a == b"
    ),
    "XR.Expression/XR.Block" to kt(
      "{ val foo = bar; val baz = qux; baz }"
    ),
    "XR.Expression/XR.Product" to kt(
      "MyClass(foo = bar, baz = qux)"
    ),
    "XR.Expression/XR.Free" to kt(
      """free("a, ${'$'}foo, b, ${'$'}bar, c, ${'$'}baz").asPure()"""
    ),
    "XR.Expression/XR.MethodCall" to kt(
      "foo.method_MC(bar, baz)"
    ),
    "XR.Expression/XR.GlobalCall" to kt(
      "method_GC(bar, baz)"
    ),
    "XR.Expression/XR.QueryToExpr" to kt(
      "Table(Person).toExpr"
    ),
    "XR.Expression/XR.TagForParam" to kt(
      """TagP("foo")"""
    ),
    "XR.Expression/XR.TagForSqlExpression" to kt(
      """TagE("foo")"""
    ),
    "XR.Query/XR.Map" to kt(
      "Table(Person).map { p -> p.name }"
    ),
    "XR.Query/XR.FlatMap" to kt(
      "Table(Person).flatMap { p -> Table(Person).map { p -> p.name } }"
    ),
    "XR.Query/XR.Filter" to kt(
      "Table(Person).filter { p -> p.age == 42 }"
    ),
    "XR.Query/XR.Entity" to kt(
      "Table(Person)"
    ),
    "XR.Query/XR.Union" to kt(
      "foo.union(bar)"
    ),
    "XR.Query/XR.UnionAll" to kt(
      "foo.unionAll(bar)"
    ),
    "XR.Query/XR.Distinct" to kt(
      "Table(Person).distinct"
    ),
    "XR.Query/XR.DistinctOn" to kt(
      "Table(Person).distinctOn { p -> p.name }"
    ),
    "XR.Query/XR.Drop" to kt(
      "Table(Person).drop(2)"
    ),
    "XR.Query/XR.Take" to kt(
      "Table(Person).take(2)"
    ),
    "XR.Query/XR.SortBy" to kt(
      "Table(Person).sortBy { p.name to Asc }"
    ),
    "XR.Query/XR.SortBy - Implicit" to kt(
      "Table(Person).sortBy { p.name }"
    ),
    "XR.Query/XR.FlatJoin" to kt(
      "Table(Person).join { p -> p.name == o.other }"
    ),
    "XR.Query/XR.FlatGroupBy" to kt(
      "groupBy(p.name)"
    ),
    "XR.Query/XR.FlatSortBy" to kt(
      "sortBy(p.name to Asc)"
    ),
    "XR.Query/XR.FlatFilter" to kt(
      "where(p.name == o.other)"
    ),
    "XR.Query/XR.ConcatMap" to kt(
      "Table(Person).concatMap { p -> p.name }"
    ),
    "XR.Query/XR.Nested" to kt(
      "Table(Person).nested"
    ),
    "XR.Query/XR.ExprToQuery" to kt(
      "foo.toQuery"
    ),
    "XR.Query/XR.Free" to kt(
      """free("a, ${'$'}foo, b, ${'$'}bar, c, ${'$'}baz").asPure()"""
    ),
    "XR.Query/XR.TagForSqlQuery" to kt(
      """TagQ("foo")"""
    ),
    "XR.Query/XR.CustomQueryRef - SelectValue" to kt(
      "select { val p = from(Table(Person)); val p = join(Table(Address)) { p.name == a.street }; where(p.age == 42); groupBy(p.name); sortBy(p.name to Asc); p.name }"
    ),
  )
}
