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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
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
            if (permissionGranted && autoEnabled) StepService.start(this, live = true)
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
            // Ξαναδιαβάζουμε τα γραφήματα κάθε φορά που αλλάζουν τα βήματα.
            val hourly = remember(steps) { store.todayByHour() }
            val week = remember(steps) { store.lastDays(7) }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    StepScreen(
                        steps = steps,
                        goal = store.goal,
                        heightCm = heightCm,
                        hourly = hourly,
                        week = week,
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
                            if (on) StepService.start(this, live = true) else StepService.stop(this)
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
        StepRepo.steps.value = store.todaySteps
        permissionGranted = StepService.hasActivityPermission(this)
        // Με την οθόνη ανοιχτή: ζωντανή μέτρηση, χωρίς καθυστέρηση.
        if (permissionGranted && autoEnabled) StepService.start(this, live = true)
    }

    override fun onPause() {
        super.onPause()
        // Με την εφαρμογή κλειστή: οικονομία μπαταρίας.
        if (permissionGranted && autoEnabled) StepService.start(this, live = false)
    }

    private fun requestPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }
}

private fun fmt(n: Int) = "%,d".format(n).replace(',', '.')

@Composable
fun StepScreen(
    steps: Int,
    goal: Int,
    heightCm: Int,
    hourly: List<Int>,
    week: List<Pair<LocalDate, Int>>,
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Μετρητής Βημάτων", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

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

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 14.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(steps), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("από ${fmt(goal)}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("%.2f".format(distanceKm), "χλμ")
            Stat("${kcal.roundToInt()}", "θερμίδες")
            Stat("${(progress * 100).roundToInt()}%", "στόχος")
        }

        Spacer(Modifier.height(28.dp))
        ChartCard(title = "Σήμερα ανά ώρα") {
            val nowHour = LocalTime.now().hour
            BarChart(
                values = hourly,
                labelFor = { i -> if (i % 6 == 0) "%02d".format(i) else "" },
                tooltipFor = { i -> "%02d:00–%02d:00 · %s βήματα".format(i, (i + 1) % 24, fmt(hourly[i])) },
                highlightIndex = nowHour
            )
        }

        Spacer(Modifier.height(16.dp))
        ChartCard(title = "Τελευταίες 7 ημέρες") {
            val greek = Locale.forLanguageTag("el")
            BarChart(
                values = week.map { it.second },
                labelFor = { i -> week[i].first.dayOfWeek.getDisplayName(TextStyle.SHORT, greek) },
                tooltipFor = { i ->
                    val d = week[i].first
                    "${d.dayOfWeek.getDisplayName(TextStyle.FULL, greek)} ${d.dayOfMonth}/${d.monthValue} · ${fmt(week[i].second)} βήματα"
                },
                highlightIndex = week.lastIndex,
                goal = goal
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Ύψος: $heightCm cm (μήκος βήματος %.0f cm)".format(strideM * 100))
        Slider(
            value = heightCm.toFloat(),
            onValueChange = { onHeightChange(it.roundToInt()) },
            valueRange = 140f..210f
        )

        Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(16.dp))
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

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Απλό γράφημα στηλών. Πάτα μια στήλη για να δεις την τιμή της.
 * Η τρέχουσα ώρα/ημέρα έχει πιο έντονο χρώμα. Με [goal] σχεδιάζεται
 * διακεκομμένη γραμμή στόχου.
 */
@Composable
private fun BarChart(
    values: List<Int>,
    labelFor: (Int) -> String,
    tooltipFor: (Int) -> String,
    highlightIndex: Int,
    goal: Int? = null
) {
    var selected by remember(values.size) { mutableStateOf<Int?>(null) }
    val bar = MaterialTheme.colorScheme.primary
    val barDim = bar.copy(alpha = 0.45f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val goalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val maxV = maxOf(values.maxOrNull() ?: 0, goal ?: 0, 1)

    Text(
        text = selected?.let { tooltipFor(it) } ?: "Πάτα μια στήλη για λεπτομέρειες",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(values.size) {
                detectTapGestures { pos ->
                    val slot = size.width / values.size.toFloat()
                    val i = (pos.x / slot).toInt().coerceIn(0, values.lastIndex)
                    selected = if (selected == i) null else i
                }
            }
    ) {
        val slot = size.width / values.size
        val gap = 2.dp.toPx()
        val barW = (slot - gap).coerceAtLeast(1f)
        val radius = minOf(4.dp.toPx(), barW / 2)

        // Γραμμή βάσης
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())

        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val h = (v.toFloat() / maxV) * size.height
            val color = if (i == highlightIndex || i == selected) bar else barDim
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + gap / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(radius, radius)
            )
        }

        goal?.let {
            val y = size.height - (it.toFloat() / maxV) * size.height
            drawLine(
                goalColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        values.indices.forEach { i ->
            Text(
                labelFor(i),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f)
            )
        }
    }
    goal?.let {
        Text(
            "Διακεκομμένη γραμμή: στόχος ${fmt(it)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
