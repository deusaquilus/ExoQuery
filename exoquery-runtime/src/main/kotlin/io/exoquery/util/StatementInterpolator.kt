package io.exoquery.util

import io.exoquery.sql.Statement
import io.exoquery.sql.StringToken
import io.exoquery.sql.Token
import io.exoquery.sql.token
import io.exoquery.terpal.Interpolator

object stmt: Interpolator<Token, Statement> {
  override fun interpolate(parts: () -> List<String>, params: () -> List<Token>): Statement {
    //checkLengths(args, sc.parts)
    val partsIterator = parts().iterator()
    val argsIterator  = params().iterator()
    val bldr          = mutableListOf<Token>()
    bldr += StringToken(partsIterator.next())
    while (argsIterator.hasNext()) {
      bldr += argsIterator.next()
      bldr += StringToken(partsIterator.next())
    }
    val tokens = flatten(bldr)
    return Statement(tokens)
  }

  val List<Token>.head get() = this.first()
  val List<Token>.tail get() =
    if (this.isEmpty()) throw IllegalStateException("Cannot get the tail of an empty List")
    else this.subList(1, this.size)

  fun MutableList<Token>.withMore(token: Token): MutableList<Token> {
    add(token)
    return this
  }

  private fun flatten(tokens: List<Token>): List<Token> {
    fun unnestStatements(tokens: List<Token>): List<Token> {


      fun loop(acc: MutableList<Token>, rest: List<Token>): List<Token> = rest.run {
        when {
          none() -> acc
          head is Statement -> loop(acc, (head as Statement).tokens + tail)
          else              -> loop(acc.withMore(head), tail)
        }
      }

      return loop(mutableListOf(), tokens)
    }

    fun mergeStringTokens(tokens: List<Token>): List<Token> {
      val (resultBuilder, leftTokens) =
        tokens.fold((mutableListOf<Token>() to mutableListOf<String>())) { (builder, acc), token ->
          when {
            token is StringToken -> {
              val str = token.string
              if (str.isNotEmpty()) {
                acc += token.string
              }
              (builder to acc)
            }
            acc.isEmpty() -> {
              builder += token.token
              (builder to mutableListOf())
            }
            else -> {
              builder += StringToken(acc.mkString())
              builder += token.token
              (builder to mutableListOf())
            }
          }
        }
      if (leftTokens.isNotEmpty()) {
        resultBuilder += StringToken(leftTokens.mkString())
      }
      return resultBuilder
    }

    val output =
      ::unnestStatements
        .andThen(::mergeStringTokens)
        .invoke(tokens)

    return output
  }

}

inline fun <A, R, R1> ((A) -> R).andThen(crossinline f: (R) -> R1): (A) -> R1 =
  { a: A -> f(this.invoke(a)) }
