package io.exoquery.plugin.trees

import io.exoquery.plugin.classIdOf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

// TODO need to introduce CallType to know what is pure and what is not. Also need to ahve equivalent data-structure for global calls
object MethodWhitelist {
  data class HostMethods(val host: ClassId, val allowedMethods: List<String>)

  private inline fun <reified T> classIdOrFail(label: String) = classIdOf<T>() ?: throw IllegalArgumentException("Cannot create MethodWhitelist. Cannot get classId of: ${label}")

  private val data: List<HostMethods> =
    listOf(
      HostMethods(classIdOrFail<String>("String"), listOf("substring", "uppercase", "lowercase", "length"))
    )

  private val dataMap = data.map { it.host to it.allowedMethods }.toMap()

  private val allHosts = data.map { it.host }

  fun allowedHost(host: ClassId) = data.find { it.host == host } != null
  fun allowedMethodForHost(host: ClassId?, method: String?): Boolean =
    if (host == null || method == null)
      false
    else
      dataMap[host]?.let { it.contains(method) } ?: false
}
