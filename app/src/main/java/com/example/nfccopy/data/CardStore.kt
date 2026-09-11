package com.example.nfccopy.data

import android.content.Context
import android.util.Log
import com.example.nfccopy.model.CardDump
import org.json.JSONArray
import java.io.File

/**
 * Persists the read/import card list as a JSON array of [CardDump.toJson] objects.
 * File I/O is synchronous; callers should invoke from a background thread.
 */
class CardStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    fun load(): List<CardDump> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            buildList {
                for (i in 0 until arr.length()) {
                    add(CardDump.fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "load failed path=${file.absolutePath}", e)
            } catch (_: Throwable) {
                System.err.println("CardStore load failed: ${e.message}")
            }
            emptyList()
        }
    }

    fun save(cards: List<CardDump>) {
        try {
            val arr = JSONArray()
            for (c in cards) arr.put(c.toJson())
            val dir = file.parentFile ?: error("no parent for ${file.path}")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "save failed path=${file.absolutePath}", e)
            } catch (_: Throwable) {
                System.err.println("CardStore save failed: ${e.message}")
            }
        }
    }

    companion object {
        const val FILE_NAME = "saved_cards.json"
        private const val TAG = "CardStore"
    }
}
