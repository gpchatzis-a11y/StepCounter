package com.example.stepcounter

import kotlin.math.sqrt

/**
 * Εναλλακτική ανίχνευση βημάτων για κινητά χωρίς αισθητήρα βημάτων.
 *
 * Σε κάθε βήμα το σώμα ανεβοκατεβαίνει, οπότε η επιτάχυνση ταλαντεύεται
 * γύρω από τη βαρύτητα. Αφαιρούμε έναν αργό μέσο όρο (τη βαρύτητα) και
 * μετράμε κάθε κορυφή που ξεπερνάει ένα κατώφλι που προσαρμόζεται
 * στο πόσο έντονα περπατάς, με ελάχιστο κενό 0,25 s ανάμεσα στα βήματα.
 */
class AccelStepDetector(private val onStep: () -> Unit) {

    private var fast = 9.81f      // ελαφριά εξομάλυνση του θορύβου
    private var slow = 9.81f      // αργός μέσος όρος ≈ βαρύτητα
    private var energy = 1.0f     // τυπικό πλάτος της ταλάντωσης
    private var above = false
    private var lastStepNs = 0L

    fun onSample(x: Float, y: Float, z: Float, timestampNs: Long) {
        val a = sqrt(x * x + y * y + z * z)
        fast += 0.35f * (a - fast)
        slow += 0.02f * (a - slow)
        val d = fast - slow
        energy += 0.01f * (kotlin.math.abs(d) - energy)

        val upper = maxOf(MIN_THRESHOLD, energy * 0.8f)
        if (!above && d > upper) {
            above = true
            if (timestampNs - lastStepNs > MIN_GAP_NS) {
                lastStepNs = timestampNs
                onStep()
            }
        } else if (above && d < 0f) {
            above = false
        }
    }

    companion object {
        private const val MIN_THRESHOLD = 0.6f       // m/s², αγνοεί μικρές κινήσεις
        private const val MIN_GAP_NS = 250_000_000L  // το πολύ 4 βήματα/δευτερόλεπτο
    }
}
