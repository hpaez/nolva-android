package app.nolva

import android.app.Application
import android.util.Log
import app.nolva.di.AppContainer
import app.nolva.work.TextRecognitionWorker
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NolvaApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /** Work that outlives any screen; lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Pages left pending by a previous run, or added before OCR existed.
        TextRecognitionWorker.schedule(this)
        appScope.launch {
            // Let the first screen load before walking the storage.
            delay(MAINTENANCE_DELAY)
            val result = container.storageMaintenance.run()
            if (result.filesRemoved > 0) {
                Log.i(TAG, "Storage cleanup: ${result.filesRemoved} files, ${result.bytesFreed} bytes")
            }
        }
    }

    private companion object {
        const val TAG = "Nolva"
        val MAINTENANCE_DELAY = 10.seconds
    }
}
