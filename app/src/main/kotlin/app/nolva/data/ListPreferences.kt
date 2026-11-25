package app.nolva.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** How documents are ordered in the list. Each uses its natural direction. */
enum class DocumentSort {
    /** Most recently edited first. */
    Updated,

    /** Most recently created first. */
    Created,

    /** A to Z, ignoring case and accents. */
    Name,

    /** Largest first. */
    Size,

    /** Most pages first. */
    Pages,
}

enum class ListLayout { List, Grid }

data class ListSettings(
    val sort: DocumentSort = DocumentSort.Updated,
    val layout: ListLayout = ListLayout.List,
)

/** The user's choice of order and layout for the document list. Main-safe. */
interface ListPreferences {
    suspend fun load(): ListSettings

    suspend fun save(settings: ListSettings)
}

/** [ListPreferences] in a small private SharedPreferences file. */
class SharedListPreferences(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ListPreferences {

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun load(): ListSettings = withContext(ioDispatcher) {
        ListSettings(
            sort = enumOrDefault(prefs.getString(KEY_SORT, null), DocumentSort.Updated),
            layout = enumOrDefault(prefs.getString(KEY_LAYOUT, null), ListLayout.List),
        )
    }

    override suspend fun save(settings: ListSettings) = withContext(ioDispatcher) {
        prefs.edit()
            .putString(KEY_SORT, settings.sort.name)
            .putString(KEY_LAYOUT, settings.layout.name)
            .apply()
    }

    private companion object {
        const val FILE = "document_list"
        const val KEY_SORT = "sort"
        const val KEY_LAYOUT = "layout"
    }
}

/** The enum value named [name], or [default] (also for values a newer version wrote). */
internal inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
