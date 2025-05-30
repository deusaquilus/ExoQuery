package io.exoquery

import io.exoquery.sql.PostgresDialect
import io.exoquery.testdata.Person

class ParamReq: GoldenSpecDynamic(ParamReqGoldenDynamic, Mode.ExoGoldenTest(), {
  "single single-param" - {
    val n = "Leah"
    val cap = capture { Table<Person>().filter { p -> p.name == param(n) } }
    val build = cap.build<PostgresDialect>()
    "compileTime" {
      shouldBeGolden(build.value, "Original SQL")
      shouldBeGolden(build.determinizeDynamics(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    val buildRuntime = cap.buildRuntime(PostgresDialect(), null)
    "runtime" {
      shouldBeGolden(buildRuntime.token.build(), "Original SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().params.toString(), "Params")
    }
  }
  "multi single-param" - {
    val n = "Leib"
    val a = 42
    val cap = capture { Table<Person>().filter { p -> p.name == param(n) && p.age == param(a) } }
    val build = cap.build<PostgresDialect>()
    "compileTime" {
      shouldBeGolden(build.value, "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    val buildRuntime = cap.buildRuntime(PostgresDialect(), null)
    "runtime" {
      shouldBeGolden(buildRuntime.token.build(), "Original SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().params.toString(), "Params")
    }
  }

  "single multi-param" - {
    val names = listOf("Leah", "Leib")
    val cap = capture { Table<Person>().filter { p -> p.name in params(names) } }
    val build = cap.build<PostgresDialect>()
    "compileTime" {
      shouldBeGolden(build.value, "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    val buildRuntime = cap.buildRuntime(PostgresDialect(), null)
    "runtime" {
      shouldBeGolden(buildRuntime.token.build(), "Original SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().params.toString(), "Params")
    }
  }

  "multi multi-param" - {
    val names = listOf("Leah", "Leib")
    val ages = listOf(42, 43)
    val cap = capture { Table<Person>().filter { p -> p.name in params(names) && p.age in params(ages) } }
    val build = cap.build<PostgresDialect>()
    "compileTime" {
      shouldBeGolden(build.value, "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    val buildRuntime = cap.buildRuntime(PostgresDialect(), null)
    "runtime" {
      shouldBeGolden(buildRuntime.token.build(), "Original SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().params.toString(), "Params")
    }
  }

  "one single, one multi" - {
    val n = "Joe"
    val ages = listOf(42, 43)
    val cap = capture { Table<Person>().filter { p -> p.name == param(n) && p.age in params(ages) } }
    val build = cap.build<PostgresDialect>()
    "compileTime" {
      shouldBeGolden(build.value, "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    val buildRuntime = cap.buildRuntime(PostgresDialect(), null)
    "runtime" {
      shouldBeGolden(buildRuntime.token.build(), "Original SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(buildRuntime.determinizeDynamics().params.toString(), "Params")
    }
  }

  "datatypes" - {
    "LocalDate comparison" {
      data class Client(val name: String, val birthDate: kotlinx.datetime.LocalDate)
      val test = kotlinx.datetime.LocalDate(2000, 1, 1)
      val q = capture.select {
        val c = from(Table<Client>())
        where { c.birthDate > param(test) || c.birthDate >= param(test) || c.birthDate < param(test) || c.birthDate <= param(test) }
        c.name
      }
      val build = q.buildFor.Postgres()
      shouldBeGolden(build.token.build(), "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    "LocalTime comparison" {
      data class Client(val name: String, val birthTime: kotlinx.datetime.LocalTime)
      val test = kotlinx.datetime.LocalTime(12, 0, 0)
      val q = capture.select {
        val c = from(Table<Client>())
        where { c.birthTime > param(test) || c.birthTime >= param(test) || c.birthTime < param(test) || c.birthTime <= param(test) }
        c.name
      }
      val build = q.buildFor.Postgres()
      shouldBeGolden(build.token.build(), "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    "LocalDateTime comparison" {
      data class Client(val name: String, val birthDateTime: kotlinx.datetime.LocalDateTime)
      val test = kotlinx.datetime.LocalDateTime(2000, 1, 1, 12, 0, 0)
      val q = capture.select {
        val c = from(Table<Client>())
        where { c.birthDateTime > param(test) || c.birthDateTime >= param(test) || c.birthDateTime < param(test) || c.birthDateTime <= param(test) }
        c.name
      }
      val build = q.buildFor.Postgres()
      shouldBeGolden(build.token.build(), "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
    "Instant comparison" {
      data class Client(val name: String, val birthInstant: kotlinx.datetime.Instant)
      val test = kotlinx.datetime.Instant.fromEpochSeconds(946684800) // 2000-01-01T00:00:00Z
      val q = capture.select {
        val c = from(Table<Client>())
        where { c.birthInstant > param(test) || c.birthInstant >= param(test) || c.birthInstant < param(test) || c.birthInstant <= param(test) }
        c.name
      }
      val build = q.buildFor.Postgres()
      shouldBeGolden(build.token.build(), "Original SQL")
      shouldBeGolden(build.determinizeDynamics().token.build(), "Determinized SQL")
      shouldBeGolden(build.determinizeDynamics().params.toString(), "Params")
    }
  }
})
