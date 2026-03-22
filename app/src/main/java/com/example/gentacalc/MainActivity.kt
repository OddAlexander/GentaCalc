package com.example.gentacalc

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    val gfrGroup: Int,
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

private fun addDaysFrom(base: Calendar, n: Int): Calendar =
    (base.clone() as Calendar).also { it.add(Calendar.DAY_OF_YEAR, n) }

fun calculate(
    gender: Gender,
    age: Int,
    weightKg: Double,
    heightCm: Double,
    dosingMgKg: Double,
    creatinine: Double,
    firstDoseHour: Int,
    firstDoseDay: Calendar
): GentaResult {
    val warnings = mutableListOf<String>()

    val hM = heightCm / 100.0
    val bmi = weightKg / (hM * hM)

    val ibw = if (gender == Gender.Mann)
        50.0 + 0.9 * (heightCm - 152)
    else
        45.5 + 0.9 * (heightCm - 152)

    val abw = ibw + 0.4 * (weightKg - ibw)
    val tbw = ibw * 1.249

    val weightUsed = if (ibw * 1.25 > weightKg) weightKg else maxOf(tbw, abw)

    val gfrRaw = ((140.0 - age) * weightUsed) / (0.814 * creatinine)
    val gfr = floor(if (gender == Gender.Mann) gfrRaw else gfrRaw * 0.85).toInt()

    val gfrGroup = when {
        gfr < 40 -> 1
        gfr < 60 -> 2
        else -> 3
    }

    val dose1Raw = dosingMgKg * weightUsed
    val dose1Mg: Int? = if (gfrGroup == 1) null
    else if (dose1Raw > 600) 600
    else roundTo40(dose1Raw)

    val baseDay = firstDoseDay.clone() as Calendar
    val tomorrow = addDaysFrom(baseDay, 1)
    val dayAfter = addDaysFrom(baseDay, 2)

    val dose1Time = if (gfrGroup == 1) "Anbefales ikke"
    else "${fmtDate(baseDay)} kl. $firstDoseHour:00"

    val correction = if (firstDoseHour < 12) 24 else 0
    val hSince = (firstDoseHour - 12) + correction
    val reductionPct = if (hSince > 3) hSince * 0.04167 else 0.0
    val nullDose = if (hSince > 19) 0 else 1

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
            dose2Mg = dose1Mg
            val d2Cal = (baseDay.clone() as Calendar).also {
                it.set(Calendar.HOUR_OF_DAY, firstDoseHour)
                it.add(Calendar.HOUR_OF_DAY, 36)
            }
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
            val d2Date = if (firstDoseHour > 7) tomorrow else baseDay
            dose2Time = if (nullDose == 0) "Gis ikke (for tidlig etter dose 1)"
            else "${fmtDate(d2Date)} kl. 12:00"

            dose3Mg = dose1Mg
            val d3Date = if (firstDoseHour > 7) dayAfter else tomorrow
            dose3Time = "${fmtDate(d3Date)} kl. 12:00"
        }
    }

    val levelOrderTime = when {
        gfrGroup == 1 -> "Anbefales ikke"
        gfrGroup == 2 -> "${fmtDate(addDaysFrom(baseDay, if (firstDoseHour > 11) 3 else 2))} kl. 08:00"
        else -> "${fmtDate(addDaysFrom(baseDay, 3))} kl. 08:00"
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
    var selectedDateMs by remember {
        mutableStateOf(
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var currentSheet by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current

    if (showDatePicker) {
        DisposableEffect(Unit) {
            val cal = Calendar.getInstance().also { it.timeInMillis = selectedDateMs }
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDateMs = Calendar.getInstance().apply {
                        set(year, month, day)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    showDatePicker = false
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            )
            dialog.setOnDismissListener { showDatePicker = false }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }

    if (showTimePicker) {
        DisposableEffect(Unit) {
            val dialog = TimePickerDialog(
                context,
                { _, hour, _ ->
                    firstDoseHour = hour
                    showTimePicker = false
                },
                firstDoseHour, 0, true
            )
            dialog.setOnDismissListener { showTimePicker = false }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }

    val result: GentaResult? = remember(gender, age, weight, height, dosing, creatinine, firstDoseHour, selectedDateMs) {
        val a = age.toIntOrNull() ?: return@remember null
        val w = weight.toDoubleOrNull() ?: return@remember null
        val h = height.toDoubleOrNull() ?: return@remember null
        val d = dosing.toDoubleOrNull() ?: return@remember null
        val c = creatinine.toDoubleOrNull() ?: return@remember null
        if (a <= 0 || w <= 0 || h <= 0 || d <= 0 || c <= 0) return@remember null
        val day = Calendar.getInstance().also { it.timeInMillis = selectedDateMs }
        calculate(gender, a, w, h, d, c, firstDoseHour, day)
    }

    val menuItems = listOf(
        "0-prove"        to stringResource(R.string.menu_0_prove),
        "8t-prove"       to stringResource(R.string.menu_8t_prove),
        "prosedyre"      to stringResource(R.string.menu_prosedyre),
        "referanser"     to stringResource(R.string.menu_referanser),
        "forutsetninger" to stringResource(R.string.menu_forutsetninger),
        "om-appen"       to stringResource(R.string.menu_om_appen)
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        currentSheet?.let { key -> menuItems.first { it.first == key }.second }
                            ?: stringResource(R.string.top_bar_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = if (currentSheet != null) 16.sp else 20.sp
                    )
                },
                navigationIcon = {
                    if (currentSheet != null) {
                        TextButton(onClick = { currentSheet = null }) {
                            Text(stringResource(R.string.back_button), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { showMenu = true }) {
                            Text("☰", color = MaterialTheme.colorScheme.onPrimary, fontSize = 22.sp)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            menuItems.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { currentSheet = key; showMenu = false }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (currentSheet != null) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoSheet(currentSheet!!)
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Contraindications ─────────────────────────────────────────
                CollapsibleCard(
                    title = stringResource(R.string.section_contraindications),
                    containerColor = Color(0xFFFFCDD2),
                    contentColor = Color(0xFF1A1A1A)
                ) {
                    Text(stringResource(R.string.contraindications_text), fontSize = 13.sp)
                }

                CollapsibleCard(
                    title = stringResource(R.string.section_relative_contraindications),
                    containerColor = Color(0xFFFFE0B2),
                    contentColor = Color(0xFF1A1A1A)
                ) {
                    Text(stringResource(R.string.relative_contraindications_text), fontSize = 13.sp)
                }

                // ── Patient inputs ────────────────────────────────────────────
                SectionCard(stringResource(R.string.section_patient_info), titleLarge = true) {
                    Text(stringResource(R.string.label_gender), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Gender.entries.forEach { g ->
                            FilterChip(
                                selected = gender == g,
                                onClick = { gender = g },
                                label = {
                                    Text(
                                        if (g == Gender.Mann) stringResource(R.string.gender_male)
                                        else stringResource(R.string.gender_female)
                                    )
                                }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(stringResource(R.string.label_age), age, Modifier.weight(1f)) { age = it }
                        NumField(stringResource(R.string.label_weight), weight, Modifier.weight(1f)) { weight = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(stringResource(R.string.label_height), height, Modifier.weight(1f)) { height = it }
                        NumField(stringResource(R.string.label_creatinine), creatinine, Modifier.weight(1f)) { creatinine = it }
                    }
                    NumField(stringResource(R.string.label_dosing_mgkg), dosing, Modifier.fillMaxWidth()) { dosing = it }

                    Spacer(Modifier.height(4.dp))
                    val dateLabel = remember(selectedDateMs) { dateFmt.format(Date(selectedDateMs)) }
                    Text(
                        stringResource(R.string.label_first_dose, dateLabel, firstDoseHour),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📅  $dateLabel")
                        }
                        Button(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🕐  kl. $firstDoseHour:00")
                        }
                    }
                }

                // ── Calculated values ─────────────────────────────────────────
                if (result != null) {
                    SectionCard(stringResource(R.string.section_calculated)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CalcChip(stringResource(R.string.label_bmi), "%.1f".format(result.bmi), Modifier.weight(1f))
                            CalcChip(
                                stringResource(R.string.label_gfr),
                                (if (result.gfr > 90) ">90" else "${result.gfr}") + " ml/min",
                                Modifier.weight(1f)
                            )
                            CalcChip(stringResource(R.string.label_weight_used), "%.1f kg".format(result.weightUsed), Modifier.weight(1f))
                        }
                    }

                    if (result.warnings.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.label_warning), fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                                result.warnings.forEach { w ->
                                    Text("⚠ $w", color = MaterialTheme.colorScheme.onTertiaryContainer, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    SectionCard(stringResource(R.string.section_dosing)) {
                        DoseRow(stringResource(R.string.label_dose_1), result.dose1Mg, result.dose1Time)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        DoseRow(stringResource(R.string.label_dose_2), result.dose2Mg, result.dose2Time)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        DoseRow(stringResource(R.string.label_dose_3), result.dose3Mg, result.dose3Time)
                    }

                    SectionCard(stringResource(R.string.section_level_order)) {
                        Text(result.levelOrderTime, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ─── Info sheets ──────────────────────────────────────────────────────────────

@Composable
private fun InfoSheet(sheet: String) {
    when (sheet) {
        "0-prove"        -> NullProveSheet()
        "8t-prove"       -> AchtTimerProveSheet()
        "prosedyre"      -> ProsedyreSheet()
        "referanser"     -> ReferanserSheet()
        "forutsetninger" -> ForutsetningerSheet()
        "om-appen"       -> OmAppenSheet()
    }
}

@Composable
private fun OmAppenSheet() {
    InfoSectionCard("Om appen") {
        InfoParagraph(
            "GentaCalc er utviklet av Odd Alexander Tellefsen.\n\n" +
            "Dette er en uoffisiell app basert på Gentamicin-prosedyren ved Sørlandet Sykehus, " +
            "opprinnelig utarbeidet av Runar Hamre.\n\n" +
            "Appen er laget i Android Studio Panda 2 (2025.3.2) med Claude Code."
        )
    }
}

@Composable
private fun ReferanserSheet() {
    InfoSectionCard("Referanser") {
        val refs = listOf(
            "Helsedirektoratet. Nasjonal faglig retningslinje for bruk av antibiotika i sykehus. helsedirektoratet.no",
            "IDSA. Guidance on the Treatment of Antimicrobial Resistant Gram-Negative Infections. idsociety.org",
            "Hsu CY et al. Community-based incidence of acute renal failure. Kidney Int. 2008",
            "UpToDate: Treatment of myasthenia gravis",
            "Therapeutic Guidelines Antibiotic (Australian)",
            "Medicin.dk: Aminoglykosider – systemisk brug",
            "Waagsbø B, Spigset O. Dosering av gentamicin/tobramycin. St. Olavs Hospital 2021",
            "Stenstad T, Finne SN. Behandling med aminoglykosider IV. Sykehuset i Vestfold",
            "Oliveira JFP et al. Acute kidney injury in patients with sepsis and septic shock. Antimicrob Agents Chemother. 2009",
            "Sojo-Dorado J, Rodríguez-Baño J. Gentamicin. Kucers' The Use of Antibiotics, 7. utg.",
            "UpToDate: Pathogenesis and prevention of aminoglycoside nephrotoxicity and ototoxicity",
            "Legget JE. Aminoglycosides. Mandell Douglas Bennet, 9. utg. 2020",
            "Stanford Antimicrobial Safety & Sustainability Program. Aminoglycoside dosing guideline",
            "Skogstrøm V. Aminocalc, Sørlandet Sykehus Arendal. Personlig meddelelse Olav Spigset; Lopez-Nouva et al. Aminoglycoside nephrotoxicity. Kidney Int. 2011"
        )
        refs.forEachIndexed { i, ref ->
            Text("${i + 1}. $ref", fontSize = 12.sp, lineHeight = 18.sp)
            if (i < refs.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ForutsetningerSheet() {
    InfoSectionCard("Formler og definisjoner") {
        Text("BMI", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph("Vekt / Høyde²")
        Spacer(Modifier.height(6.dp))
        Text("Ideell kroppsvekt (IBW)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph("Mann: 50 kg + 0,9 kg/cm over 152 cm\nKvinne: 45,5 kg + 0,9 kg/cm over 152 cm")
        Spacer(Modifier.height(6.dp))
        Text("Justert kroppsvekt (ABW)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph("IBW + 0,4 × (Kroppsvekt − IBW)\nBrukes når målt vekt > 125 % av IBW")
        Spacer(Modifier.height(6.dp))
        Text("GFR – Cockcroft-Gault", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph(
            "((140 − alder) × vekt) / (S-kreatinin [µmol/L] × 0,814)\n" +
            "Multipliseres med 0,85 for kvinner.\n" +
            "ABW brukes ved BMI > 30"
        )
    }

    InfoSectionCard("Doseringsregler") {
        SimpleTable(
            headers = listOf("GFR", "Doser", "Intervall"),
            rows = listOf(
                listOf("≥ 60", "3 doser", "24 timer"),
                listOf("40–59", "2 doser", "36 timer"),
                listOf("< 40", "Anbefales ikke", "–")
            )
        )
        Spacer(Modifier.height(8.dp))
        Text("Maksdose: 600 mg. Rundes til nærmeste 40 mg.", fontSize = 13.sp)
    }

    InfoSectionCard("Dosejustering dose 2 (GFR ≥ 60)") {
        InfoParagraph(
            "Basert på timer siden forrige dose (relativt til kl. 12:00):\n\n" +
            "• ≤ 4 timer siden: ingen reduksjon\n" +
            "• 5–20 timer siden: reduksjon 4,17 % per time\n" +
            "• > 20 timer siden: dose 2 utsettes til neste dag kl. 12:00"
        )
    }

    InfoSectionCard("Validering av inndata") {
        SimpleTable(
            headers = listOf("Felt", "Min", "Maks"),
            rows = listOf(
                listOf("Alder", "16 år", "110 år"),
                listOf("Vekt", "35 kg", "250 kg"),
                listOf("Høyde", "130 cm", "210 cm"),
                listOf("Dosering", "3 mg/kg", "7 mg/kg"),
                listOf("Kreatinin", "30 µmol/L", "1000 µmol/L")
            )
        )
    }
}

@Composable
private fun NullProveSheet() {
    InfoSectionCard("Tolking s-gentamicin, 0-prøve") {
        Text("Tas kl. 08:00 dagen før dose 4 (ca. 3 dager etter oppstart).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    InfoSectionCard("Steg 1 – Vurder alternativer") {
        InfoParagraph(
            "• Trenger pasienten fortsatt gramnegativ dekning / synergisme?\n" +
            "• Kan man justere basert på mikrobiologisvar?\n" +
            "• Kan man gå over til peroral antibiotika?"
        )
    }

    InfoSectionCard("Steg 2 – Vurder kontraindikasjoner") {
        Text("Absolutte stoppkriterier:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph(
            "• Habituell GFR < 60\n" +
            "• Alder > 80 år\n" +
            "• Alvorlig nedsatt hørsel eller vestibulær skade"
        )
        Spacer(Modifier.height(4.dp))
        Text("Nefrotoksisk belastning – pasienten:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph(
            "• Dehydrering, vedvarende hypotensjon, skrøpelighet (f.eks. sykehjemspasient)\n" +
            "• Diabetes, leversvikt, ubehandlet hypotyreose\n" +
            "• Ukorrigert alvorlig hyponatremi eller metabolsk acidose\n" +
            "• Enkeltnyret"
        )
        Spacer(Modifier.height(4.dp))
        Text("Nefrotoksisk belastning – legemidler:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph(
            "• Vankomycin, NSAIDs, slyngediuretika (særlig > 100 mg Furosemid)\n" +
            "• ACE-hemmere / AT2-blokkere, Amfotericin, røntgenkontrastmiddel under innleggelse"
        )
    }

    InfoSectionCard("Steg 3 – Tolkning av S-gentamicin 0-prøve") {
        SimpleTable(
            headers = listOf("S-genta", "Vurdering", "Tiltak"),
            rows = listOf(
                listOf(
                    "≤ 0,5 mg/L",
                    "Terapeutisk nivå",
                    "Fortsett; maks 5 mg/kg. Neste prøve om 48t. Ved behandling > 5 dager: konferér infeksjonsmedisin. Endokarditt: fortsett uendret; nivå 3×/uke."
                ),
                listOf(
                    "> 0,5 mg/L",
                    "Over referanse",
                    "Vurder seponering, forlenget intervall eller dosereduksjon. Endokarditt: reduser dose; følg kreatinin og nivå daglig; hvis > 0,5 flere dager, konferér infeksjonsmedisin."
                ),
                listOf(
                    "> 2,0 mg/L",
                    "Langt over referanse",
                    "Særlig høy nefrotoksisitetsrisiko. Seponer gentamicin. Endokarditt: hold neste dose; ny prøve neste dag; daglig kreatinin og nivå; vurder infeksjonsmedisin."
                )
            )
        )
    }
}

@Composable
private fun AchtTimerProveSheet() {
    InfoSectionCard("Tolkning s-gentamicin, 8t-prøve") {
        Text("Tas 8 timer etter 1. dose (eller rett før neste dose hvis < 8t til neste planlagte dose).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SimpleTable(
            headers = listOf("S-genta 8t", "Vurdering", "Tiltak"),
            rows = listOf(
                listOf(
                    "< 1,5 mg/L",
                    "Under terapeutisk nivå",
                    "Stabile pasienter: vurder økt dose, konferér infeksjonsmedisin dagtid. Septiske pasienter: bytt straks til alternativ (f.eks. cefotaksim eller pip/tazo), konferér infeksjonsmedisin dagtid."
                ),
                listOf(
                    "1,5–4,0 mg/L",
                    "Innenfor terapeutisk nivå",
                    "Uendret dosering."
                ),
                listOf(
                    "> 4,0 mg/L",
                    "Over terapeutisk nivå",
                    "Vurder seponering, forlenget intervall eller dosereduksjon. Konferér infeksjonsmedisin dagtid."
                )
            )
        )
    }

    InfoSectionCard("Nomogrammer ved avvikende prøvetidspunkt") {
        InfoParagraph(
            "Dersom 8t-prøven ble tatt på et annet tidspunkt, bruk nomogram:\n\n" +
            "• 7 mg/kg dosering: Hartford-nomogrammet\n" +
            "• 5 mg/kg dosering: Urban & Craig-nomogrammet"
        )
    }

    InfoSectionCard("Indikasjoner for 8t-prøve") {
        InfoParagraph(
            "• BMI > 30 med høy nefrotoksisk belastning\n" +
            "• Obligatorisk ved BMI > 35\n" +
            "• Endret muskelmasse\n" +
            "• Maternell sepsis"
        )
    }
}

@Composable
private fun ProsedyreSheet() {
    InfoSectionCard("Formål") {
        InfoParagraph(
            "Sikre målrettet og pasienttrygge bruk av gentamicin ved alvorlige infeksjoner. " +
            "Supplerer nasjonal antibiotikaretningslinje."
        )
    }

    InfoSectionCard("Ansvar") {
        Text("Rekvirerende lege:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph("Ansvarlig for indikasjoner, absolutte/relative kontraindikasjoner, dosering i inntil 3 dager og bestilling av serumnivåer.")
        Spacer(Modifier.height(4.dp))
        Text("Behandlende lege:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        InfoParagraph("Ansvarlig for oppfølging av nivåer og videre dosering.")
    }

    InfoSectionCard("Indikasjoner") {
        InfoParagraph(
            "• Gramnegativ dekning, kombinasjonsbehandling (E. coli, Klebsiella, Enterobacter)\n" +
            "• Gramnegativ dekning, monoterapi (febril UVI, antibiotikaresistent cystitt – én dose vanligvis tilstrekkelig)\n" +
            "• Grampositivt synergisme (endokarditt: stafs, enterokokker, streptokokker)"
        )
    }

    InfoSectionCard("Absolutte kontraindikasjoner") {
        InfoParagraph(
            "• Kronisk nyresvikt GFR < 30–40 (app bruker 40 som grense)\n" +
            "• Fulminant flerorgansvikt\n" +
            "• Myastenia gravis\n" +
            "• Tidligere aminoglykosidindusert hørsels- eller vestibularskade hos pasient eller førstegradsslektning"
        )
    }

    InfoSectionCard("Relative kontraindikasjoner (én dose kan gis)") {
        InfoParagraph(
            "• Aminoglykosider siste måned\n" +
            "• Cisplatin siste 2 måneder\n" +
            "• Høydose metotreksat / ifosfamid\n" +
            "• Etter nyretransplantasjon\n" +
            "• Massiv ascites\n" +
            "• Myelomatose\n" +
            "• Graviditet"
        )
    }

    InfoSectionCard("Nefrotoksisitet") {
        InfoParagraph(
            "Akutt nyresvikt inntreffer vanligvis bare etter > 5–7 dager; debut typisk 7–10 dager etter oppstart. " +
            "Vesentlig kreatininstigning de første dagene = se etter annen årsak.\n\n" +
            "Ved akutt nyresvikt kan 1. dose overveies. Ikke vent på kreatinin hos ustabile pasienter. " +
            "Bruk habituelt kreatinin dersom AKI er mistenkt."
        )
    }

    InfoSectionCard("Oto-/vestibulotoksisitet") {
        InfoParagraph(
            "Mest relevant ved endokardittbehandling. Risikofaktorer som ved nefrotoksisitet.\n\n" +
            "Symptomer:\n" +
            "• Vestibulær: oscillopsi, postural ustabilitet, ustø gange\n" +
            "• Otisk: hørselstap (høye frekvenser først), tinnitus"
        )
    }

    InfoSectionCard("Dosering via Gentacalc") {
        InfoParagraph(
            "Bruker justert kroppsvekt for overvektige pasienter; GFR etter Cockroft-Gault.\n\n" +
            "BMI > 35: 8t-serumnivå er obligatorisk.\n\n" +
            "Anbefaler dosering i 3 dager; behandling > 5 dager skal diskuteres med infeksjonsmedisin."
        )
    }

    InfoSectionCard("Serumnivåtolkning") {
        InfoParagraph(
            "0-prøve: akkumulasjons-/toksisitetsovervåking, tas kl. 08:00 før dose 4.\n\n" +
            "8t-prøve: dose-/intervalloptimalisering, tas 8t etter 1. dose (eller rett før neste dose " +
            "hvis < 8t til neste planlagte dose).\n\n" +
            "Indikasjoner for 8t-prøve: BMI > 30 med høy nefrotoksisk belastning (obligatorisk ved BMI > 35), " +
            "endret muskelmasse, maternell sepsis."
        )
    }
}

// ─── Composable helpers ───────────────────────────────────────────────────────

@Composable
private fun InfoSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun InfoParagraph(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable
private fun SimpleTable(headers: List<String>, rows: List<List<String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Header row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            headers.forEachIndexed { i, h ->
                val w = if (i == 0) 0.22f else if (i == 1) 0.28f else 0.5f
                Text(
                    h,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(w)
                )
            }
        }
        HorizontalDivider()
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, cell ->
                    val w = if (i == 0) 0.22f else if (i == 1) 0.28f else 0.5f
                    Text(cell, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(w))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = contentColor)
                Text(if (expanded) "▲" else "▼", color = contentColor, fontSize = 12.sp)
            }
            if (expanded) { content() }
        }
    }
}

@Composable
private fun SectionCard(title: String, titleLarge: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = if (titleLarge) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
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
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                Text("$mg mg", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            } else {
                Text("–", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(time, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 64.dp))
    }
}
