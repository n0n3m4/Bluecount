package com.bluecount

import android.app.Application

class App : Application() {
  val repo: Repo by lazy { Repo(this, Identity.signer) }
  val sync: SyncEngine by lazy { SyncEngine(this, repo) }

  override fun onCreate() {
    super.onCreate()
    instance = this
    // Re-announce after every local write (spec §7).
    repo.onAppend = { sync.kick() }
  }

  companion object {
    lateinit var instance: App
      private set
  }
}
