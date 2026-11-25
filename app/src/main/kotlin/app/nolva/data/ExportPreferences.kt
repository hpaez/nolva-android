package app.nolva.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Export choices remembered between documents. Main-safe. */
interface ExportPreferences {
    /** Whether PDFs get page numbers; off until the user turns it on. */
    suspend fun pageNumbers(): Boolean

    suspend fun setPageNumbers(enabled: Boolean)
}

/** [ExportPreferences] in a small private SharedPreferences file. */
class SharedExportPreferences(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ExportPreferences {

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun pageNumbers(): Boolean = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_PAGE_NUMBERS, false)
    }

    override suspend fun setPageNumbers(enabled: Boolean) = withContext(ioDispatcher) {
        prefs.edit().putBoolean(KEY_PAGE_NUMBERS, enabled).apply()
    }

    private companion object {
        const val FILE = "export"
        const val KEY_PAGE_NUMBERS = "pdf_page_numbers"
    }
}
