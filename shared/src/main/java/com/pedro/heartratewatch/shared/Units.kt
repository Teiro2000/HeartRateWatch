package com.pedro.heartratewatch.shared

import kotlin.math.roundToInt

/**
 * Units for displaying or entering a distance value. Lives in :shared (rather than just the phone
 * app) because it's now part of [TrainingSettings] and synced to the watch, so the tile/watch
 * screen format pace and distance the same way the phone does.
 */
enum class DistanceUnit(val symbol: String, val metersPerUnit: Double) {
    KILOMETERS("km", 1000.0),
    MILES("mi", 1609.344),
    METERS("meters", 1.0),
    FEET("feet", 0.3048),
    YARDS("yards", 0.9144),
    FOOTBALL_FIELDS("football fields", 91.44),
    BANANAS("bananas", 0.18)
}

/**
 * Units offered for pace specifically, deliberately narrower than [DistanceUnit] as a whole --
 * pace as "time per banana" doesn't produce a meaningful number (a marathon pace is a tiny
 * fraction of a second per banana), so only the two units runners actually pace themselves by are
 * offered here.
 */
val PACE_UNITS = listOf(DistanceUnit.KILOMETERS, DistanceUnit.MILES)

/** Formats a pace stored canonically as whole seconds-per-kilometer into "M:SS /<unit>". */
fun formatPace(secPerKm: Int, unit: DistanceUnit): String {
    val secPerUnit = (secPerKm * (unit.metersPerUnit / 1000.0)).roundToInt()
    return "%d:%02d /%s".format(secPerUnit / 60, secPerUnit % 60, unit.symbol)
}

/** "km/h" or "mph" for the distance unit a person picked (miles -> mph, anything else -> km/h). */
fun speedUnitLabel(unit: DistanceUnit): String = if (unit == DistanceUnit.MILES) "mph" else "km/h"

/** Speed in km/h for a pace stored as seconds per kilometer. */
fun speedKmhFromPace(secPerKm: Int): Double = 3600.0 / secPerKm

/** Pace (seconds per km) for a speed in km/h. */
fun paceFromSpeedKmh(kmh: Double): Int = (3600.0 / kmh).roundToInt()

/** A speed in km/h converted to the unit's own (km/h or mph). */
fun speedInUnit(kmh: Double, unit: DistanceUnit): Double = if (unit == DistanceUnit.MILES) kmh * 1000.0 / 1609.344 else kmh

/** The inverse of [speedInUnit]: a speed typed in the unit's own km/h or mph, as km/h. */
fun speedKmhFromUnit(value: Double, unit: DistanceUnit): Double =
    if (unit == DistanceUnit.MILES) value * 1609.344 / 1000.0 else value

/** Formats a pace stored as seconds per kilometer as a speed, e.g. "23.4 km/h" or "14.5 mph". */
fun formatSpeed(secPerKm: Int, unit: DistanceUnit): String =
    "%.1f %s".format(speedInUnit(speedKmhFromPace(secPerKm), unit), speedUnitLabel(unit))

/**
 * Formats a distance stored canonically in meters into "<value> <unit>", e.g. "5.23 km". Whole
 * numbers for the larger fixed units (meters/feet/yards), where a decimal just adds noise.
 */
fun formatDistance(meters: Float, unit: DistanceUnit): String {
    val value = meters / unit.metersPerUnit
    val decimals = when (unit) {
        DistanceUnit.METERS, DistanceUnit.FEET, DistanceUnit.YARDS -> 0
        else -> 2
    }
    return "%.${decimals}f %s".format(value, unit.symbol)
}
