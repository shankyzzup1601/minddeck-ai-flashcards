package com.minddeck.nativeapp

import kotlin.math.max

data class StudyCard(val id: String, val deck: String, val subject: String, val front: String, val back: String, val due: Long = 0, val interval: Int = 0, val reviews: Int = 0)
data class DeckSummary(val title: String, val subject: String, val count: Int, val due: Int)
data class Profile(val name: String = "Student", val classLevel: String = "Class 12", val stream: String = "PCB", val complete: Boolean = false)
data class UserSession(val id: String, val name: String, val accessToken: String, val refreshToken: String)
data class TimerState(val duration: Int = 1500, val remaining: Int = 1500, val endAt: Long = 0, val sessionId: String = "") {
    val running get() = endAt > 0
    val paused get() = !running && remaining in 1 until duration
    fun secondsAt(now: Long): Int = if (running) ((max(0L, endAt - now) + 999) / 1000).toInt().coerceAtMost(duration) else remaining
}
object ReviewScheduler {
    fun next(card: StudyCard, remembered: Boolean, now: Long): StudyCard {
        val interval = if (!remembered) 0 else when (card.interval) { 0 -> 1; 1 -> 3; else -> (card.interval * 2).coerceAtMost(365) }
        return card.copy(interval = interval, reviews = card.reviews + 1, due = now + if (remembered) interval * 86_400_000L else 600_000L)
    }
}
fun subjectsFor(stream: String): List<String> = when(stream) {
    "PCM" -> listOf("Physics", "Chemistry", "Mathematics")
    "Commerce" -> listOf("Accountancy", "Business Studies", "Economics", "Entrepreneurship")
    else -> listOf("Physics", "Chemistry", "Biology")
}
