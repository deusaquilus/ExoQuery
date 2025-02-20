package io.exoquery.annotation

import io.exoquery.serial.ParamSerializer
import io.exoquery.util.TraceType
import kotlin.reflect.KClass

/**
 * This annotation means that the construct e.g. the SqlQuery represents a value captured during compile-time by the
 * ExoQuery system (via the parser and transformers). It cannot be specified by the user.
 */


@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CapturedFunction

@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.BINARY)
annotation class CapturedDynamic

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class Dsl

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class ExoGoldenTest

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class TracesEnabled(vararg val traceType: KClass<out TraceType>)

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class ExoGoldenOverride

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ExoExtras

sealed interface DslFunctionCallType {
  object PureFunction : DslFunctionCallType
  object ImpureFunction : DslFunctionCallType
  object Aggregator : DslFunctionCallType
  object QueryAggregator : DslFunctionCallType
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class DslFunctionCall(val type: KClass<out DslFunctionCallType>)

@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class DslNestingIgnore

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ParamStatic(val type: KClass<out ParamSerializer<out Any>>)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ParamCtx

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ParamPrimitive

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ParamCustom

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ParamCustomValue

@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class ExoValue
