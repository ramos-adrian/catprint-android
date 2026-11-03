package com.adrianramos.catprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri

/** How grayscale is turned into dots. Ported from catprinter/img.py. */
enum class Mode(val label: String) {
    GRAY("Grises (16 niveles, MXW01)"),
    FLOYD("Floyd-Steinberg"),
    ATKINSON("Atkinson"),
    HALFTONE("Semitono"),
    THRESHOLD("Blanco y negro"),
}

/** A grayscale image, 0 = black .. 255 = white, exactly PRINT_WIDTH wide. */
class Gray(val width: Int, val height: Int, val px: IntArray) {

    /** Result of dithering: dots (true = black) and the burn levels when in GRAY mode. */
    class Result(val rows: List<BooleanArray>, val levels: List<ByteArray>?, val preview: Bitmap)

    fun render(mode: Mode): Result = when (mode) {
        Mode.GRAY -> {
            val levels = List(height) { y -> ByteArray(width) { x -> ((255 - px[y * width + x]) shr 4).toByte() } }
            // Dithered fallback for printers without grayscale.
            val rows = errorDiffusion(FLOYD_KERNEL)
            val prev = IntArray(px.size) { val l = levels[it / width][it % width].toInt(); 255 - l * 17 }
            Result(rows, levels, bitmap(prev))
        }
        Mode.FLOYD -> dots(errorDiffusion(FLOYD_KERNEL))
        Mode.ATKINSON -> dots(errorDiffusion(ATKINSON_KERNEL))
        Mode.HALFTONE -> dots(halftone())
        Mode.THRESHOLD -> dots(List(height) { y -> BooleanArray(width) { x -> px[y * width + x] < 128 } })
    }

    private fun dots(rows: List<BooleanArray>): Result {
        val prev = IntArray(px.size) { if (rows[it / width][it % width]) 0 else 255 }
        return Result(rows, null, bitmap(prev))
    }

    private fun bitmap(v: IntArray): Bitmap {
        val colors = IntArray(v.size) { Color.rgb(v[it], v[it], v[it]) }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    /** kernel: triples of (dx, dy, weight); weights are divided by `div`. */
    private class Kernel(val taps: IntArray, val div: Float)

    private fun errorDiffusion(k: Kernel): List<BooleanArray> {
        val buf = FloatArray(px.size) { px[it].toFloat() }
        val rows = List(height) { BooleanArray(width) }
        for (y in 0 until height) for (x in 0 until width) {
            val old = buf[y * width + x]
            val black = old <= 127f
            rows[y][x] = black
            val err = old - if (black) 0f else 255f
            var i = 0
            while (i < k.taps.size) {
                val nx = x + k.taps[i]; val ny = y + k.taps[i + 1]
                if (nx in 0 until width && ny < height) {
                    val j = ny * width + nx
                    buf[j] = (buf[j] + err * k.taps[i + 2] / k.div).coerceIn(0f, 255f)
                }
                i += 3
            }
        }
        return rows
    }

    /** Circles whose size follows the darkness of each 4x4 block (GravO8/halftone). */
    private fun halftone(): List<BooleanArray> {
        val side = 4
        val out = Bitmap.createBitmap(width, ((height + side - 1) / side) * side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { drawColor(Color.WHITE) }
        val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        for (by in 0 until height step side) for (bx in 0 until width step side) {
            var sum = 0; var n = 0
            for (y in by until minOf(by + side, height)) for (x in bx until minOf(bx + side, width)) { sum += px[y * width + x]; n++ }
            val intensity = 1f - sum / n / 255f
            val radius = (3 * intensity * side / 2).toInt()
            if (radius > 0) {
                // Like the Python version, each dot is drawn inside its own cell.
                canvas.save()
                canvas.clipRect(bx, by, bx + side, by + side)
                canvas.drawCircle(bx + side / 2f, by + side / 2f, radius.toFloat(), paint)
                canvas.restore()
            }
        }
        val p = IntArray(width * out.height)
        out.getPixels(p, 0, width, 0, 0, width, out.height)
        return List(out.height) { y -> BooleanArray(width) { x -> Color.red(p[y * width + x]) < 128 } }
    }

    companion object {
        private val FLOYD_KERNEL = Kernel(intArrayOf(1, 0, 7, -1, 1, 3, 0, 1, 5, 1, 1, 1), 16f)
        private val ATKINSON_KERNEL = Kernel(intArrayOf(1, 0, 1, 2, 0, 1, -1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 2, 1), 8f)

        /** Scales a bitmap to the print width and flattens it on white. */
        fun fromBitmap(src: Bitmap): Gray {
            val h = maxOf(1, Math.round(src.height * PRINT_WIDTH.toFloat() / src.width))
            val scaled = Bitmap.createBitmap(PRINT_WIDTH, h, Bitmap.Config.ARGB_8888)
            Canvas(scaled).apply {
                drawColor(Color.WHITE)
                drawBitmap(src, null, android.graphics.Rect(0, 0, PRINT_WIDTH, h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val p = IntArray(PRINT_WIDTH * h)
            scaled.getPixels(p, 0, PRINT_WIDTH, 0, 0, PRINT_WIDTH, h)
            for (i in p.indices) {
                val c = p[i]
                p[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }
            return Gray(PRINT_WIDTH, h, p)
        }

        /** Decodes an image, downsampled close to the print width and rotated per its EXIF data. */
        fun loadBitmap(context: Context, uri: Uri): Bitmap {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) throw IllegalArgumentException("No pude leer la imagen.")
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PRINT_WIDTH * 2) sample *= 2
            val bmp = cr.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw IllegalArgumentException("No pude leer la imagen.")
            val rotation = runCatching {
                cr.openInputStream(uri).use {
                    when (ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            }.getOrDefault(0)
            if (rotation == 0) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
    }
}
