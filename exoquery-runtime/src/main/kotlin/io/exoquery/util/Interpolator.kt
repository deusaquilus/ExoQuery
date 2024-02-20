package io.exoquery.util

import io.exoquery.fansi.Attrs
import io.exoquery.pprint.PPrinterConfig
import io.exoquery.printing.PrintXR
import io.exoquery.terpal.Interpolator

class Tracer(
  val traceType: TraceType,
  val traceConfig: TraceConfig,
  val defaultIndent: Int = 0,
  val color: Boolean = Globals.traceColors,
  val globalTracesEnabled: (TraceType) -> Boolean = { Globals.tracesEnabled(it) }
): Interpolator<Any, Traceable> {
  override fun interpolate(parts: () -> List<String>, params: () -> List<Any>): Traceable =
    Traceable(parts, params, traceType, color, defaultIndent, traceConfig, globalTracesEnabled)
}

class Traceable(
  val parts: () -> List<String>,
  val params: () -> List<Any>,
  val traceType: TraceType,
  val color: Boolean,
  val defaultIndent: Int,
  val traceConfig: TraceConfig,
  val globalTracesEnabled: (TraceType) -> Boolean
): IndentOps {

  fun tracesEnabled(): Boolean =
    traceConfig.enabledTraces.contains(traceType) || globalTracesEnabled(traceType)

  // Hiearchy representing the type of element to print once it is classified as a splice
  private sealed interface Printee {
    data class Str(val value: String, val first: Boolean):Printee
    data class Elem(val value: String):Printee
    data class Simple(val value: String):Printee
    object Separator:Printee
  }

  private val elementPrefix = "|  "

  val qprint =
    if (color)
      PrintXR(PPrinterConfig(defaultShowFieldNames = false))
    else
      PrintXR(PPrinterConfig(defaultShowFieldNames = false, colorLiteral = Attrs.Empty, colorApplyPrefix = Attrs.Empty))

  fun generateStringForCommand(value: Any, indent: Int): String {
    val objectString = qprint(value).toString()
    val oneLine      = objectString.toString().fitsOnOneLine
    return when(oneLine) {
      true -> "${indent.prefix}> ${objectString}"
      false -> "${indent.prefix}>\n${objectString.multiline(indent, elementPrefix)}"
    }
  }

  private fun readFirst(first: String) =
    Regex("%([0-9]+)(.*)").find(first)?.let {
      it.groupValues[2].trim() to it.groupValues[1].toInt()
    } ?: (first to null)

  sealed interface Splice {
    val value: String
    data class Simple(override val value: String): Splice // Simple splice into the string, don't indent etc...
    data class Show(override val value: String): Splice // Indent, colorize the element etc...
  }

  private fun readBuffers(parts: List<String>, params: List<Any>) = run {
    fun orZero(i: Int): Int = if (i < 0) 0 else i

    val elements = params.map {
      when (it) {
        is String -> Splice.Simple(it)
        else -> Splice.Show(qprint(it).toString())
      }
    }

    val (firstStr, explicitIndent) = readFirst(parts.first())
    val indent =
      explicitIndent ?: run {
        // A trick to make nested calls of andReturn indent further out which makes andReturn MUCH more usable.
        // Just count the number of times it has occurred on the thread stack.
        val returnInvocationCount = Thread
          .currentThread()
          .getStackTrace()
          .toList()
          .count { e -> e.getMethodName() == "andReturn" }
        defaultIndent + orZero(returnInvocationCount - 1) * 2
      }

    val partsIter = parts.iterator()
    partsIter.next() // already took care of the 1st element
    val elementsIter = elements.iterator()

    val sb = mutableListOf<Printee>()
    sb.add(Printee.Str(firstStr.trim(), true))

    while (elementsIter.hasNext()) {
      val nextElem = elementsIter.next()
      with(nextElem) {
        when(this) {
          is Splice.Simple -> {
            sb.add(Printee.Simple(value))
            val nextPart = partsIter.next().trim()
            sb.add(Printee.Simple(nextPart))
          }
          is Splice.Show -> {
            sb.add(Printee.Separator)
            sb.add(Printee.Elem(value))
            val nextPart = partsIter.next().trim()
            sb.add(Printee.Separator)
            sb.add(Printee.Str(nextPart, false))
          }
        }
      }
    }

    sb.toList() to indent
  }

  // Evalauate the parts/params and generate a string
  fun generateString() = renderString(parts(), params())

  internal fun renderString(parts: List<String>, params: List<Any>) = run {
    val (elementsRaw, indent) = readBuffers(parts, params)

    // I.e. the non-whitespace elements
    val elements = elementsRaw.filter {
      when (it) {
        is Printee.Str -> it.value.trim() != ""
        is Printee.Elem -> it.value.trim() != ""
        else -> true
      }
    }

    val oneLine = elementsRaw.all {
      when (it) {
        is Printee.Str -> it.value.fitsOnOneLine
        is Printee.Elem -> it.value.fitsOnOneLine
        else -> true
      }
    }

    val output =
      elements.map {
        with(it) {
          when {
            this is Printee.Simple -> value
            // it is the first thing on the row it needs to be indented since its the only thing in the line
            this is Printee.Str && first == true && oneLine -> indent.prefix + value
            this is Printee.Str && first == false && oneLine -> value
            this is Printee.Elem && oneLine -> value
            this is Printee.Separator && oneLine -> " "
            // Cases where it's not one line
            this is Printee.Str && first == true && !oneLine -> value.multiline(indent, "")
            this is Printee.Str && first == false && !oneLine -> value.multiline(indent, "|")
            this is Printee.Elem -> value.multiline(indent, "|  ")
            this is Printee.Separator -> "\n"
            // This case should not happen, everything should now be covered
            else -> it.toString()
          }
        }
      }

    output.joinToString("") to indent
  }

  fun logIfEnabled() =
    if (tracesEnabled()) renderString(parts(), params()) else null

  fun andLog(): Unit {
    logIfEnabled()?.let { println(it.first) }
  }

  infix inline fun <T> andContinue(command: () -> T): T {
    logIfEnabled()?.let { println(it.first) }
    return command()
  }

  infix inline fun <T> andReturn(command: () -> T): T =
    logIfEnabled()?.let { (output, indent) ->
      // do the initial log
      println(output)
      // evaluate the command, this will activate any traces that were inside of it
      val result = command()
      println(generateStringForCommand(result as Any, indent))
      result
    } ?: command()

  fun <T> andReturnIf(command: () -> T): (((T) -> Boolean) -> T) = { showIf ->
    logIfEnabled()?.let { (output, indent) ->

      // Even though we usually want to evaluate the command after the initial log was done
      // (so that future logs are nested under this one after the intro text but not
      // before the return) but we can't do that in this case because the switch indicating
      // whether to output anything or not is dependant on the return value.
      val result = command()

      if (showIf(result))
        println(output)

      if (showIf(result))
        println(generateStringForCommand(result as Any, indent))

      result
    } ?: command()
  }


}