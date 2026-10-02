package com.example.stepcounter

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var store: StepStore
    private var hasStepSensor = false
    private var hasAnySensor = false

    private var heightCm by mutableIntStateOf(175)
    private var autoEnabled by mutableStateOf(true)
    private var permissionGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionGranted = StepService.hasActivityPermission(this)
            if (permissionGranted && autoEnabled) StepService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hasStepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        hasAnySensor = hasStepSensor || sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

        store = StepStore(this)
        heightCm = store.heightCm
        autoEnabled = store.autoEnabled
        permissionGranted = StepService.hasActivityPermission(this)

        setContent {
            val steps by StepRepo.steps.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    StepScreen(
                        steps = steps,
                        goal = store.goal,
                        heightCm = heightCm,
                        usingHardware = hasStepSensor,
                        hasAnySensor = hasAnySensor,
                        permissionGranted = permissionGranted,
                        autoEnabled = autoEnabled,
                        onRequestPermission = { requestPermissions() },
                        onHeightChange = {
                            heightCm = it
                            store.heightCm = it
                        },
                        onAutoChange = { on ->
                            autoEnabled = on
                            store.autoEnabled = on
                            if (on) StepService.start(this) else StepService.stop(this)
                        },
                        onReset = {
                            store.resetToday()
                            StepRepo.steps.value = 0
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Η μέρα μπορεί να άλλαξε όσο η εφαρμογή ήταν κλειστή.
        StepRepo.steps.value = store.todaySteps
        permissionGranted = StepService.hasActivityPermission(this)
        if (permissionGranted && autoEnabled) StepService.start(this)
    }

    private fun requestPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }
}

@Composable
fun StepScreen(
    steps: Int,
    goal: Int,
    heightCm: Int,
    usingHardware: Boolean,
    hasAnySensor: Boolean,
    permissionGranted: Boolean,
    autoEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onHeightChange: (Int) -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    // Μήκος βήματος ≈ 0.415 × ύψος (συνηθισμένη εκτίμηση για περπάτημα)
    val strideM = heightCm * 0.415 / 100.0
    val distanceKm = steps * strideM / 1000.0
    // Περίπου 0.04 θερμίδες ανά βήμα για έναν μέσο ενήλικα
    val kcal = steps * 0.04
    val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Μετρητής Βημάτων", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        if (!hasAnySensor) {
            Text("Η συσκευή δεν έχει κατάλληλο αισθητήρα.", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (!permissionGranted) {
            Text(
                "Χρειάζεται η άδεια «Φυσική δραστηριότητα» για να μετράω βήματα, " +
                    "και η άδεια ειδοποιήσεων για να βλέπεις τα βήματα στην ειδοποίηση.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermission) { Text("Δώσε άδεια") }
            return@Column
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 14.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%,d".format(steps).replace(',', '.'), fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Text("από %,d".format(goal).replace(',', '.'), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("%.2f".format(distanceKm), "χλμ")
            Stat("${kcal.roundToInt()}", "θερμίδες")
            Stat("${(progress * 100).roundToInt()}%", "στόχος")
        }

        Spacer(Modifier.height(32.dp))
        Text("Ύψος: $heightCm cm (μήκος βήματος %.0f cm)".format(strideM * 100))
        Slider(
            value = heightCm.toFloat(),
            onValueChange = { onHeightChange(it.roundToInt()) },
            valueRange = 140f..210f
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Μέτρηση όλη μέρα", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (usingHardware) "Με τον αισθητήρα βημάτων, ελάχιστη μπαταρία."
                    else "Με το επιταχυνσιόμετρο, ξοδεύει περισσότερη μπαταρία.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = autoEnabled, onCheckedChange = onAutoChange)
        }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) { Text("Μηδενισμός σημερινών") }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
