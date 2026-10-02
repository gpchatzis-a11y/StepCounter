package com.example.stepcounter

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime

/**
 * Κρατάει τα βήματα: σύνολο ημέρας, ανά ώρα για σήμερα και ιστορικό ημερών.
 *
 * Ο αισθητήρας TYPE_STEP_COUNTER δίνει τα συνολικά βήματα από την τελευταία
 * επανεκκίνηση του κινητού. Κρατάμε την τελευταία τιμή που είδαμε και
 * προσθέτουμε μόνο τη διαφορά. Αν η τιμή μικρύνει, έγινε επανεκκίνηση.
 */
class StepStore(context: Context) {

    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)

    val todaySteps: Int
        get() {
            rollOverIfNewDay()
            return prefs.getInt(KEY_TODAY, 0)
        }

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

    /** Αν έχουμε ήδη μια τιμή αναφοράς από τον αισθητήρα βημάτων. */
    val hasCounterBaseline: Boolean
        get() = prefs.getLong(KEY_LAST_COUNTER, -1L) >= 0

    /**
     * Από τον αισθητήρα βημάτων: συνολικά βήματα από την εκκίνηση.
     * [stepsBeforeBaseline]: βήματα που είδε ο ανιχνευτής βημάτων πριν έρθει
     * η πρώτη τιμή του μετρητή, ώστε να μη χαθούν τα πρώτα βήματα.
     */
    fun onCounterValue(total: Long, stepsBeforeBaseline: Int = 0): Int {
        rollOverIfNewDay()
        val last = prefs.getLong(KEY_LAST_COUNTER, -1L)
        val delta = when {
            last < 0 -> stepsBeforeBaseline.toLong()
            total < last -> total     // έγινε επανεκκίνηση του κινητού
            else -> total - last
        }
        prefs.edit().putLong(KEY_LAST_COUNTER, total).apply()
        return addSteps(delta.toInt())
    }

    fun addSteps(n: Int): Int {
        rollOverIfNewDay()
        val now = prefs.getInt(KEY_TODAY, 0) + n
        val date = LocalDate.now().toString()
        val hourKey = KEY_HOUR + LocalTime.now().hour
        prefs.edit()
            .putInt(KEY_TODAY, now)
            .putInt(KEY_DAY + date, now)
            .putInt(hourKey, prefs.getInt(hourKey, 0) + n)
            .apply()
        return now
    }

    /** Βήματα ανά ώρα σήμερα (24 τιμές). */
    fun todayByHour(): List<Int> {
        rollOverIfNewDay()
        return (0 until 24).map { prefs.getInt(KEY_HOUR + it, 0) }
    }

    /** Τελευταίες [days] ημέρες, από την παλαιότερη ως σήμερα. */
    fun lastDays(days: Int = 7): List<Pair<LocalDate, Int>> {
        rollOverIfNewDay()
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            d to prefs.getInt(KEY_DAY + d, 0)
        }
    }

    fun resetToday() {
        val e = prefs.edit().putInt(KEY_TODAY, 0).putInt(KEY_DAY + LocalDate.now(), 0)
        (0 until 24).forEach { e.putInt(KEY_HOUR + it, 0) }
        e.apply()
    }

    private fun rollOverIfNewDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DATE, null) != today) {
            val e = prefs.edit().putString(KEY_DATE, today).putInt(KEY_TODAY, 0)
            (0 until 24).forEach { e.putInt(KEY_HOUR + it, 0) }
            // Σβήνουμε ημέρες παλαιότερες από 60 μέρες.
            val cutoff = LocalDate.now().minusDays(60)
            prefs.all.keys.filter { it.startsWith(KEY_DAY) }.forEach { k ->
                runCatching { LocalDate.parse(k.removePrefix(KEY_DAY)) }
                    .getOrNull()?.let { if (it.isBefore(cutoff)) e.remove(k) }
            }
            e.apply()
        }
    }

    companion object {
        private const val KEY_TODAY = "today"
        private const val KEY_DATE = "date"
        private const val KEY_LAST_COUNTER = "last_counter"
        private const val KEY_HEIGHT = "height"
        private const val KEY_GOAL = "goal"
        private const val KEY_AUTO = "auto"
        private const val KEY_DAY = "day_"
        private const val KEY_HOUR = "hour_"
    }
}
