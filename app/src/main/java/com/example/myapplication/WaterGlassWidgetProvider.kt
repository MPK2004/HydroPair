package com.example.myapplication

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

class WaterGlassWidgetProvider : AppWidgetProvider() {

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
        if (intent.action == ACTION_LOG_250) {
            logWaterFromWidget(context, 250)
            updateAllWidgets(context)
        }
    }

    companion object {
        const val ACTION_LOG_250 = "com.example.myapplication.ACTION_LOG_250"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, WaterGlassWidgetProvider::class.java)
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
            val dailyGoalMl = prefs.getInt("daily_goal_ml", 2000)
            val cachedJson = prefs.getString("cached_reminders", "[]") ?: "[]"

            var todayConsumedMl = 0
            try {
                val json = Json { ignoreUnknownKeys = true; isLenient = true }
                val reminders = json.decodeFromString<List<ReminderRow>>(cachedJson)
                todayConsumedMl = reminders.sumOf { r ->
                    var sum = 0
                    if (r.sender == userName) sum += r.sender_amount_ml
                    if (r.reply_amount_ml != null && r.sender != userName) sum += r.reply_amount_ml
                    sum
                }
            } catch (e: Exception) {
                todayConsumedMl = 0
            }

            val progress = (todayConsumedMl.toFloat() / dailyGoalMl.toFloat()).coerceIn(0f, 1f)

            val views = RemoteViews(context.packageName, R.layout.widget_water_glass)
            views.setTextViewText(R.id.widget_intake_text, "$todayConsumedMl / $dailyGoalMl ml")

            // Render dynamic water glass bitmap
            val glassBitmap = drawWaterGlassBitmap(progress, "${(progress * 100).toInt()}%")
            views.setImageViewBitmap(R.id.widget_glass_image, glassBitmap)

            // Intent for +250ml log button
            val logIntent = Intent(context, WaterGlassWidgetProvider::class.java).apply {
                action = ACTION_LOG_250
            }
            val pendingLogIntent = PendingIntent.getBroadcast(
                context,
                0,
                logIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_log_250, pendingLogIntent)

            // Intent to launch main activity when clicking glass image or background
            val mainIntent = Intent(context, MainActivity::class.java)
            val pendingMainIntent = PendingIntent.getActivity(
                context,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_glass_image, pendingMainIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun logWaterFromWidget(context: Context, amountMl: Int) {
            val prefs = context.getSharedPreferences("hydropair_prefs", Context.MODE_PRIVATE)
            val pairCode = prefs.getString("pair_code", "AQUA-101") ?: "AQUA-101"
            val userName = prefs.getString("user_name", "User") ?: "User"
            val cachedJson = prefs.getString("cached_reminders", "[]") ?: "[]"

            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            var currentList = try {
                json.decodeFromString<List<ReminderRow>>(cachedJson)
            } catch (e: Exception) {
                emptyList()
            }

            val newRow = ReminderRow(
                id = (System.currentTimeMillis() % 10000000).toInt(),
                pair_code = pairCode,
                sender = userName,
                reminder_text = "Widget Quick Log",
                sender_amount_ml = amountMl,
                reply_amount_ml = null,
                created_at = "Today"
            )

            currentList = listOf(newRow) + currentList
            try {
                val updatedJson = json.encodeToString(currentList)
                prefs.edit().putString("cached_reminders", updatedJson).apply()
            } catch (e: Exception) {
                // ignore
            }
        }

        private fun drawWaterGlassBitmap(progress: Float, percentText: String): Bitmap {
            val width = 360
            val height = 360
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Glass geometry bounds
            val leftRim = 70f
            val rightRim = 290f
            val topRim = 50f
            val leftBase = 100f
            val rightBase = 260f
            val bottomBase = 320f

            val glassHeight = bottomBase - topRim

            // Calculate liquid level
            val fillHeight = glassHeight * progress
            val liquidTopY = bottomBase - fillHeight

            // Interpolate left/right liquid top coordinates based on trapezoid taper
            val fillRatio = progress
            val liquidLeftX = leftBase + (leftRim - leftBase) * fillRatio
            val liquidRightX = rightBase + (rightRim - rightBase) * fillRatio
            val midX = (liquidLeftX + liquidRightX) / 2f

            // Draw Liquid if progress > 0
            if (progress > 0f) {
                val liquidPath = Path().apply {
                    moveTo(liquidLeftX, liquidTopY)
                    // Wave top line
                    val waveHeight = 12f * (1f - (progress - 0.5f).let { Math.abs(it) } * 2f)
                    quadTo(midX, liquidTopY - waveHeight, liquidRightX, liquidTopY)
                    lineTo(rightBase - 4f, bottomBase - 6f)
                    quadTo(midX, bottomBase + 8f, leftBase + 4f, bottomBase - 6f)
                    close()
                }

                val liquidShader = LinearGradient(
                    0f, liquidTopY, 0f, bottomBase,
                    Color.parseColor("#06B6D4"), Color.parseColor("#0284C7"),
                    Shader.TileMode.CLAMP
                )
                paint.shader = liquidShader
                paint.style = Paint.Style.FILL
                canvas.drawPath(liquidPath, paint)
                paint.shader = null

                // Draw bubbles
                paint.color = Color.parseColor("#80FFFFFF")
                canvas.drawCircle(midX - 25f, bottomBase - fillHeight * 0.4f, 6f, paint)
                canvas.drawCircle(midX + 30f, bottomBase - fillHeight * 0.7f, 9f, paint)
                canvas.drawCircle(midX, bottomBase - fillHeight * 0.2f, 5f, paint)
            }

            // Draw Glass Outer Contour
            val glassPath = Path().apply {
                moveTo(leftRim, topRim)
                lineTo(rightRim, topRim)
                lineTo(rightBase, bottomBase)
                quadTo((leftBase + rightBase) / 2f, bottomBase + 16f, leftBase, bottomBase)
                close()
            }

            paint.color = Color.parseColor("#40FFFFFF")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 10f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(glassPath, paint)

            // Draw Glass Rim Oval
            val rimRect = RectF(leftRim - 4f, topRim - 10f, rightRim + 4f, topRim + 10f)
            paint.color = Color.parseColor("#60F8FAFC")
            paint.strokeWidth = 6f
            canvas.drawOval(rimRect, paint)

            // Glass Reflection Line
            paint.color = Color.parseColor("#30FFFFFF")
            paint.strokeWidth = 8f
            canvas.drawLine(leftRim + 20f, topRim + 25f, leftBase + 18f, bottomBase - 25f, paint)

            // Draw Percent Text in Center
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 34f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(percentText, width / 2f, height / 2f + 10f, paint)

            return bitmap
        }
    }
}
