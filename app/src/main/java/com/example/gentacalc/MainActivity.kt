package com.example.gentacalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gentacalc.ui.theme.GentaCalcTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GentaCalcTheme {
                GentaCalcApp()
            }
        }
    }
}

// ─── Model ────────────────────────────────────────────────────────────────────

enum class Gender { Mann, Kvinne }

data class GentaResult(
    val bmi: Double,
    val ibw: Double,
    val abw: Double,
    val weightUsed: Double,
    val gfr: Int,
    val gfrGroup: Int,   // 1=<40  2=40-59  3=>=60
    val dose1Mg: Int?,
    val dose1Time: String,
    val dose2Mg: Int?,
    val dose2Time: String,
    val dose3Mg: Int?,
    val dose3Time: String,
    val levelOrderTime: String,
    val warnings: List<String>
)

// ─── Calculation ──────────────────────────────────────────────────────────────

private fun roundTo40(v: Double): Int = (Math.round(v / 40.0) * 40).toInt()

private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

private fun fmtDate(cal: Calendar): String = dateFmt.format(cal.time)

private fun addDays(n: Int): Calendar =
    Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, n) }

fun calculate(
    gender: Gender,
    age: Int,
    weightKg: Double,
    heightCm: Double,
    dosingMgKg: Double,
    creatinine: Double,
    firstDoseHour: Int
): GentaResult {
    val warnings = mutableListOf<String>()

    val hM = heightCm / 100.0
    val bmi = weightKg / (hM * hM)

    val ibw = if (gender == Gender.Mann)
        50.0 + 0.9 * (heightCm - 152)
    else
        45.5 + 0.9 * (heightCm - 152)

    val abw = ibw + 0.4 * (weightKg - ibw)
    val tbw = ibw * 1.249   // TBW × 1.249

    // Weight used for both dosing and GFR (Cockroft-Gault)
    // IF IBW×1.25 > actual weight → use actual weight
    // ELSE use max(TBW×1.249, ABW)
    val weightUsed = if (ibw * 1.25 > weightKg) weightKg else maxOf(tbw, abw)

    // GFR (Cockroft-Gault)
    val gfrRaw = ((140.0 - age) * weightUsed) / (0.814 * creatinine)
    val gfr = floor(if (gender == Gender.Mann) gfrRaw else gfrRaw * 0.85).toInt()

    val gfrGroup = when {
        gfr < 40 -> 1
        gfr < 60 -> 2
        else -> 3
    }

    // Dose 1
    val dose1Raw = dosingMgKg * weightUsed
    val dose1Mg: Int? = if (gfrGroup == 1) null
    else if (dose1Raw > 600) 600
    else roundTo40(dose1Raw)

    val today = Calendar.getInstance()
    val tomorrow = addDays(1)
    val dayAfter = addDays(2)

    val dose1Time = if (gfrGroup == 1) "Anbefales ikke"
    else "Nå – ${fmtDate(today)} kl. $firstDoseHour:00"

    // Dose reduction for dose 2 (accounts for time of day relative to noon)
    val correction = if (firstDoseHour < 12) 24 else 0
    val hSince = (firstDoseHour - 12) + correction
    val reductionPct = if (hSince > 3) hSince * 0.04167 else 0.0
    val nullDose = if (hSince > 19) 0 else 1  // skip dose 2 if given too early in morning

    val dose2Mg: Int?
    val dose2Time: String
    val dose3Mg: Int?
    val dose3Time: String

    when (gfrGroup) {
        1 -> {
            dose2Mg = null; dose2Time = "Anbefales ikke"
            dose3Mg = null; dose3Time = "Anbefales ikke"
        }
        2 -> {
            // Same dose as dose 1, given 36h after dose 1
            dose2Mg = dose1Mg
            val d2Cal = Calendar.getInstance().also { it.add(Calendar.HOUR_OF_DAY, 36) }
            dose2Time = "36t etter dose 1 – ${fmtDate(d2Cal)} kl. ${d2Cal.get(Calendar.HOUR_OF_DAY)}:00"
            dose3Mg = null
            dose3Time = "Tredje dose skal ikke gis"
        }
        else -> {
            val uncapped = if (dose1Raw > 599) 600.0 else dose1Raw
            val d2Raw = uncapped * (1 - reductionPct) * nullDose
            dose2Mg = if (nullDose == 0) null
            else if (d2Raw > 600) 600
            else roundTo40(d2Raw)
            val d2Date = if (firstDoseHour > 7) tomorrow else today
            dose2Time = if (nullDose == 0) "Gis ikke (for tidlig etter dose 1)"
            else "${fmtDate(d2Date)} kl. 12:00"

            dose3Mg = dose1Mg
            val d3Date = if (firstDoseHour > 7) dayAfter else tomorrow
            dose3Time = "${fmtDate(d3Date)} kl. 12:00"
        }
    }

    // S-Gentamicin trough level order time
    val levelOrderTime = when {
        gfrGroup == 1 -> "Anbefales ikke"
        gfrGroup == 2 -> "${fmtDate(if (firstDoseHour > 11) addDays(2) else addDays(1))} kl. 08:00"
        else -> "${fmtDate(addDays(3))} kl. 08:00"
    }

    if (gfrGroup == 1)   warnings.add("GFR < 40: Gentamicin anbefales ikke")
    if (bmi > 35)        warnings.add("BMI > 35: 8-timersprøve er obligatorisk")
    if (dose1Raw > 600)  warnings.add("Beregnet dose > 600 mg – begrenset til 600 mg")
    if (age > 80)        warnings.add("Alder > 80 år: vurder dosejustering")

    return GentaResult(
        bmi, ibw, abw, weightUsed, gfr, gfrGroup,
        dose1Mg, dose1Time,
        dose2Mg, dose2Time,
        dose3Mg, dose3Time,
        levelOrderTime, warnings
    )
}

