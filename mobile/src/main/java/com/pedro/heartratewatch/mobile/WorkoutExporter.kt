package com.pedro.heartratewatch.mobile

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the workout history as a .csv file, one row per set, in the same layout
 * [WorkoutImporter] reads -- so an export can be edited in Excel (or any spreadsheet) and imported
 * back, or used as a backup. The extra "Workout ID" column is what makes importing an export back onto the same phone
 * replace the original workouts instead of duplicating them. Pure Kotlin, no Android classes.
 */
object WorkoutExporter {

    val HEADERS = listOf(
        "Workout", "Date", "Exercise", "Set", "Weight (lb)", "Reps",
        "Exercise note", "Intensity", "Workout note", "Workout ID"
    )

    /**
     * Each workout's number: an imported workout keeps the number it was imported with, and the
     * rest are numbered after the highest of those, oldest first.
     */
    fun numbering(workouts: List<WorkoutLog>): Map<String, Int> {
        val ordered = workouts.sortedBy { it.startedAtMillis }
        val imported = { w: WorkoutLog -> w.id.removePrefix("imported-").takeIf { w.id.startsWith("imported-") }?.toIntOrNull() }
        var next = (ordered.mapNotNull(imported).maxOrNull() ?: 0) + 1
        return ordered.associate { it.id to (imported(it) ?: next++) }
    }

    /** Workouts oldest first, numbered by [numbering]. */
    fun write(workouts: List<WorkoutLog>, out: OutputStream) {
        val ordered = workouts.sortedBy { it.startedAtMillis }
        val numbers = numbering(workouts)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        val rows = mutableListOf<List<Any?>>()
        for (workout in ordered) {
            val number = numbers.getValue(workout.id)
            val date = dateFormat.format(Date(workout.startedAtMillis))
            var first = true
            for (log in workout.exercises) {
                if (log.sets.isEmpty()) {
                    rows += rowOf(number, date, log.exerciseName, null, null, null, log, 0, workout, first)
                    first = false
                    continue
                }
                log.sets.forEachIndexed { index, set ->
                    rows += rowOf(number, date, log.exerciseName, set.weight, set.reps, index + 1, log, index, workout, first)
                    first = false
                }
            }
            if (first && workout.note.isNotBlank()) {
                rows += listOf(number, date, "", null, null, null, "", "", workout.note, workout.id)
            }
        }
        writeCsv(listOf<List<Any?>>(HEADERS) + rows, out)
    }

    private fun rowOf(
        number: Int, date: String, exercise: String, weight: Double?, reps: Int?, setNumber: Int?,
        log: ExerciseLog, indexInExercise: Int, workout: WorkoutLog, firstOfWorkout: Boolean
    ): List<Any?> = listOf(
        number, date, exercise, setNumber,
        weight?.let { Math.round(it * 100) / 100.0 },
        reps,
        if (indexInExercise == 0) log.note else "",
        if (indexInExercise == 0) log.intensity?.label.orEmpty() else "",
        if (firstOfWorkout) workout.note else "",
        workout.id
    )

    // ---------------------------------------------------------------- the .csv file

    /** UTF-8 with a byte-order mark, so Excel opens accented exercise names correctly. */
    private fun writeCsv(rows: List<List<Any?>>, out: OutputStream) {
        val text = rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { csvCell(it) } }
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    /** Numbers as plain digits; text in quotes (with quotes doubled) whenever it holds a comma, quote or line break. */
    internal fun csvCell(value: Any?): String = when (value) {
        null -> ""
        is Int, is Long -> value.toString()
        is Double -> trimNumber(value)
        else -> {
            val text = value.toString()
            if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
    }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
}
