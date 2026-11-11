package com.tvdeck.app.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader

/**
 * Reads big provider lists (thousands of channels, tens of thousands of movies) straight from the
 * downloaded file, keeping only the fields the app uses. Much faster and lighter than loading the
 * whole response into a string and building a JSON object for every item.
 */
object JsonStream {

    /**
     * Calls [onItem] for each object in a top-level array, or for each value of a top-level object
     * (some servers send `{"1": {...}, "2": {...}}`). Only [fields] are read, as trimmed strings with
     * null and "null" turned into "". The map passed to [onItem] is reused, so copy what you keep.
     * Anything that isn't a JSON list (an HTML error page, say) yields no items.
     */
    fun forEachObject(file: File, fields: Set<String>, onItem: (Map<String, String>) -> Unit) {
        Http.openStream(file).use { input ->
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            r.isLenient = true
            val item = HashMap<String, String>(fields.size * 2)
            fun readItem() {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    return
                }
                item.clear()
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name in fields) item[name] = scalar(r) else r.skipValue()
                }
                r.endObject()
                onItem(item)
            }
            when (runCatching { r.peek() }.getOrNull()) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) readItem()
                    r.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        readItem()
                    }
                    r.endObject()
                }
                else -> Unit
            }
        }
    }

    /** Xtream servers mix numbers, strings, booleans and nulls for the same field. */
    private fun scalar(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString().trim().let { if (it == "null") "" else it }
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> {
            r.nextNull()
            ""
        }
        else -> {
            r.skipValue()
            ""
        }
    }
}
