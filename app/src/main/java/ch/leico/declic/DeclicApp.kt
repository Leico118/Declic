package ch.leico.declic

import android.app.Application

class DeclicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
