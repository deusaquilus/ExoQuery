package io.exoquery.android

import io.exoquery.testdata.BasicSchemaTerpal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import io.exoquery.controller.android.AndroidDatabaseController
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowLog

object EmptyCallback : SupportSQLiteOpenHelper.Callback(1) {
  override fun onCreate(db: SupportSQLiteDatabase) {}
  override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object TestDatabase {
  val databaseName = "terpal_test.db"
  val ctx by lazy {
    // NOTE any stdio output here seems to be swallowed by Robolectric and/or the CI test runner
    ShadowLog.stream = System.out
    System.setProperty("robolectric.logging", "stdout")
    runBlocking {
      AndroidDatabaseController.fromApplicationContext(databaseName, ApplicationProvider.getApplicationContext(), BasicSchemaTerpal)
    }
  }
}
