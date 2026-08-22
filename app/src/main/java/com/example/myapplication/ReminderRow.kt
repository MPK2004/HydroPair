package com.example.myapplication

import kotlinx.serialization.Serializable

@Serializable
data class ReminderRow(
    val id: Int = 0,
    val pair_code: String = "",
    val sender: String = "",
    val reminder_text: String = "",
    val sender_amount_ml: Int = 0,
    val reply_amount_ml: Int? = null,
    val created_at: String = ""
)
