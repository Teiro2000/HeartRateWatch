package com.pedro.heartratewatch.mobile

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Reads a photo of a Nutrition Facts label: the text is recognized on the phone itself (Google's ML Kit, no internet
 * and nothing uploaded), then [NutritionLabelParser] picks the numbers out of it. Returns null if the picture couldn't
 * be read at all.
 */
object LabelScanner {

    suspend fun scan(context: Context, photo: Uri): ScannedLabel? = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = recognizer.process(InputImage.fromFilePath(context, photo)).await()
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.bottom) }
            }
            NutritionLabelParser.parse(NutritionLabelParser.rows(lines))
        } finally {
            recognizer.close()
        }
    }.getOrNull()
}
