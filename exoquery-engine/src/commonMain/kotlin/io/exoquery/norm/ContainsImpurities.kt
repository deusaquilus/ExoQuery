package io.exoquery.norm

import io.exoquery.xr.StatelessChecker
import io.exoquery.xr.XR
import io.exoquery.xr.XR.Expression
import io.exoquery.xr.XR.Free

private object ContainsImpurities: StatelessChecker {
  override fun check(xr: XR): Boolean = when (xr) {
    is XR.U.Call -> !xr.isPure()
    is XR.Window -> true
    is Free -> xr.pure == false
    else -> false
  }
}

fun XR.Query.hasImpurities() = ContainsImpurities(this)
fun Expression.hasImpurities() = ContainsImpurities(this)
