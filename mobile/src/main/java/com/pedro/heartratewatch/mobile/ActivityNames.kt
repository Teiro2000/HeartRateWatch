package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType

/** What an activity is called on the phone (pickers, history, charts). UI text, so it lives here rather than in :shared. */
fun ActivityType.displayName(): String = when (this) {
    ActivityType.RUN -> "Running"
    ActivityType.WALK -> "Walking"
    ActivityType.BIKE -> "Biking"
    ActivityType.STATIONARY_BIKE -> "Stationary bike"
}

/** The order activities are offered in. */
val ACTIVITY_ORDER = listOf(ActivityType.RUN, ActivityType.WALK, ActivityType.BIKE, ActivityType.STATIONARY_BIKE)
