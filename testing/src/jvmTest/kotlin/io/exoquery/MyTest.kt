//package io.exoquery
//
//import io.exoquery.printing.PrintXR
//import io.exoquery.printing.qprint
//import kotlin.reflect.typeOf
//
//data class Foo(val bar: String)
//
////fun case0() {
////  val cap0 = capture { 123 + param(456) }
////  println(cap0.params)
////
////  val cap1 = cap0
////  val cap = capture { 789 + cap1.use }
////  println(qprint(cap.xr))
////  println(cap.params)
////
////  /*
////  java.lang.IllegalStateException: ------- Calling replant -------
////CALL 'public final fun <get-params> (): io.exoquery.Params declared in io.exoquery.SqlExpression' type=io.exoquery.Params origin=GET_PROPERTY
////  $this: GET_VAR 'val cap0: @[Captured(value = "initial-value")] io.exoquery.SqlExpression<kotlin.Int> [val] declared in io.exoquery.case0' type=@[Captured(value = "initial-value")] io.exoquery.SqlExpression<kotlin.Int> origin=null
////
////  ================= IR: ========================
////RETURN type=kotlin.Nothing from='local final fun <anonymous> (): kotlin.String declared in io.exoquery.case0'
////  CALL 'public final fun <get-bar> (): kotlin.String declared in io.exoquery.Foo' type=kotlin.String origin=GET_PROPERTY
////    $this: GET_VAR 'val f: io.exoquery.Foo [val] declared in io.exoquery.case0' type=io.exoquery.Foo origin=null
////
////   */
////  //val f = Foo("hello")
////  //printSource { f.bar }
////}
//
////fun case1() {
////  fun cap0() = capture { 123 + param(456) }
////  val cap = capture { 789 + cap0().use }
////  println(qprint(cap.xr))
////  println(cap.params)
////}
//
////fun case2() {
////  fun cap0(input: Int) = capture { 123 + param(input) }
////  val cap = capture { 789 + cap0(456).use }
////  println(qprint(cap.xr))
////  println(cap.params)
////}
//
////fun case1_dynamic() {
////  val x = true
////  fun cap0() =
////    if (x)
////      capture { 123 }
////    else
////      capture { 456 }
////
////  val cap = capture { 789 + cap0().use }
////  println(cap.show())
////}
//
//fun case2_dynamic() {
//  val x = true
//  fun cap0(input: Int) =
//    if (x)
//      capture { 123 + param(input) }
//    else
//      capture { 456 + param(input) }
//
//  val cap = capture { 789 + cap0(456).use }
//  println(cap.show())
//}
//
////fun case4() {
////  //val cap = capture { 123 + capture { 456 }.use }
////  //println(qprint(cap.xr))
////
////  class Foo {
////    val cap0 = capture { 456 + param(456) }
////  }
////  val f = Foo()
////  val cap = capture { 789 + f.cap0.use }
////  //println(cap.show())
////  println(cap.show())
////}
//
////fun case5_dynamic() {
////  //val cap = capture { 123 + capture { 456 }.use }
////  //println(qprint(cap.xr))
////  val v = true
////
////  class Foo {
////    val cap0 =
////      if (v) capture { 456 + param(456) } else capture { 456 + param(999) }
////  }
////  val f = Foo()
////  val cap = capture { 789 + f.cap0.use }
////  //println(cap.show())
////  println(cap.show())
////}
//
////fun case5() {
////  //val cap = capture { 123 + capture { 456 }.use }
////  //println(qprint(cap.xr))
////
////  class Foo {
////    fun cap0() = capture { 456 }
////  }
////  val f = Foo()
////  val cap = capture { 123 + f.cap0().use }
////  println(qprint(cap.xr))
////}
//
//fun main() {
//  case2_dynamic()
//  //case5()
//  //case2()
//  //case1()
//}
//
////inline fun <reified T> readAnnotations(value: T) =
////  typeOf<T>().annotations
