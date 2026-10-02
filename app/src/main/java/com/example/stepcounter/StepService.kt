package com.example.stepcounter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Υπηρεσία που μένει ενεργή όλη μέρα και μετράει βήματα,
 * ακόμα και με την εφαρμογή κλειστή και την οθόνη σβηστή.
 *
 * Το Android απαιτεί μια μόνιμη ειδοποίηση για τέτοιες υπηρεσίες.
 * Την αξιοποιούμε για να δείχνουμε τα βήματα της ημέρας.
 */
class StepService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var store: StepStore
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L

    /** Όταν η οθόνη της εφαρμογής είναι ανοιχτή, θέλουμε άμεση ενημέρωση. */
    private var live = false
    /** Βήματα από τον ανιχνευτή που ο μετρητής δεν έχει αναφέρει ακόμα. */
    private var pendingDetector = 0

    private val accelDetector = AccelStepDetector { publish(store.addSteps(1)) }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        store = StepStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasActivityPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_LIVE_ON -> live = true
            ACTION_LIVE_OFF -> live = false
        }
        goForeground(store.todaySteps)
        startSensors()
        StepRepo.steps.value = store.todaySteps + pendingDetector
        // Αν το σύστημα σκοτώσει την υπηρεσία, να την ξαναξεκινήσει.
        return START_STICKY
    }

    private fun goForeground(steps: Int) {
        val n = buildNotification(steps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startSensors() {
        sensorManager.unregisterListener(this)
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null) {
            releaseWakeLock()
            // Το τσιπ μετράει μόνο του. Με την εφαρμογή κλειστή αφήνουμε το
            // σύστημα να μαζεύει τα βήματα και να τα δίνει ανά λίγα δευτερόλεπτα
            // (λιγότερη μπαταρία). Με την εφαρμογή ανοιχτή τα θέλουμε αμέσως.
            sensorManager.registerListener(
                this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL,
                if (live) 0 else BATCH_LATENCY_US
            )
            // Ο ανιχνευτής βημάτων αναφέρει κάθε βήμα σχεδόν αμέσως. Τον
            // χρησιμοποιούμε για ζωντανή ένδειξη, και για να μη χαθούν τα
            // βήματα πριν έρθει η πρώτη τιμή του μετρητή.
            val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (detector != null && (live || !store.hasCounterBaseline)) {
                sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_FASTEST, 0)
            } else {
                pendingDetector = 0
            }
            sensorManager.flush(this)
        } else {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            // Χωρίς τσιπ βημάτων πρέπει ο επεξεργαστής να μένει ξύπνιος
            // για να διαβάζει το επιταχυνσιόμετρο με σβηστή οθόνη.
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StepCounter:accel")
                    .apply { acquire() }
            }
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = store.onCounterValue(event.values[0].toLong(), pendingDetector)
                pendingDetector = 0
                publish(total)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                pendingDetector++
                publish(store.todaySteps + pendingDetector)
            }
            Sensor.TYPE_ACCELEROMETER -> accelDetector.onSample(
                event.values[0], event.values[1], event.values[2], event.timestamp
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun publish(steps: Int) {
        StepRepo.steps.value = steps
        // Ενημερώνουμε την ειδοποίηση το πολύ μία φορά το δευτερόλεπτο.
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs > 1000) {
            lastNotifyMs = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(steps))
        }
    }

    private fun buildNotification(steps: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val goal = store.goal
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_steps)
            .setContentTitle("%,d βήματα σήμερα".format(steps).replace(',', '.'))
            .setContentText("Στόχος: %,d".format(goal).replace(',', '.'))
            .setProgress(goal, steps.coerceAtMost(goal), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Μέτρηση βημάτων", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Δείχνει τα βήματα της ημέρας" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "steps"
        private const val NOTIF_ID = 1
        private const val BATCH_LATENCY_US = 5_000_000 // 5 δευτερόλεπτα
        private const val ACTION_LIVE_ON = "live_on"
        private const val ACTION_LIVE_OFF = "live_off"

        fun start(context: Context, live: Boolean? = null) {
            if (!hasActivityPermission(context)) return
            val i = Intent(context, StepService::class.java)
            when (live) {
                true -> i.action = ACTION_LIVE_ON
                false -> i.action = ACTION_LIVE_OFF
                null -> {}
            }
            if (live == false) {
                // Η υπηρεσία τρέχει ήδη: απλώς της λέμε να γυρίσει σε οικονομία.
                runCatching { context.startService(i) }
            } else {
                ContextCompat.startForegroundService(context, i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepService::class.java))
        }

        fun hasActivityPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
