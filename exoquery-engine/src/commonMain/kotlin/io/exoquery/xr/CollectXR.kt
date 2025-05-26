package io.exoquery.xr

class CollectXR<T>(private val collect: (XR) -> T?) : StatefulTransformerSingleRoot<MutableList<T>> {

  override val state = mutableListOf<T>()

  override fun <X : XR> root(xr: X): Pair<X, StatefulTransformerSingleRoot<MutableList<T>>> {
    val found = collect(xr)
    if (found != null) {
      state.add(found)
    }
    return Pair(xr, this)
  }

  companion object {
    inline fun <reified T> byType(xr: XR): List<T> where T : XR =
      CollectXR<T> {
        when {
          // looks like we need the `as T?` here which looks like a bug
          it is T -> it as T?
          else -> null as T?
        }
      }.root(xr).second.state

    operator fun <T> invoke(xr: XR, collect: (XR) -> T?): List<T> where T : XR =
      CollectXR<T>(collect).invoke(xr).second.state
  }
}


class ContainsXR(private val predicate: (XR) -> Boolean) : StatelessChecker {
  override fun check(xr: XR): Boolean = predicate(xr)

  companion object {
    operator fun invoke(xr: XR, collect: (XR) -> Boolean): Boolean =
      ContainsXR(collect).invoke(xr)
  }
}

open class TransformXR(
  // MAKE SURE that the default value of these lambdas is always { null } since only that will make the
  // transfoemrs delegate to the super to transformer their branches. Remember that the null-check is
  // the equivalent of a partial-function-miss here since Kotlin has no partial functions. It tells
  // the super-transformer to continue down the tree.
  val transformExpression: (XR.Expression) -> XR.Expression? = { null },
  val transformQuery: (XR.Query) -> XR.Query? = { null },
  val transformAction: (XR.Action) -> XR.Action? = { null },
  val transformBranch: (XR.Branch) -> XR.Branch? = { null },
  val transformVariable: (XR.Variable) -> XR.Variable? = { null },
) : StatelessTransformer {

  // For each transform, if the transform-function actually "captures" the given XR use the result of that,
  // otherwise go to the super-class to recurse down into respective nodes
  override fun invoke(xr: XR.Expression): XR.Expression = transformExpression(xr) ?: super.invoke(xr)
  override fun invoke(xr: XR.Query): XR.Query = transformQuery(xr) ?: super.invoke(xr)
  override fun invoke(xr: XR.Branch): XR.Branch = transformBranch(xr) ?: super.invoke(xr)
  override fun invoke(xr: XR.Action): XR.Action = transformAction(xr) ?: super.invoke(xr)

  companion object {
    fun Query(xr: XR.Query, transform: (XR.Query) -> XR.Query?): XR.Query =
      TransformXR(transformQuery = transform).invoke(xr)

    fun Expression(xr: XR.Expression, transform: (XR.Expression) -> XR.Expression?): XR.Expression =
      TransformXR(transformExpression = transform).invoke(xr)

    fun Branch(xr: XR.Branch, transform: (XR.Branch) -> XR.Branch?): XR.Branch =
      TransformXR(transformBranch = transform).invoke(xr)

    fun Variable(xr: XR.Variable, transform: (XR.Variable) -> XR.Variable?): XR.Variable =
      TransformXR(transformVariable = transform).invoke(xr)

    fun Action(xr: XR.Action, transform: (XR.Action) -> XR.Action?): XR.Action =
      TransformXR(transformAction = transform).invoke(xr)

    fun build() = TransformerBuilder()
  }
}

class TransformerBuilder {
  @PublishedApi
  internal var transformExpression: ((XR.Expression) -> XR.Expression?)? = null
  @PublishedApi
  internal var transformQuery: ((XR.Query) -> XR.Query?)? = null
  @PublishedApi
  internal var transformAction: ((XR.Action) -> XR.Action?)? = null

  fun withQuery(transform: (XR.Query) -> XR.Query?) = run {
    transformQuery = transform
    this
  }

  fun withExpression(transform: (XR.Expression) -> XR.Expression?) = run {
    transformExpression = transform
    this
  }

  fun withAction(transform: (XR.Action) -> XR.Action?) = run {
    transformAction = transform
    this
  }

  internal inline fun <reified XRQ : XR.Query> withQueryOf(crossinline transform: (XRQ) -> XR.Query?) = run {
    transformQuery = { if (it is XRQ) transform(it) else null /* note if this returns a non-null value the transform recursion will stop so need to return null until an actual match is made */ }
    this
  }

  internal inline fun <reified XRE : XR.Expression> withExpressionOf(crossinline transform: (XRE) -> XR.Expression?) = run {
    transformExpression = { if (it is XRE) transform(it) else null /* note if this returns a non-null value the transform recursion will stop so need to return null until an actual match is made */ }
    this
  }

  internal inline fun <reified XRA : XR.Action> withActionOf(crossinline transform: (XRA) -> XR.Action?) = run {
    transformAction = { if (it is XRA) transform(it) else null /* note if this returns a non-null value the transform recursion will stop so need to return null until an actual match is made */ }
    this
  }

  // MAKE SURE that the default value of these lambdas is always { null } since only that will make the
  // transfoemrs delegate to the super to transformer their branches. Remember that the null-check is
  // the equivalent of a partial-function-miss here since Kotlin has no partial functions. It tells
  // the super-transformer to continue down the tree.
  operator fun invoke(xr: XR) =
    TransformXR(transformExpression ?: { null }, transformQuery ?: { null }, transformAction ?: { null }).invoke(xr)

  operator fun invoke(xr: XR.U.QueryOrExpression): XR.U.QueryOrExpression =
    TransformXR(transformExpression ?: { null }, transformQuery ?: { null }, transformAction ?: { null }).invoke(xr)

  operator fun invoke(xr: XR.Action): XR.Action =
    TransformXR(transformExpression ?: { null }, transformQuery ?: { null }, transformAction ?: { null }).invoke(xr)
}
