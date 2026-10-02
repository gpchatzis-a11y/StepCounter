package com.example.stepcounter

import android.content.Context
import java.time.LocalDate

/**
 * Κρατάει τα βήματα της ημέρας, ώστε να μη χάνονται όταν κλείνει η εφαρμογή.
 *
 * Ο αισθητήρας TYPE_STEP_COUNTER δίνει τα συνολικά βήματα από την τελευταία
 * επανεκκίνηση του κινητού. Κρατάμε την τελευταία τιμή που είδαμε και
 * προσθέτουμε μόνο τη διαφορά. Αν η τιμή μικρύνει, έγινε επανεκκίνηση.
 */
class StepStore(context: Context) {

    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)

    var todaySteps: Int
        get() {
            rollOverIfNewDay()
            return prefs.getInt(KEY_TODAY, 0)
        }
        private set(value) = prefs.edit().putInt(KEY_TODAY, value).apply()

    var heightCm: Int
        get() = prefs.getInt(KEY_HEIGHT, 175)
        set(value) = prefs.edit().putInt(KEY_HEIGHT, value).apply()

    var goal: Int
        get() = prefs.getInt(KEY_GOAL, 10_000)
        set(value) = prefs.edit().putInt(KEY_GOAL, value).apply()

    /** Αν η μέτρηση όλη μέρα είναι ενεργή (και μετά από επανεκκίνηση). */
    var autoEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** Από τον αισθητήρα βημάτων: συνολικά βήματα από την εκκίνηση. */
    fun onCounterValue(total: Long): Int {
        rollOverIfNewDay()
        val last = prefs.getLong(KEY_LAST_COUNTER, -1L)
        val delta = when {
            last < 0 -> 0L            // πρώτη φορά: ξεκινάμε από εδώ
            total < last -> total     // έγινε επανεκκίνηση του κινητού
            else -> total - last
        }
        prefs.edit().putLong(KEY_LAST_COUNTER, total).apply()
        return addSteps(delta.toInt())
    }

    /** Από την εναλλακτική μέθοδο με το επιταχυνσιόμετρο. */
    fun addSteps(n: Int): Int {
        rollOverIfNewDay()
        val now = prefs.getInt(KEY_TODAY, 0) + n
        todaySteps = now
        return now
    }

    fun resetToday() {
        prefs.edit().putInt(KEY_TODAY, 0).apply()
    }

    private fun rollOverIfNewDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DATE, null) != today) {
            prefs.edit().putString(KEY_DATE, today).putInt(KEY_TODAY, 0).apply()
        }
    }

    companion object {
        private const val KEY_TODAY = "today"
        private const val KEY_DATE = "date"
        private const val KEY_LAST_COUNTER = "last_counter"
        private const val KEY_HEIGHT = "height"
        private const val KEY_GOAL = "goal"
        private const val KEY_AUTO = "auto"
    }
}
