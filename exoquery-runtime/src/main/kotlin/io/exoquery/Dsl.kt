package io.exoquery

import io.exoquery.annotation.Captured
import io.exoquery.xr.XR
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.protobuf.ProtoBuf

fun unpackExpr(expr: String): XR.Expression =
  ProtoBuf.decodeFromHexString<XR.Expression>(expr)

// TODO make this have a context-reciver that allows `use` to be used, otherwise don't allow it
fun <T> capture(block: () -> T): @Captured("initial-value") SqlExpression<T> = error("Compile time plugin did not transform the tree")
