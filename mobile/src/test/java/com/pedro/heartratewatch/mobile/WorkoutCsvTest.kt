package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class WorkoutCsvTest {

    @Test
    fun readsQuotedFieldsBomAndLineBreaksInsideQuotes() {
        val csv = "﻿Workout,Date,Exercise,Set,Weight (lb),Reps,Exercise note\r\n" +
            "1,2025-03-14,Squat,1,135,5,\"felt good, slow\"\r\n" +
            "1,2025-03-14,Squat,2,135,5,\"said \"\"hard\"\"\nsecond line\"\r\n"
        val rows = WorkoutImporter.parseCsv(csv)
        assertEquals(3, rows.size)
        assertEquals("Workout", rows[0][0])
        assertEquals("felt good, slow", rows[1][6])
        assertEquals("said \"hard\"\nsecond line", rows[2][6])
    }

    @Test
    fun readsSemicolonSeparatedFilesFromEuropeanExcel() {
        val rows = WorkoutImporter.parseCsv("Workout;Exercise;Reps\n1;Squat;5\n")
        assertEquals(listOf("1", "Squat", "5"), rows[1])
    }

    @Test
    fun aCsvFileImportsLikeASheet() {
        val csv = "Workout,Date,Exercise,Set,Weight (lb),Reps\n1,2025-03-14,Squat,1,135,5\n1,2025-03-14,Squat,2,135,5\n"
        val plan = WorkoutImporter.plan(ByteArrayInputStream(csv.toByteArray()), emptyList())
        assertEquals(1, plan.workouts.size)
        assertEquals(2, plan.setCount)
        assertEquals("Squat", plan.newExercises.single().name)
    }

    @Test
    fun exportQuotesCellsThatNeedIt() {
        assertEquals("plain", WorkoutExporter.csvCell("plain"))
        assertEquals("\"a, b\"", WorkoutExporter.csvCell("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", WorkoutExporter.csvCell("say \"hi\""))
        assertEquals("132.5", WorkoutExporter.csvCell(132.5))
        assertEquals("135", WorkoutExporter.csvCell(135.0))
        assertEquals("", WorkoutExporter.csvCell(null))
    }
}