// ─── UI ───────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GentaCalcApp() {
    var gender by remember { mutableStateOf(Gender.Mann) }
    var age by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var dosing by remember { mutableStateOf("5") }
    var creatinine by remember { mutableStateOf("") }
    var firstDoseHour by remember { mutableStateOf(8) }

    val result: GentaResult? = remember(gender, age, weight, height, dosing, creatinine, firstDoseHour) {
        val a = age.toIntOrNull() ?: return@remember null
        val w = weight.toDoubleOrNull() ?: return@remember null
        val h = height.toDoubleOrNull() ?: return@remember null
        val d = dosing.toDoubleOrNull() ?: return@remember null
        val c = creatinine.toDoubleOrNull() ?: return@remember null
        if (a <= 0 || w <= 0 || h <= 0 || d <= 0 || c <= 0) return@remember null
        calculate(gender, a, w, h, d, c, firstDoseHour)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gentacalc", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Contraindications ─────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Kontraindikasjoner for bruk av Gentamicin",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "• Kronisk nyresvikt, GFR < 40 ml/min\n" +
                        "• Fulminant flerorgansvikt\n" +
                        "• Myastenia gravis\n" +
                        "• Gravide i 2. eller 3. trimester",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }

            // ── Patient inputs ────────────────────────────────────────────────
            SectionCard("Pasientinformasjon") {
                Text("Kjønn", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gender.entries.forEach { g ->
                        FilterChip(
                            selected = gender == g,
                            onClick = { gender = g },
                            label = { Text(g.name) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Alder (år)", age, Modifier.weight(1f)) { age = it }
                    NumField("Vekt (kg)", weight, Modifier.weight(1f)) { weight = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Høyde (cm)", height, Modifier.weight(1f)) { height = it }
                    NumField("Kreatinin (µmol/L)", creatinine, Modifier.weight(1f)) { creatinine = it }
                }
                NumField("Dosering (mg/kg)", dosing, Modifier.fillMaxWidth()) { dosing = it }

                Spacer(Modifier.height(4.dp))
                Text(
                    "1. dose gis kl. $firstDoseHour:00",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = firstDoseHour.toFloat(),
                    onValueChange = { firstDoseHour = it.toInt() },
                    valueRange = 0f..23f,
                    steps = 22
                )
            }

            // ── Calculated values ─────────────────────────────────────────────
            if (result != null) {
                SectionCard("Beregnet") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalcChip("BMI", "%.1f".format(result.bmi), Modifier.weight(1f))
                        CalcChip(
                            "GFR",
                            (if (result.gfr > 90) ">90" else "${result.gfr}") + " ml/min",
                            Modifier.weight(1f)
                        )
                        CalcChip("Vekt brukt", "%.1f kg".format(result.weightUsed), Modifier.weight(1f))
                    }
                }

                // ── Warnings ──────────────────────────────────────────────────
                if (result.warnings.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Advarsel",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            result.warnings.forEach { w ->
                                Text(
                                    "⚠ $w",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // ── Dosing ────────────────────────────────────────────────────
                SectionCard("Dosering av Gentamicin") {
                    DoseRow("Dose 1", result.dose1Mg, result.dose1Time)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    DoseRow("Dose 2", result.dose2Mg, result.dose2Time)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    DoseRow("Dose 3", result.dose3Mg, result.dose3Time)
                }

                // ── S-Gentamicin level ────────────────────────────────────────
                SectionCard("Bestill s-gentamicin / Vurder videre bruk") {
                    Text(result.levelOrderTime, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─── Composable helpers ───────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun CalcChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun DoseRow(label: String, mg: Int?, time: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
            if (mg != null) {
                Text(
                    "$mg mg",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text("–", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(
            time,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 64.dp)
        )
    }
}
