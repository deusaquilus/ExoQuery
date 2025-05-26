package io.exoquery

import io.exoquery.annotation.WindowFun
import io.exoquery.annotation.Dsl

interface WindowDsl {

  fun orderBy(order: Any?): WindowDsl
  fun orderByDescending(order: Any?): WindowDsl
  fun orderBy(vararg orders: Pair<Any?, Ord?>): WindowDsl

  fun partitionBy(vararg statement: Any?): WindowDsl

  /**
   * If you want to use a custom frame specification, you can use this function. This use useful
   * when used with the `free` construct. For example:
   * ```
     * Table<Person>().map { p -> Pair(p.name, over().partitionBy(p.city).frame(free("CUME_DIST()")<Double>())) }
   * ```
   *
   * Note, in general the design principle of ExoQuery when it comes to chosing an argument versus
   * a () -> T is that if the logic being used is NOT predication (e.g. like the where clause DSL) then
   * use an argument form. If the logic is predication then use a () -> T form.
   */
  @Dsl @WindowFun("CUSTOM") fun <T> frame(statement: T): T

  @Dsl @WindowFun("RANK") fun rank(): Int
  @Dsl @WindowFun("DENSE_RANK") fun rankDense(): Int
  @Dsl @WindowFun("ROW_NUMBER") fun rowNumber(): Int

  @Dsl @WindowFun("SUM") fun <T> sum(expression: T): T
  @Dsl @WindowFun("AVG") fun avg(expression: Any?): Double
  @Dsl @WindowFun("MIN") fun <T> min(expression: T): T
  @Dsl @WindowFun("MAX") fun <T> max(expression: T): T

  @Dsl
  @WindowFun("COUNT")
  fun count(expression: Any?): Int

  @Dsl
  @WindowFun("COUNT_STAR")
  fun count(): Int

  @Dsl @WindowFun("LAG")
  fun <T> lag(expression: T): T?
  @Dsl @WindowFun("LEAD")
  fun <T> lead(expression: T): T?

  @Dsl @WindowFun("FIRST_VALUE")
  fun <T> firstValue(expression: T): T
  @Dsl @WindowFun("FIRST_VALUE")
  fun <T> lastValue(expression: T): T
  @Dsl @WindowFun("NTH_VALUE")
  fun <T> nthValue(expression: T, n: Int): T

  @Dsl @WindowFun("NTILE")
  fun ntile(expression: Any?): Double


  /** Synonym for orderBy */
  fun sortBy(order: Any?): WindowDsl
  /** Synonym for orderByDescending */
  fun sortByDescending(order: Any?): WindowDsl
  /** Synonym for orderBy */
  fun sortBy(vararg orders: Pair<Any?, Ord?>): WindowDsl
}
