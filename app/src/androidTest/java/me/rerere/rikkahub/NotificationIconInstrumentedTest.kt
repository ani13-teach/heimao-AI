package me.rerere.rikkahub

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationIconInstrumentedTest {
    @Test
    fun notificationUsesSmallIcon() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notificationManager = NotificationManagerCompat.from(context)
        val platformNotificationManager = context.getSystemService(NotificationManager::class.java)
        val notificationId = 0x484549
        assumeTrue(
            "POST_NOTIFICATIONS must be granted before running the visual notification test",
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )

        val notification = NotificationCompat.Builder(
            context,
            CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("黑猫ai")
            .setContentText("通知小图标真机验证")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
            var activeNotification = platformNotificationManager.activeNotifications
                .firstOrNull { it.id == notificationId }
            repeat(30) {
                if (activeNotification != null) return@repeat
                Thread.sleep(100L)
                activeNotification = platformNotificationManager.activeNotifications
                    .firstOrNull { it.id == notificationId }
            }

            assertNotNull(activeNotification)
            assertEquals(context.packageName, activeNotification?.packageName)
            assertEquals(R.drawable.small_icon, activeNotification?.notification?.smallIcon?.resId)

            val holdMs = InstrumentationRegistry.getArguments()
                .getString("notificationHoldMs")
                ?.toLongOrNull()
                ?.coerceIn(0L, 30_000L)
                ?: 0L
            if (holdMs > 0L) {
                Thread.sleep(holdMs)
            }
        } finally {
            notificationManager.cancel(notificationId)
        }
    }
}
