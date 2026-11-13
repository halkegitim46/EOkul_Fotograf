package com.eokul.studio

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eokul.studio.core.Crop
import kotlin.math.abs
import kotlin.math.min

/** Düzenleyicinin durumu: tek fotoğraf, kırpma kutusu ve geri alma geçmişi. */
class EditSession(val name: String) {
    var loading by mutableStateOf(true)
    var bitmap by mutableStateOf<Bitmap?>(null)
    var pixels: IntArray? = null
    var iw: Int = 0
    var ih: Int = 0

    var box by mutableStateOf(Box(0.0, 0.0, 1.0, 1.0))
    var startBox: Box = Box(0.0, 0.0, 1.0, 1.0)
    var faces by mutableStateOf<List<FaceInfo>>(emptyList())
    var faceFound by mutableStateOf(false)
    var canUndo by mutableStateOf(false)

    private val history = ArrayList<Box>()
    private var lastPushNs = 0L

    fun changed(): Boolean = !box.sameAs(startBox)

    private fun push(b: Box, coalesce: Boolean) {
        val now = System.nanoTime()
        if (coalesce && history.isNotEmpty() && now - lastPushNs < BURST_NS) {
            lastPushNs = now
            return
        }
        history.add(b)
        if (history.size > MAX_HISTORY) history.removeAt(0)
        lastPushNs = now
        canUndo = true
    }

    /** Sürükleme / yakınlaştırma serileri tek geri alma adımı sayılır. */
    fun pan(dx: Double, dy: Double) {
        val w = box.width
        val h = box.height
        val x1 = (box.x1 + dx).coerceIn(0.0, (iw - w).coerceAtLeast(0.0))
        val y1 = (box.y1 + dy).coerceIn(0.0, (ih - h).coerceAtLeast(0.0))
        if (abs(x1 - box.x1) < 1e-9 && abs(y1 - box.y1) < 1e-9) return
        push(box, true)
        box = Box(x1, y1, x1 + w, y1 + h)
    }

    /** [factor] < 1: kutu küçülür (yakınlaşma). (ax, ay): sabit kalacak nokta (görüntü koordinatı). */
    fun zoom(factor: Double, ax: Double, ay: Double) {
        val maxW = min(iw.toDouble(), ih * RATIO)
        val minW = min(40.0, maxW)
        val newW = (box.width * factor).coerceIn(minW, maxW)
        if (abs(newW - box.width) < 1e-6) return
        push(box, true)
        val k = newW / box.width
        val ncx = ax + (box.cx - ax) * k
        val ncy = ay + (box.cy - ay) * k
        box = Box.of(Crop.fitInside(Crop.fromCenter(ncx, ncy, newW, RATIO), iw, ih, RATIO))
    }

    fun moveBox(b: Box) {
        push(box, false)
        box = b.fitInside(iw, ih)
    }

    fun undo() {
        if (history.isEmpty()) return
        box = history.removeAt(history.size - 1)
        canUndo = history.isNotEmpty()
    }

    companion object {
        private const val BURST_NS = 600_000_000L
        private const val MAX_HISTORY = 50
    }
}
