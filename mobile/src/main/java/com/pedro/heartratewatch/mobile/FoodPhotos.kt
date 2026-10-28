package com.pedro.heartratewatch.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

/**
 * The photo a saved food was entered from (its nutrition label), kept as a small JPEG named after the food's id, so
 * the food database shows what the label looked like. Part of a backup.
 */
object FoodPhotos {

    const val FOLDER = "food_photos"
    private const val MAX_SIDE = 1600
    private const val QUALITY = 85

    private fun file(context: Context, foodId: String) = File(File(context.filesDir, FOLDER), "$foodId.jpg")

    fun save(context: Context, foodId: String, photo: Bitmap) {
        val folder = File(context.filesDir, FOLDER).also { it.mkdirs() }
        val longest = max(photo.width, photo.height)
        val scaled = if (longest <= MAX_SIDE) photo else {
            val k = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(photo, (photo.width * k).toInt(), (photo.height * k).toInt(), true)
        }
        File(folder, "$foodId.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
    }

    /** The photo, decoded no bigger than about [maxSide] pixels on its long side; null if there is none. */
    fun load(context: Context, foodId: String, maxSide: Int = MAX_SIDE): Bitmap? {
        val file = file(context, foodId).takeIf { it.exists() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun delete(context: Context, foodId: String) {
        file(context, foodId).delete()
    }
}
