package me.rerere.rikkahub.data.workspace

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import me.rerere.rikkahub.RouteActivity
import kotlin.system.exitProcess

/** Schedules the next app process before terminating the process that owns the closed Room DB. */
internal object WorkspaceArchiveRestart {
    private const val TAG = "WorkspaceArchiveRestart"
    private const val ACTION_RELAUNCH = "me.rerere.rikkahub.action.RELAUNCH_AFTER_ARCHIVE"
    private const val REQUEST_CODE = 0x524b
    private const val RELAUNCH_DELAY_MILLIS = 250L

    fun scheduleAndExit(context: Context): Nothing {
        val applicationContext = context.applicationContext
        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            action = ACTION_RELAUNCH
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        try {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
                ?: error("AlarmManager is unavailable; cannot relaunch after database restore")
            val triggerAt = SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MILLIS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
                }
            } catch (error: SecurityException) {
                // setAndAllowWhileIdle is normally permitted without exact-alarm access, but a
                // managed device can still reject it. A non-exact AlarmManager alarm is the safe
                // fallback; it is still armed before the old process exits.
                Log.w(TAG, "Idle relaunch alarm was rejected; using a regular alarm", error)
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pendingIntent)
            }
        } catch (error: Throwable) {
            // Never leave the process with a closed Room connection. A direct activity launch is
            // only a last-resort fallback; on normal devices the AlarmManager path above is used.
            Log.e(TAG, "Unable to arm relaunch alarm after database restore", error)
            runCatching { applicationContext.startActivity(intent) }
        }
        exitProcess(0)
    }
}
