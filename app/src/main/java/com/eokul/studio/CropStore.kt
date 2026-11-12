package com.eokul.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Kırpma kutularını ve işaretleri klasör başına saklar; uygulama yeniden açılınca düzenlemeler korunur. */
class CropStore(private val file: File) {

    data class Rec(
        val w: Int,
        val h: Int,
        val box: Box,
        val face: Boolean,
        val faceCount: Int,
        val edited: Boolean,
        val skipped: Boolean
    )

    private fun readItems(): JSONObject {
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version") == VERSION) root.optJSONObject("items") ?: JSONObject() else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun load(): Map<String, Rec> {
        val items = readItems()
        val out = HashMap<String, Rec>()
        val keys = items.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            try {
                val o = items.getJSONObject(name)
                val b = o.getJSONArray("box")
                if (b.length() != 4) continue
                out[name] = Rec(
                    w = o.getInt("w"),
                    h = o.getInt("h"),
                    box = Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)),
                    face = o.optBoolean("face"),
                    faceCount = o.optInt("faceCount"),
                    edited = o.optBoolean("edited"),
                    skipped = o.optBoolean("skipped")
                )
            } catch (e: Exception) {
                // bozuk kayıt: yoksay
            }
        }
        return out
    }

    private fun write(items: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            val root = JSONObject().put("version", VERSION).put("items", items)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            // kalıcılık başarısız olsa da uygulama çalışmaya devam eder
        }
    }

    fun save(items: List<PhotoItem>) {
        val merged = readItems()
        for (it in items) {
            val b = it.box ?: continue
            if (it.error != null) continue
            merged.put(
                it.name,
                JSONObject()
                    .put("w", it.width)
                    .put("h", it.height)
                    .put("box", JSONArray(listOf(b.x1, b.y1, b.x2, b.y2)))
                    .put("face", it.faceFound)
                    .put("faceCount", it.faceCount)
                    .put("edited", it.edited)
                    .put("skipped", it.skipped)
            )
        }
        write(merged)
    }

    /** Elle düzenlenmemiş (ve atlanmamış) kayıtları siler; yeniden otomatik kırpılsın. */
    fun dropUnedited() {
        val items = readItems()
        val keep = JSONObject()
        val keys = items.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = items.optJSONObject(name) ?: continue
            if (o.optBoolean("edited") || o.optBoolean("skipped")) keep.put(name, o)
        }
        write(keep)
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val VERSION = 3

        fun forKey(ctx: Context, key: String): CropStore {
            val dir = File(ctx.filesDir, "stores")
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            val hex = digest.joinToString("") { b -> "%02x".format(b) }
            return CropStore(File(dir, "$hex.json"))
        }
    }
}
