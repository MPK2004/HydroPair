package com.example.myapplication

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.RemoteViews
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.Calendar

class PartnerAvatarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REPLY_WIDGET) {
            replyFromWidget(context)
            WaterGlassWidgetProvider.updateAllWidgets(context)
            updateAllWidgets(context)
        }
    }

    companion object {
        const val ACTION_REPLY_WIDGET = "com.example.myapplication.ACTION_REPLY_WIDGET"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, PartnerAvatarWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, appWidgetManager, id)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences("hydropair_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "User") ?: "User"
            val cachedJson = prefs.getString("cached_reminders", "[]") ?: "[]"
            val customStickerUri = prefs.getString("custom_sticker_uri", null)

            var pendingReminder: ReminderRow? = null
            try {
                val json = Json { ignoreUnknownKeys = true; isLenient = true }
                val reminders = json.decodeFromString<List<ReminderRow>>(cachedJson)
                // Find un-replied reminder from partner
                pendingReminder = reminders.firstOrNull { r ->
                    !r.sender.equals(userName, ignoreCase = true) && r.reply_amount_ml == null
                }
            } catch (e: Exception) {
                pendingReminder = null
            }

            val isDrinkingState = pendingReminder != null
            val views = RemoteViews(context.packageName, R.layout.widget_partner_avatar)

            if (isDrinkingState) {
                val partnerName = pendingReminder?.sender ?: "Partner"
                views.setTextViewText(R.id.widget_status_text, "💧 $partnerName says: Drink Water!")
                views.setTextViewText(R.id.widget_btn_reply, "I Drank Water! 💧 (+250ml)")

                val avatarBitmap = loadStickerOrAsset(
                    context = context,
                    customUriStr = customStickerUri,
                    defaultDrawableRes = R.drawable.avatar_drinking,
                    statusBadgeText = "DRINK WATER! 💧",
                    badgeColorHex = "#EF4444"
                )
                views.setImageViewBitmap(R.id.widget_avatar_image, avatarBitmap)

                // PendingIntent to reply directly
                val replyIntent = Intent(context, PartnerAvatarWidgetProvider::class.java).apply {
                    action = ACTION_REPLY_WIDGET
                }
                val pendingReplyIntent = PendingIntent.getBroadcast(
                    context,
                    1,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_reply, pendingReplyIntent)
            } else {
                // Rotate human daily activities during normal idle time (Reading, Gaming, Music)
                val cal = Calendar.getInstance()
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val min = cal.get(Calendar.MINUTE)
                val activityIndex = (hour * 4 + min / 15) % 3

                val (drawableRes, activityText) = when (activityIndex) {
                    0 -> Pair(R.drawable.avatar_reading, "Reading a book 📖")
                    1 -> Pair(R.drawable.avatar_gaming, "Playing games 🎮")
                    else -> Pair(R.drawable.avatar_music, "Vibing to music 🎧")
                }

                views.setTextViewText(R.id.widget_status_text, "Partner is idle • $activityText")
                views.setTextViewText(R.id.widget_btn_reply, "Send Reminder 🚀")

                val avatarBitmap = loadStickerOrAsset(
                    context = context,
                    customUriStr = customStickerUri,
                    defaultDrawableRes = drawableRes,
                    statusBadgeText = activityText,
                    badgeColorHex = "#10B981"
                )
                views.setImageViewBitmap(R.id.widget_avatar_image, avatarBitmap)

                // PendingIntent to open main app
                val mainIntent = Intent(context, MainActivity::class.java)
                val pendingMainIntent = PendingIntent.getActivity(
                    context,
                    0,
                    mainIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_reply, pendingMainIntent)
            }

            // Click avatar image to open app
            val mainIntent = Intent(context, MainActivity::class.java)
            val pendingMainIntent = PendingIntent.getActivity(
                context,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_avatar_image, pendingMainIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun replyFromWidget(context: Context) {
            val prefs = context.getSharedPreferences("hydropair_prefs", Context.MODE_PRIVATE)
            val userName = prefs.getString("user_name", "User") ?: "User"
            val cachedJson = prefs.getString("cached_reminders", "[]") ?: "[]"

            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            var currentList = try {
                json.decodeFromString<List<ReminderRow>>(cachedJson)
            } catch (e: Exception) {
                emptyList()
            }

            val target = currentList.firstOrNull { r ->
                !r.sender.equals(userName, ignoreCase = true) && r.reply_amount_ml == null
            }

            if (target != null) {
                currentList = currentList.map { r ->
                    if (r.id == target.id) r.copy(reply_amount_ml = 250) else r
                }
                try {
                    val updatedJson = json.encodeToString(currentList)
                    prefs.edit().putString("cached_reminders", updatedJson).apply()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        private fun loadStickerOrAsset(
            context: Context,
            customUriStr: String?,
            defaultDrawableRes: Int,
            statusBadgeText: String,
            badgeColorHex: String
        ): Bitmap {
            val baseBitmap: Bitmap = if (!customUriStr.isNull_or_empty()) {
                try {
                    val uri = Uri.parse(customUriStr)
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream) ?: BitmapFactory.decodeResource(context.resources, defaultDrawableRes)
                } catch (e: Exception) {
                    BitmapFactory.decodeResource(context.resources, defaultDrawableRes)
                }
            } else {
                BitmapFactory.decodeResource(context.resources, defaultDrawableRes)
            }

            // Create canvas bitmap to apply soft rounded corners & status badge
            val scaledBitmap = Bitmap.createScaledBitmap(baseBitmap, 320, 320, true)
            val output = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

            // Draw Activity Badge overlay
            val badgeRect = RectF(10f, 260f, 310f, 310f)
            paint.color = Color.parseColor("#E00F172A")
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(badgeRect, 14f, 14f, paint)

            paint.color = Color.parseColor(badgeColorHex)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawRoundRect(badgeRect, 14f, 14f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 20f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(statusBadgeText, 160f, 292f, paint)

            return output
        }

        private fun String?.isNull_or_empty(): Boolean = this == null || this.isEmpty()
    }
}
