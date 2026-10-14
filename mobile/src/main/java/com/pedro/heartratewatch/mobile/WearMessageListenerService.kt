package com.pedro.heartratewatch.mobile

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pedro.heartratewatch.shared.AlertType
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Receives the "/alert/..." messages ExerciseSessionService sends from the watch the instant a
 * threshold is crossed, and plays the corresponding audio cue on the phone. Also receives the
 * "/calibration/result" message CalibrationStore sends when a guided max-HR test is saved, and
 * the "/run/summary" message sent when a run stops.
 */
class WearMessageListenerService : WearableListenerService() {

    /** A finished session sent by the watch as a Data Layer item: save it, then clear it from the Data Layer. */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val items = dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }
            .filter { it.dataItem.uri.path?.startsWith(DataLayerPaths.RUN_SUMMARY + "/") == true }
            .map { it.freeze() }
        if (items.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            SessionIngest.pullPending(applicationContext)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == DataLayerPaths.ALERT_STOP) {
            AlertPlayer(applicationContext).stop()
            return
        }

        if (messageEvent.path == DataLayerPaths.CALIBRATION_RESULT) {
            val buffer = ByteBuffer.wrap(messageEvent.data)
            val bpm = buffer.int
            val recordedAtMillis = buffer.long
            CoroutineScope(Dispatchers.IO).launch {
                CalibrationRepository(applicationContext).save(CalibrationResult(bpm, recordedAtMillis))
            }
            return
        }

        // Older watch builds sent the finished session as a message; current ones send a Data Layer
        // item instead (see onDataChanged), which also survives the watch being out of range.
        if (messageEvent.path == DataLayerPaths.RUN_SUMMARY) {
            val summary = RunSummary.fromBytes(messageEvent.data)
            CoroutineScope(Dispatchers.IO).launch {
                SessionIngest.ingest(applicationContext, summary, route = null)
            }
            return
        }

        val type = when (messageEvent.path) {
            DataLayerPaths.ALERT_HIGH_HR -> AlertType.HIGH_HR
            DataLayerPaths.ALERT_LOW_HR -> AlertType.LOW_HR
            DataLayerPaths.ALERT_PACE_TOO_SLOW -> AlertType.PACE_TOO_SLOW
            DataLayerPaths.ALERT_PACE_TOO_FAST -> AlertType.PACE_TOO_FAST
            DataLayerPaths.ALERT_TARGET_REACHED -> AlertType.TARGET_REACHED
            DataLayerPaths.ALERT_HALFWAY -> AlertType.HALFWAY
            else -> return
        }
        AlertPlayer(applicationContext).play(type)
    }
}
