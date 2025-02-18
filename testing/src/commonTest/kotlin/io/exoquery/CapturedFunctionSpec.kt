package io.exoquery

import io.exoquery.annotation.CapturedFunction
import io.exoquery.xr.XR
import io.exoquery.xr.XRType
import io.exoquery.testdata.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain

/**
 * This is for CapturedFunction tests involving behavior and deep IR checks. The comprehensive tests are in CapturedFunctionReq.kt.
 */
class CapturedFunctionSpec : FreeSpec({
  "static function capture - structural tests" - {
    "proto function-capture i.e. call without capture" {
      @CapturedFunction
      fun joes(people: SqlQuery<Person>) = capture { people.filter { p -> p.name == "Joe" } }
      val people = capture { Table<Person>() }
      shouldThrow<MissingCaptureError> { joes(people) }
    }
  }
})
