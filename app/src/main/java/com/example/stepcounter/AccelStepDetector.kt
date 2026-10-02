package com.example.stepcounter

import kotlin.math.sqrt

/**
 * Εναλλακτική ανίχνευση βημάτων για κινητά χωρίς αισθητήρα βημάτων.
 *
 * Σε κάθε βήμα το σώμα ανεβοκατεβαίνει και η επιτάχυνση κάνει μια "κορυφή"
 * πάνω από τη βαρύτητα (~9.81 m/s²). Εξομαλύνουμε το σήμα και μετράμε
 * κάθε φορά που περνάει πάνω από ένα κατώφλι, με ελάχιστο κενό ανάμεσα
 * στα βήματα (κανείς δεν κάνει πάνω από ~4 βήματα το δευτερόλεπτο).
 */
class AccelStepDetector(private val onStep: () -> Unit) {

    private var smoothed = 9.81f
    private var above = false
    private var lastStepNs = 0L

    fun onSample(x: Float, y: Float, z: Float, timestampNs: Long) {
        val a = sqrt(x * x + y * y + z * z)
        smoothed = ALPHA * a + (1 - ALPHA) * smoothed

        if (!above && smoothed > UPPER) {
            above = true
            if (timestampNs - lastStepNs > MIN_GAP_NS) {
                lastStepNs = timestampNs
                onStep()
            }
        } else if (above && smoothed < LOWER) {
            above = false
        }
    }

    companion object {
        private const val ALPHA = 0.25f          // εξομάλυνση θορύβου
        private const val UPPER = 11.0f          // m/s²
        private const val LOWER = 9.0f           // m/s² (υστέρηση)
        private const val MIN_GAP_NS = 250_000_000L
    }
}
