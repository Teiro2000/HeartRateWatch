package com.pedro.heartratewatch.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max

/** A spot on the photo the number for [field] was read from (pixels of [LabelScan.photo]). */
data class LabelHighlight(val field: LabelField, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The numbers read off a label, the photo they came from (upright, as shown), and where on it each one was found. */
class LabelScan(val label: ScannedLabel, val photo: Bitmap, val highlights: List<LabelHighlight>)

/**
 * Reads a photo of a Nutrition Facts label: the text is recognized on the phone itself (Google's ML Kit, no internet
 * and nothing uploaded), then [NutritionLabelParser] picks the numbers out of it. Returns null if the picture couldn't
 * be read at all.
 */
object LabelScanner {

    private const val MAX_SIDE = 2400

    suspend fun scan(context: Context, photo: Uri): LabelScan? = runCatching {
        val bitmap = withContext(Dispatchers.IO) { loadUpright(context, photo) } ?: return@runCatching null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // The bitmap is already the right way up, so the boxes found are in its own pixels.
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.bottom, it.right) }
            }
            val rows = NutritionLabelParser.rowBoxes(lines)
            val parsed = NutritionLabelParser.parseDetailed(rows.map { it.text })
            val highlights = parsed.rowOf.map { (field, index) ->
                val row = rows[index]
                LabelHighlight(field, row.left, row.top, row.right, row.bottom)
            }
            LabelScan(parsed.label, bitmap, highlights)
        } finally {
            recognizer.close()
        }
    }.getOrNull()

    /** The photo decoded at a sensible size and turned the way up the camera meant (many phones save it sideways). */
    private fun loadUpright(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return decoded
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
}
