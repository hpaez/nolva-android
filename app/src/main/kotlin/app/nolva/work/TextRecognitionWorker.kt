package app.nolva.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nolva.NolvaApplication
import kotlinx.coroutines.CancellationException

/**
 * Recognizes the text of pending pages in the background. It survives the
 * app being closed; WorkManager resumes it later if the system stops it.
 */
class TextRecognitionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NolvaApplication).container
        return try {
            container.textRecognitionRunner.run(shouldStop = { isStopped })
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log page text or paths: only the failure type.
            Log.w(TAG, "Text recognition failed: ${e.javaClass.simpleName}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "Nolva"
        private const val UNIQUE_NAME = "text-recognition"

        /** After this many failed attempts, wait for the next [schedule] call. */
        private const val MAX_ATTEMPTS = 3

        /**
         * Makes sure pending pages get recognized. Cheap to call often: a run
         * already going picks up new pages, and an extra run queued behind it
         * finds nothing to do and ends at once.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<TextRecognitionWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
