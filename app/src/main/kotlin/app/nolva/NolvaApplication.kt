package app.nolva

import android.app.Application
import app.nolva.di.AppContainer

class NolvaApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
