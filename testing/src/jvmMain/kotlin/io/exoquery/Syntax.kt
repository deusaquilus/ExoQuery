package io.exoquery

fun main() {

  val str = "Stuff (%total) %query"

  println(str.replace("%total", "123", true).replace("%query", "SELECT * FROM BLAH", true))

}