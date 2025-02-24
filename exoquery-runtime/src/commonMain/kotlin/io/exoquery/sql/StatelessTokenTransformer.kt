package io.exoquery.sql

interface StatelessTokenTransformer {
  fun invoke(token: Token): Token =
    when (token) {
      is ParamMultiToken -> invoke(token)
      is ParamMultiTokenRealized -> invoke(token)
      is ParamSingleToken -> invoke(token)
      is ParamSingleTokenRealized -> invoke(token)
      is SetContainsToken -> invoke(token)
      is Statement -> invoke(token)
      is StringToken -> invoke(token)
    }

  fun invoke(token: StringToken): Token = token
  fun invoke(token: ParamMultiToken): Token = token
  fun invoke(token: ParamMultiTokenRealized): Token = token
  fun invoke(token: ParamSingleToken): Token = token
  fun invoke(token: ParamSingleTokenRealized): Token = token

  fun invoke(token: SetContainsToken): Token =
    SetContainsToken(
      invoke(token.a),
      invoke(token.op),
      invoke(token.b)
    )

  fun invoke(token: Statement): Token =
    Statement(token.tokens.map { invoke(it) })
}
