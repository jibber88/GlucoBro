package com.carl.glucobro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val GlucoRed = Color(0xFFD62828)
private val GlucoGreen = Color(0xFF159B4B)
private val GlucoBlue = Color(0xFF2369D2)
private val SoftBackground = Color(0xFFF6F7F9)
private val SoftGrid = Color(0xFFD5D8DC)
private val MutedText = Color(0xFF777777)

private enum class AppPage(val label: String, val glyph: String) {
    HOME("Home", "⌂"),
    GRAPH("Graph", "⌁"),
    STATS("Stats", "%"),
    SETTINGS("Settings", "⚙")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GlucoBroApp() }
    }
}

@Composable
fun GlucoBroApp() {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    val api = remember { LibreApi(context) }
    var appReady by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (preferences.isLoggedIn()) {
            val token = preferences.getAuthToken()
            val userId = preferences.getUserId()
            if (token != null && userId != null) {
                api.restoreSession(token, userId)
                loggedIn = true
            }
        }
        appReady = true
    }

    LaunchedEffect(appReady, loggedIn) {
        if (appReady && loggedIn && preferences.isLoggedIn()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlucosePollingService::class.java)
            )
        }
    }

    if (!appReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (loggedIn) {
        GlucoseScreen(api, preferences) { loggedIn = false }
    } else {
        LoginScreen(api, preferences) { loggedIn = true }
    }
}

@Composable
fun LoginScreen(api: LibreApi, preferences: Preferences, onLoginSuccess: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberLogin by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.glucobro_logo),
            contentDescription = "GlucoBro",
            modifier = Modifier.size(82.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("GlucoBro", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Libre Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(showPassword, { showPassword = it }); Text("Show password")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rememberLogin, { rememberLogin = it }); Text("Remember me")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            when {
                username.isBlank() -> status = "Please enter your Libre username."
                password.isBlank() -> status = "Please enter your password."
                else -> {
                    status = "Connecting..."
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            try { api.login(username, password) } catch (e: Exception) { e.printStackTrace(); false }
                        }
                        if (success) {
                            if (rememberLogin) {
                                val token = api.authToken
                                val userId = api.userId
                                if (token != null && userId != null) preferences.saveSession(username, token, userId)
                            }
                            onLoginSuccess()
                        } else status = "Login failed"
                    }
                }
            }
        }) { Text("Connect") }
        Spacer(Modifier.height(20.dp)); Text(status)
    }
}

@Composable
fun GlucoseScreen(api: LibreApi, preferences: Preferences, onLoggedOut: () -> Unit) {
    var glucose by remember { mutableStateOf<GlucoseReading?>(api.getCachedGlucose()) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var currentPage by remember { mutableStateOf(AppPage.HOME) }
    var targetLow by remember { mutableDoubleStateOf(4.0) }
    var targetHigh by remember { mutableDoubleStateOf(10.0) }

    LaunchedEffect(Unit) {
        targetLow = preferences.getTargetLow()
        targetHigh = preferences.getTargetHigh()
        while (true) {
            api.getCachedGlucose()?.let { glucose = it }
            tick = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SoftBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = Color.White,
                tonalElevation = 0.dp
            ) {
                AppPage.entries.forEach { page ->
                    val selected = currentPage == page
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentPage = page },
                        icon = {
                            Text(
                                page.glyph,
                                fontSize = if (page == AppPage.SETTINGS) 25.sp else 24.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        label = { Text(page.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GlucoGreen,
                            selectedTextColor = GlucoGreen,
                            indicatorColor = Color(0xFFEAF7EF)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            AppHeader(glucose, tick)

            when (currentPage) {
                AppPage.HOME -> HomePage(glucose, tick, targetLow, targetHigh)
                AppPage.GRAPH -> GraphPage(glucose, targetLow, targetHigh)
                AppPage.STATS -> StatsPage(glucose, tick, targetLow, targetHigh)
                AppPage.SETTINGS -> SettingsPage(
                    preferences = preferences,
                    targetLow = targetLow,
                    targetHigh = targetHigh,
                    onTargetRangeChanged = { low, high ->
                        targetLow = low
                        targetHigh = high
                    },
                    onLoggedOut = onLoggedOut
                )
            }
        }
    }
}

@Composable
private fun AppHeader(reading: GlucoseReading?, tick: Long) {
    val ageMinutes = reading?.let { readingAgeMinutes(it.timestamp, tick) }
    val statusText = when {
        reading == null -> "WAITING"
        ageMinutes == null -> "LIVE"
        ageMinutes >= 5L -> "STALE"
        else -> "LIVE"
    }
    val statusColor = when (statusText) {
        "LIVE" -> GlucoGreen
        "STALE" -> GlucoRed
        else -> MutedText
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.glucobro_logo),
                contentDescription = "GlucoBro logo",
                modifier = Modifier.size(54.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("GlucoBro", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Libre glucose monitor", fontSize = 13.sp, color = MutedText)
            }
        }
        Surface(shape = RoundedCornerShape(50), color = Color.White) {
            Text(
                statusText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HomePage(reading: GlucoseReading?, tick: Long, targetLow: Double, targetHigh: Double) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CurrentGlucoseCard(reading, tick, targetLow, targetHigh)
        Spacer(Modifier.height(16.dp))
        TimeInRangeCard(reading?.history24h.orEmpty(), compact = true, targetLow = targetLow, targetHigh = targetHigh)
        Spacer(Modifier.height(14.dp))
        Home24HourSummary(reading?.history24h.orEmpty(), targetLow, targetHigh)
        Spacer(Modifier.height(14.dp))
        HomeSensorRemaining(reading, tick)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomeSensorRemaining(reading: GlucoseReading?, tick: Long) {
    val activated = reading?.sensorActivatedAtEpochSeconds
    val remaining = sensorRemainingText(activated, tick)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Sensor  •  ", fontSize = 12.sp, color = MutedText)
        Text(
            remaining,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (activated != null) GlucoGreen else MutedText
        )
    }
}

@Composable
private fun Home24HourSummary(points: List<GraphPoint>, targetLow: Double, targetHigh: Double) {
    val stats = remember(points, targetLow, targetHigh) { calculateTir(points, targetLow, targetHigh) }
    val average = remember(points) { if (points.isNotEmpty()) points.map { it.valueMmol }.average() else null }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniMetricCard(
            title = "Average (24h)",
            value = average?.let { String.format(Locale.UK, "%.1f", it) } ?: "—",
            subtitle = "mmol/L",
            valueColor = GlucoGreen,
            modifier = Modifier.weight(1f)
        )
        MiniMetricCard(
            title = "Low (24h)",
            value = "${stats.lowPct}%",
            subtitle = durationText(stats.lowMinutes),
            valueColor = GlucoRed,
            modifier = Modifier.weight(1f)
        )
        MiniMetricCard(
            title = "High (24h)",
            value = "${stats.highPct}%",
            subtitle = durationText(stats.highMinutes),
            valueColor = GlucoBlue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CurrentGlucoseCard(reading: GlucoseReading?, tick: Long, targetLow: Double, targetHigh: Double) {
    val glucoseColor = reading?.valueMmol?.let { glucoseColour(it, targetLow, targetHigh) } ?: MaterialTheme.colorScheme.onSurface
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (reading == null) {
                Spacer(Modifier.height(30.dp)); CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Waiting for first glucose reading…")
                Spacer(Modifier.height(30.dp))
            } else {
                Text("CURRENT GLUCOSE", fontSize = 12.sp, color = MutedText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.UK, "%.1f", reading.valueMmol), fontSize = 78.sp, fontWeight = FontWeight.Bold, color = glucoseColor)
                    Spacer(Modifier.width(12.dp))
                    Text(reading.trendArrow, fontSize = 50.sp, fontWeight = FontWeight.Medium, color = glucoseColor)
                }
                Text("mmol/L", fontSize = 18.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(reading.deltaMmol?.let { String.format(Locale.UK, "%+.1f mmol/L", it) } ?: "Awaiting delta", fontSize = 19.sp, fontWeight = FontWeight.Medium)
                    Text("  •  ${updatedText(reading.timestamp, tick)}", fontSize = 16.sp, color = MutedText)
                }
            }
        }
    }
}

@Composable
private fun GraphPage(reading: GlucoseReading?, targetLow: Double, targetHigh: Double) {
    val sourcePoints = reading?.history24h.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        listOf(6, 12, 24).forEach { hours ->
            val visiblePoints = remember(sourcePoints, hours) { filterPointsByHours(sourcePoints, hours) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${hours} hour", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Last ${hours}h", fontSize = 12.sp, color = MutedText)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (visiblePoints.size >= 2) {
                        GlucoseGraph(
                            points = visiblePoints,
                            hours = hours,
                            targetLow = targetLow,
                            targetHigh = targetHigh,
                            modifier = Modifier.fillMaxWidth().height(155.dp)
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(155.dp), contentAlignment = Alignment.Center) {
                            Text("Waiting for glucose history…", color = MutedText)
                        }
                    }

                    if (hours == 24) {
                        Spacer(Modifier.height(10.dp))
                        val collected = historySpanMinutes(visiblePoints, 24 * 60)
                        if (collected < 23 * 60 + 45) {
                            Surface(
                                color = Color(0xFFF0F6FF),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        "${durationText(collected)} of 24h history collected",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2B5F9E)
                                    )
                                    Text(
                                        "The blank area is time where GlucoBro has no stored data yet.",
                                        fontSize = 11.sp,
                                        color = MutedText
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatsPage(reading: GlucoseReading?, tick: Long, targetLow: Double, targetHigh: Double) {
    val history = reading?.history7d.orEmpty()
    val windows = listOf(24 to "24 Hours", 72 to "3 Days", 168 to "7 Days")
    var selectedWindowIndex by remember { mutableIntStateOf(0) }
    var showStatsInfo by remember { mutableStateOf(false) }

    val totalHistoryMinutes = historySpanMinutes(filterPointsByHours(history, 168), 168 * 60)

    if (showStatsInfo) {
        AlertDialog(
            onDismissRequest = { showStatsInfo = false },
            title = { Text("Stats history") },
            text = {
                Text(
                    "GlucoBro currently has ${durationText(totalHistoryMinutes)} of glucose data stored. " +
                        "The 24-hour view builds as data is collected. The 3-day and 7-day stats will become available automatically once enough history has been collected for each full period."
                )
            },
            confirmButton = {
                TextButton(onClick = { showStatsInfo = false }) { Text("Got it") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Time in Range",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            )
            IconButton(onClick = { showStatsInfo = true }) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MutedText)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "i",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MutedText
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val (hours, label) = windows[selectedWindowIndex]
        val points = remember(history, hours) { filterPointsByHours(history, hours) }
        val stats = remember(points, targetLow, targetHigh) { calculateTir(points, targetLow, targetHigh) }
        val coverageMinutes = historySpanMinutes(points, hours * 60)
        val requireFullHistory = hours > 24
        val available = !requireFullHistory || coverageMinutes >= (hours * 60) - 15

        TirCarouselCard(
            label = label,
            stats = stats,
            coverageMinutes = coverageMinutes,
            windowMinutes = hours * 60,
            available = available,
            selectedIndex = selectedWindowIndex,
            itemCount = windows.size,
            onClick = { selectedWindowIndex = (selectedWindowIndex + 1) % windows.size }
        )

        Spacer(Modifier.height(14.dp))

        Text("Glucose summary", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        GlucoseSummaryCards(points = points, available = available)

        Spacer(Modifier.height(14.dp))
        Text("Range breakdown", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        RangeBreakdownCard(stats = stats, available = available, targetLow = targetLow, targetHigh = targetHigh)

        Spacer(Modifier.height(14.dp))
        SensorInfoCard(reading, tick)
        Spacer(Modifier.height(18.dp))
    }
}


@Composable
private fun GlucoseSummaryCards(points: List<GraphPoint>, available: Boolean) {
    val values = points.map { it.valueMmol }.filter { it.isFinite() }
    val average = values.takeIf { it.isNotEmpty() }?.average()
    val lowest = values.minOrNull()
    val highest = values.maxOrNull()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniMetricCard(
            title = "Average",
            value = if (available && average != null) String.format(Locale.UK, "%.1f", average) else "—",
            subtitle = "mmol/L",
            valueColor = if (available) GlucoGreen else Color(0xFFAAAAAA),
            modifier = Modifier.weight(1f)
        )
        MiniMetricCard(
            title = "Lowest",
            value = if (available && lowest != null) String.format(Locale.UK, "%.1f", lowest) else "—",
            subtitle = "mmol/L",
            valueColor = if (available) GlucoRed else Color(0xFFAAAAAA),
            modifier = Modifier.weight(1f)
        )
        MiniMetricCard(
            title = "Highest",
            value = if (available && highest != null) String.format(Locale.UK, "%.1f", highest) else "—",
            subtitle = "mmol/L",
            valueColor = if (available) GlucoBlue else Color(0xFFAAAAAA),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RangeBreakdownCard(stats: TirStats, available: Boolean, targetLow: Double, targetHigh: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (available) Color.White else Color(0xFFE9EAEC))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            RangeBreakdownRow(
                color = GlucoRed,
                label = "Low",
                threshold = String.format(Locale.UK, "< %.1f mmol/L", targetLow),
                percent = if (available) "${stats.lowPct}%" else "—",
                duration = if (available) durationText(stats.lowMinutes) else "Collecting"
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = SoftGrid)
            RangeBreakdownRow(
                color = GlucoGreen,
                label = "In range",
                threshold = String.format(Locale.UK, "%.1f–%.1f mmol/L", targetLow, targetHigh),
                percent = if (available) "${stats.inRangePct}%" else "—",
                duration = if (available) durationText(stats.inRangeMinutes) else "Collecting"
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = SoftGrid)
            RangeBreakdownRow(
                color = GlucoBlue,
                label = "High",
                threshold = String.format(Locale.UK, "> %.1f mmol/L", targetHigh),
                percent = if (available) "${stats.highPct}%" else "—",
                duration = if (available) durationText(stats.highMinutes) else "Collecting"
            )
        }
    }
}

@Composable
private fun RangeBreakdownRow(color: Color, label: String, threshold: String, percent: String, duration: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(threshold, fontSize = 10.sp, color = MutedText)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(percent, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(duration, fontSize = 10.sp, color = MutedText)
        }
    }
}

@Composable
private fun TirCarouselCard(
    label: String,
    stats: TirStats,
    coverageMinutes: Int,
    windowMinutes: Int,
    available: Boolean,
    selectedIndex: Int,
    itemCount: Int,
    onClick: () -> Unit
) {
    val cardColor = if (available) Color.White else Color(0xFFE9EAEC)
    val titleColor = if (available) Color.Black else Color(0xFF9A9A9A)
    val valueColor = if (available) GlucoGreen else Color(0xFFAAAAAA)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(205.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = titleColor)
            Spacer(Modifier.height(8.dp))
            Text(
                if (available) "${stats.inRangePct}%" else "—",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Text(if (available) "In range" else "Collecting", fontSize = 14.sp, color = if (available) MutedText else Color(0xFF999999))
            Spacer(Modifier.height(12.dp))

            if (available) {
                TirBar(stats)
            } else {
                Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFFD4D5D7), RoundedCornerShape(5.dp)))
            }

            Spacer(Modifier.height(9.dp))
            val full = coverageMinutes >= windowMinutes - 15
            Text(
                when {
                    full -> "Full history"
                    available -> durationText(coverageMinutes)
                    else -> "${durationText(coverageMinutes)} collected"
                },
                fontSize = 11.sp,
                color = if (available) MutedText else Color(0xFF999999),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(itemCount) { index ->
                    Box(
                        Modifier
                            .size(if (index == selectedIndex) 8.dp else 7.dp)
                            .background(
                                if (index == selectedIndex) GlucoGreen else Color(0xFFD0D3D7),
                                RoundedCornerShape(50)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniTirCard(
    label: String,
    stats: TirStats,
    coverageMinutes: Int,
    windowMinutes: Int,
    available: Boolean,
    modifier: Modifier = Modifier
) {
    val cardColor = if (available) Color.White else Color(0xFFE9EAEC)
    val titleColor = if (available) Color.Black else Color(0xFF9A9A9A)
    val valueColor = if (available) GlucoGreen else Color(0xFFAAAAAA)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = titleColor)
            Spacer(Modifier.height(7.dp))
            Text(
                if (available) "${stats.inRangePct}%" else "—",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Text(if (available) "In range" else "Collecting", fontSize = 11.sp, color = if (available) MutedText else Color(0xFF999999))
            Spacer(Modifier.height(10.dp))

            if (available) {
                TirBar(stats)
            } else {
                Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFFD4D5D7), RoundedCornerShape(5.dp)))
            }

            Spacer(Modifier.height(7.dp))
            val full = coverageMinutes >= windowMinutes - 15
            Text(
                when {
                    full -> "Full history"
                    available -> durationText(coverageMinutes)
                    else -> "${durationText(coverageMinutes)} collected"
                },
                fontSize = 9.sp,
                color = if (available) MutedText else Color(0xFF999999),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MiniMetricCard(
    title: String,
    value: String,
    subtitle: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = valueColor, textAlign = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 10.sp, color = MutedText, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TirBar(stats: TirStats) {
    Row(
        Modifier.fillMaxWidth().height(7.dp).background(SoftGrid, RoundedCornerShape(5.dp))
    ) {
        if (stats.inRangePct > 0) Box(Modifier.weight(stats.inRangePct.toFloat()).fillMaxHeight().background(GlucoGreen))
        if (stats.highPct > 0) Box(Modifier.weight(stats.highPct.toFloat()).fillMaxHeight().background(GlucoBlue))
        if (stats.lowPct > 0) Box(Modifier.weight(stats.lowPct.toFloat()).fillMaxHeight().background(GlucoRed))
        val missing = (100 - stats.inRangePct - stats.highPct - stats.lowPct).coerceAtLeast(0)
        if (missing > 0) Box(Modifier.weight(missing.toFloat()).fillMaxHeight())
    }
}

@Composable
private fun SensorInfoCard(reading: GlucoseReading?, tick: Long) {
    val serial = reading?.sensorSerial
    val activated = reading?.sensorActivatedAtEpochSeconds
    val remainingText = sensorRemainingText(activated, tick)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(50),
                color = Color(0xFFEAF7EF)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("◉", color = GlucoGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Libre sensor", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (serial.isNullOrBlank()) "Serial number unavailable" else "SN: $serial",
                    fontSize = 11.sp,
                    color = MutedText
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                remainingText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (activated != null) GlucoGreen else MutedText,
                textAlign = TextAlign.End
            )
        }
    }
}

private fun sensorRemainingText(activatedAtEpochSeconds: Long?, nowMs: Long): String {
    if (activatedAtEpochSeconds == null || activatedAtEpochSeconds <= 0L) return "Remaining unavailable"

    // Libre 2 Plus sensors are specified for 15 days of wear. The API's sensor
    // activation timestamp lets us calculate an always-current local countdown.
    val sensorLifeMs = 15L * 24L * 60L * 60L * 1000L
    val activatedMs = activatedAtEpochSeconds * 1000L
    val remainingMs = (activatedMs + sensorLifeMs - nowMs).coerceAtLeast(0L)
    if (remainingMs == 0L) return "Expired"

    val totalHours = (remainingMs + 3_599_999L) / 3_600_000L
    val days = totalHours / 24L
    val hours = totalHours % 24L
    return when {
        days > 0 && hours > 0 -> "${days}d ${hours}h remaining"
        days > 0 -> "${days}d remaining"
        else -> "${hours}h remaining"
    }
}

@Composable
private fun StatMetricCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun FixedAlarmSettingRow(
    title: String,
    value: String,
    enabled: Boolean,
    locked: Boolean,
    accentColor: Color,
    subtitle: String,
    subtitleWarning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onTest: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF8F9FA)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(8.dp).background(accentColor, RoundedCornerShape(50))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    suffix = { Text("mmol/L", fontSize = 10.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = enabled && !locked,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onTest,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp)
                ) { Text("Test", fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !locked
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (locked) "ACTIVE ALERT • Locked" else subtitle,
                fontSize = 11.sp,
                color = if (locked || subtitleWarning) GlucoRed else MutedText,
                fontWeight = if (locked || subtitleWarning) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun SensorFaultOverrideHoldButton(onHoldComplete: () -> Unit) {
    var holding by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        coroutineScope {
                            val holdJob = launch {
                                delay(5_000L)
                                onHoldComplete()
                            }
                            tryAwaitRelease()
                            holdJob.cancel()
                        }
                        holding = false
                    }
                )
            },
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF1F1)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (holding) "Keep holding…" else "Sensor fault override",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = GlucoRed
            )
            Text(
                if (holding) "Hold for 5 seconds" else "Press and hold for 5 seconds to unlock emergency disable",
                fontSize = 11.sp,
                color = MutedText,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AlarmSettingRow(
    title: String,
    enabled: Boolean,
    value: String,
    accentColor: Color,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onTest: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF8F9FA)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(8.dp).background(accentColor, RoundedCornerShape(50))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                suffix = { Text("mmol/L", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.width(126.dp)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTest,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp)
            ) { Text("Test", fontSize = 12.sp) }
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun SettingsPage(
    preferences: Preferences,
    targetLow: Double,
    targetHigh: Double,
    onTargetRangeChanged: (Double, Double) -> Unit,
    onLoggedOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lowText by remember(targetLow) { mutableStateOf(String.format(Locale.UK, "%.1f", targetLow)) }
    var highText by remember(targetHigh) { mutableStateOf(String.format(Locale.UK, "%.1f", targetHigh)) }
    var rangeMessage by remember { mutableStateOf("") }

    var alarmSettingsLoaded by remember { mutableStateOf(false) }
    var urgentLowAlarmEnabled by remember { mutableStateOf(true) }
    var urgentLowEpisodeActive by remember { mutableStateOf(false) }
    var showSensorFaultConfirm by remember { mutableStateOf(false) }
    var showAlarmInfo by remember { mutableStateOf(false) }
    var showUrgentResponsibilityConfirm by remember { mutableStateOf(false) }
    var responsibilityAccepted by remember { mutableStateOf(false) }
    var showThresholdConflict by remember { mutableStateOf(false) }
    var thresholdConflictMessage by remember { mutableStateOf("") }
    var pendingUrgentLow by remember { mutableStateOf<Double?>(null) }
    var pendingLow by remember { mutableStateOf<Double?>(null) }
    var pendingHigh by remember { mutableStateOf<Double?>(null) }
    var savedUrgentLow by remember { mutableStateOf(2.9) }
    var urgentLowAlarmText by remember { mutableStateOf("2.9") }
    var lowAlarmEnabled by remember { mutableStateOf(true) }
    var highAlarmEnabled by remember { mutableStateOf(true) }
    var lowAlarmText by remember { mutableStateOf("4.0") }
    var highAlarmText by remember { mutableStateOf("10.0") }
    var alarmMessage by remember { mutableStateOf("") }
    var testAlarmId by remember { mutableStateOf<String?>(null) }
    var testAlarmTitle by remember { mutableStateOf("") }
    var testAlarmUrgent by remember { mutableStateOf(false) }
    var testVolume by remember { mutableFloatStateOf(100f) }
    val testAlarmPlayer = remember { GlucoseAlarmPlayer(context) }

    DisposableEffect(Unit) {
        onDispose { testAlarmPlayer.stop() }
    }

    fun openAlarmTest(id: String, title: String, urgent: Boolean) {
        scope.launch {
            testAlarmPlayer.stop()
            testVolume = when (id) {
                "urgent" -> preferences.getUrgentLowAlarmVolume()
                "low" -> preferences.getLowAlarmVolume()
                else -> preferences.getHighAlarmVolume()
            }.toFloat()
            testAlarmId = id
            testAlarmTitle = title
            testAlarmUrgent = urgent
        }
    }

    fun saveAlarmValues(urgent: Double, low: Double, high: Double) {
        scope.launch {
            preferences.saveAlarmSettings(
                urgentLowEnabled = if (urgentLowEpisodeActive) true else urgentLowAlarmEnabled,
                urgentLowLevel = urgent,
                lowEnabled = lowAlarmEnabled,
                lowLevel = low,
                highEnabled = highAlarmEnabled,
                highLevel = high
            )
        }
        savedUrgentLow = urgent
        urgentLowAlarmText = String.format(Locale.UK, "%.1f", urgent)
        lowAlarmText = String.format(Locale.UK, "%.1f", low)
        highAlarmText = String.format(Locale.UK, "%.1f", high)
        alarmMessage = "Alarm settings saved."
        pendingUrgentLow = null
        pendingLow = null
        pendingHigh = null
    }

    LaunchedEffect(Unit) {
        urgentLowAlarmEnabled = preferences.getUrgentLowAlarmEnabled()
        urgentLowEpisodeActive = preferences.getUrgentLowEpisodeActive()
        savedUrgentLow = preferences.getUrgentLowAlarmLevel()
        urgentLowAlarmText = String.format(Locale.UK, "%.1f", savedUrgentLow)
        lowAlarmEnabled = preferences.getLowAlarmEnabled()
        highAlarmEnabled = preferences.getHighAlarmEnabled()
        lowAlarmText = String.format(Locale.UK, "%.1f", preferences.getLowAlarmLevel())
        highAlarmText = String.format(Locale.UK, "%.1f", preferences.getHighAlarmLevel())
        alarmSettingsLoaded = true
    }

    // Keep the Settings lock in sync with the foreground service while this page is open.
    LaunchedEffect(alarmSettingsLoaded) {
        if (alarmSettingsLoaded) {
            while (true) {
                urgentLowEpisodeActive = preferences.getUrgentLowEpisodeActive()
                if (urgentLowEpisodeActive) urgentLowAlarmEnabled = true
                delay(1_000L)
            }
        }
    }

    if (testAlarmId != null) {
        AlertDialog(
            onDismissRequest = {
                testAlarmPlayer.stop()
                testAlarmId = null
            },
            title = { Text("Test $testAlarmTitle alarm") },
            text = {
                Column {
                    Text(
                        "Set the volume for this alarm, then press Test to hear it.",
                        fontSize = 14.sp,
                        color = MutedText
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Volume ${testVolume.roundToInt()}%",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = testVolume,
                        onValueChange = { testVolume = it },
                        valueRange = 20f..100f,
                        steps = 15
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("20%", fontSize = 11.sp, color = MutedText)
                        Text("100%", fontSize = 11.sp, color = MutedText)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val volume = testVolume.roundToInt().coerceIn(20, 100)
                        testAlarmPlayer.stop()
                        if (testAlarmUrgent) testAlarmPlayer.playUrgentLow(volume)
                        else testAlarmPlayer.playLowOrHigh(volume)
                    }
                ) { Text("Test") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            testAlarmPlayer.stop()
                            testAlarmId = null
                        }
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            val id = testAlarmId ?: return@TextButton
                            val volume = testVolume.roundToInt().coerceIn(20, 100)
                            scope.launch { preferences.saveAlarmVolume(id, volume) }
                            testAlarmPlayer.stop()
                            testAlarmId = null
                            alarmMessage = "$testAlarmTitle volume saved at $volume%."
                        }
                    ) { Text("Save") }
                }
            }
        )
    }

    if (showThresholdConflict) {
        AlertDialog(
            onDismissRequest = { showThresholdConflict = false },
            title = { Text("Threshold conflict") },
            text = { Text(thresholdConflictMessage) },
            confirmButton = {
                Button(
                    onClick = {
                        showThresholdConflict = false
                        val urgent = pendingUrgentLow ?: return@Button
                        val low = pendingLow ?: return@Button
                        val high = pendingHigh ?: return@Button
                        urgentLowAlarmText = String.format(Locale.UK, "%.1f", urgent)
                        lowAlarmText = String.format(Locale.UK, "%.1f", low)
                        highAlarmText = String.format(Locale.UK, "%.1f", high)
                        if (urgent < 2.9 && urgent < savedUrgentLow - 0.001) {
                            responsibilityAccepted = false
                            showUrgentResponsibilityConfirm = true
                        } else {
                            saveAlarmValues(urgent, low, high)
                        }
                    }
                ) { Text("Update thresholds") }
            },
            dismissButton = {
                TextButton(onClick = { showThresholdConflict = false }) { Text("Cancel") }
            }
        )
    }

    if (showUrgentResponsibilityConfirm) {
        AlertDialog(
            onDismissRequest = { showUrgentResponsibilityConfirm = false },
            title = { Text("Lower Urgent Low threshold") },
            text = {
                Column {
                    val urgent = pendingUrgentLow ?: 2.9
                    Text(
                        "You're setting Urgent Low to ${String.format(Locale.UK, "%.1f", urgent)} mmol/L, below the GlucoBro default of 2.9 mmol/L. " +
                            "This may delay an urgent low-glucose warning."
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = responsibilityAccepted,
                            onCheckedChange = { responsibilityAccepted = it }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "I understand that I am choosing this threshold and accept responsibility for this setting.",
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = responsibilityAccepted,
                    onClick = {
                        val urgent = pendingUrgentLow ?: return@Button
                        val low = pendingLow ?: return@Button
                        val high = pendingHigh ?: return@Button
                        showUrgentResponsibilityConfirm = false
                        saveAlarmValues(urgent, low, high)
                    }
                ) {
                    Text("Use ${String.format(Locale.UK, "%.1f", pendingUrgentLow ?: 2.9)} mmol/L")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrgentResponsibilityConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showAlarmInfo) {
        AlertDialog(
            onDismissRequest = { showAlarmInfo = false },
            title = { Text("Glucose alarm information") },
            text = {
                Text(
                    "Urgent Low defaults to 2.9 mmol/L and can be set from 2.5 to 3.9 mmol/L. " +
                        "If you lower it below 2.9, GlucoBro asks you to explicitly accept responsibility for that choice. " +
                        "Urgent Low must always remain below Low, and Low must remain below High. " +
                        "If an urgent-low episode starts, its switch locks until glucose rises above your Low setting. " +
                        "A deliberate 5-second Sensor fault override is available for a genuinely bad Libre sensor. " +
                        "When enabled, Urgent Low sounds again at 2.5 mmol/L and every 5 minutes until recovery. " +
                        "Low and High use three long beeps and vibrations. Each alarm has its own 20–100% volume setting available from its Test button. " +
                        "Alarms use the phone alarm stream so normal silent/vibrate mode will not mute them."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAlarmInfo = false }) { Text("Got it") }
            }
        )
    }

    if (showSensorFaultConfirm) {
        AlertDialog(
            onDismissRequest = { showSensorFaultConfirm = false },
            title = { Text("Disable Urgent Low?") },
            text = {
                Text(
                    "Only use this if the Libre reading is known to be wrong because of a sensor fault. " +
                        "Urgent Low will be disabled and the active urgent-low episode will be cleared."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSensorFaultConfirm = false
                        scope.launch {
                            preferences.disableUrgentLowForSensorFault()
                            urgentLowAlarmEnabled = false
                            urgentLowEpisodeActive = false
                            alarmMessage = "Urgent Low disabled by sensor fault override."
                        }
                    }
                ) { Text("Disable Urgent Low") }
            },
            dismissButton = {
                TextButton(onClick = { showSensorFaultConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("LibreLinkUp", fontSize = 14.sp, color = MutedText)
                Text("Connected", fontSize = 18.sp, color = GlucoGreen, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(22.dp))
                HorizontalDivider(color = SoftGrid)
                Spacer(Modifier.height(18.dp))

                Text("Target range", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Used by the app graphs and Time in Range stats.", fontSize = 13.sp, color = MutedText)
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = lowText,
                        onValueChange = { lowText = it },
                        label = { Text("Low mmol/L") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = highText,
                        onValueChange = { highText = it },
                        label = { Text("High mmol/L") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val low = lowText.replace(',', '.').toDoubleOrNull()
                        val high = highText.replace(',', '.').toDoubleOrNull()
                        when {
                            low == null || high == null -> rangeMessage = "Enter valid numbers."
                            low < 2.0 || high > 25.0 -> rangeMessage = "Choose a range between 2.0 and 25.0 mmol/L."
                            low >= high -> rangeMessage = "Low target must be below high target."
                            else -> {
                                scope.launch { preferences.saveTargetRange(low, high) }
                                onTargetRangeChanged(low, high)
                                lowText = String.format(Locale.UK, "%.1f", low)
                                highText = String.format(Locale.UK, "%.1f", high)
                                rangeMessage = "Target range saved."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save target range")
                }
                if (rangeMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(rangeMessage, fontSize = 13.sp, color = if (rangeMessage == "Target range saved.") GlucoGreen else GlucoRed)
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = SoftGrid)
                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Glucose alarms",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showAlarmInfo = true }) {
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, MutedText)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "i",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MutedText
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (!alarmSettingsLoaded) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else {
                    val urgentPreview = urgentLowAlarmText.replace(',', '.').toDoubleOrNull()
                    FixedAlarmSettingRow(
                        title = "Urgent Low",
                        value = urgentLowAlarmText,
                        enabled = if (urgentLowEpisodeActive) true else urgentLowAlarmEnabled,
                        locked = urgentLowEpisodeActive,
                        accentColor = GlucoRed,
                        subtitle = if (urgentPreview != null && urgentPreview < 2.9) "Custom threshold • below default" else "Default 2.9 mmol/L",
                        subtitleWarning = urgentPreview != null && urgentPreview < 2.9,
                        onEnabledChange = { urgentLowAlarmEnabled = it },
                        onValueChange = { urgentLowAlarmText = it },
                        onTest = { openAlarmTest("urgent", "Urgent Low", true) }
                    )
                    if (urgentLowEpisodeActive) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Urgent Low is locked during an active alert. It will unlock automatically once glucose reaches your Low setting.",
                            fontSize = 12.sp,
                            color = GlucoRed
                        )
                        Spacer(Modifier.height(8.dp))
                        SensorFaultOverrideHoldButton { showSensorFaultConfirm = true }
                    }
                    Spacer(Modifier.height(10.dp))
                    AlarmSettingRow(
                        title = "Low",
                        enabled = lowAlarmEnabled,
                        value = lowAlarmText,
                        accentColor = Color(0xFFE47918),
                        onEnabledChange = { lowAlarmEnabled = it },
                        onValueChange = { lowAlarmText = it },
                        onTest = { openAlarmTest("low", "Low", false) }
                    )
                    Spacer(Modifier.height(10.dp))
                    AlarmSettingRow(
                        title = "High",
                        enabled = highAlarmEnabled,
                        value = highAlarmText,
                        accentColor = GlucoBlue,
                        onEnabledChange = { highAlarmEnabled = it },
                        onValueChange = { highAlarmText = it },
                        onTest = { openAlarmTest("high", "High", false) }
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val urgentAlarm = urgentLowAlarmText.replace(',', '.').toDoubleOrNull()
                            val lowAlarm = lowAlarmText.replace(',', '.').toDoubleOrNull()
                            val highAlarm = highAlarmText.replace(',', '.').toDoubleOrNull()
                            when {
                                urgentAlarm == null || lowAlarm == null || highAlarm == null ->
                                    alarmMessage = "Enter valid alarm levels."
                                urgentAlarm < 2.5 || urgentAlarm > 3.9 ->
                                    alarmMessage = "Urgent Low must be between 2.5 and 3.9 mmol/L."
                                lowAlarm < 3.5 || lowAlarm > 17.9 ->
                                    alarmMessage = "Low alarm must be between 3.5 and 17.9 mmol/L."
                                highAlarm > 18.0 ->
                                    alarmMessage = "High alarm cannot be above 18.0 mmol/L."
                                else -> {
                                    val adjustedLow = maxOf(lowAlarm, ((urgentAlarm * 10).roundToInt() + 1) / 10.0)
                                    val adjustedHigh = maxOf(highAlarm, ((adjustedLow * 10).roundToInt() + 1) / 10.0)
                                    if (adjustedHigh > 18.0) {
                                        alarmMessage = "These thresholds cannot be ordered below the 18.0 mmol/L High limit."
                                    } else if (adjustedLow != lowAlarm || adjustedHigh != highAlarm) {
                                        pendingUrgentLow = urgentAlarm
                                        pendingLow = adjustedLow
                                        pendingHigh = adjustedHigh
                                        val changes = buildList {
                                            if (adjustedLow != lowAlarm) add("Low ${String.format(Locale.UK, "%.1f", lowAlarm)} → ${String.format(Locale.UK, "%.1f", adjustedLow)}")
                                            if (adjustedHigh != highAlarm) add("High ${String.format(Locale.UK, "%.1f", highAlarm)} → ${String.format(Locale.UK, "%.1f", adjustedHigh)}")
                                        }.joinToString("\n")
                                        thresholdConflictMessage = "Urgent Low must stay below Low, and Low must stay below High. To keep the settings valid, GlucoBro needs to update:\n\n$changes"
                                        showThresholdConflict = true
                                    } else {
                                        pendingUrgentLow = urgentAlarm
                                        pendingLow = lowAlarm
                                        pendingHigh = highAlarm
                                        if (urgentAlarm < 2.9 && urgentAlarm < savedUrgentLow - 0.001) {
                                            responsibilityAccepted = false
                                            showUrgentResponsibilityConfirm = true
                                        } else {
                                            saveAlarmValues(urgentAlarm, lowAlarm, highAlarm)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save alarm settings")
                    }
                    if (alarmMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            alarmMessage,
                            fontSize = 13.sp,
                            color = if (alarmMessage == "Alarm settings saved.") GlucoGreen else GlucoRed
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = SoftGrid)
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // Stop the foreground service before clearing the saved session.
                            // Otherwise Android can leave the old polling coroutine alive with
                            // the previous Libre auth token until the process is force-stopped.
                            context.stopService(
                                Intent(context, GlucosePollingService::class.java)
                            )
                            preferences.logout()
                            onLoggedOut()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log out of LibreLinkUp")
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

private fun readingAgeMinutes(timestamp: String, nowMs: Long): Long? {
    if (timestamp.isBlank()) return null
    val formats = listOf(
        "M/d/yyyy h:mm:ss a",
        "MM/dd/yyyy h:mm:ss a",
        "M/d/yyyy hh:mm:ss a"
    )
    val readingMs = formats.firstNotNullOfOrNull { pattern ->
        try {
            SimpleDateFormat(pattern, Locale.US).parse(timestamp)?.time
        } catch (_: Exception) {
            null
        }
    } ?: return null

    return ((nowMs - readingMs) / 60_000L).coerceAtLeast(0L)
}

private data class TirStats(
    val inRangePct: Int,
    val highPct: Int,
    val lowPct: Int,
    val inRangeMinutes: Int,
    val highMinutes: Int,
    val lowMinutes: Int,
    val coverageMinutes: Int
)

private fun calculateTir(points: List<GraphPoint>, targetLow: Double, targetHigh: Double): TirStats {
    if (points.isEmpty()) return TirStats(0, 0, 0, 0, 0, 0, 0)

    // Calculate TIR from real elapsed time, not from the number of Libre graph
    // samples. The graph endpoint is usually ~15 minutes apart, but it can also
    // contain newer/closer readings. Counting each point equally can therefore
    // make a short high period look much longer than it really was.
    val timedPoints = points
        .mapNotNull { point -> parseLibreTimestampMs(point.timestamp)?.let { it to point.valueMmol } }
        .sortedBy { it.first }
        .distinctBy { it.first }

    if (timedPoints.size < 2) {
        val value = timedPoints.firstOrNull()?.second ?: points.last().valueMmol
        return when {
            value < targetLow -> TirStats(0, 0, 100, 0, 0, 1, 1)
            value > targetHigh -> TirStats(0, 100, 0, 0, 1, 0, 1)
            else -> TirStats(100, 0, 0, 1, 0, 0, 1)
        }
    }

    var inMs = 0.0
    var highMs = 0.0
    var lowMs = 0.0

    for (i in 0 until timedPoints.lastIndex) {
        val (t1, v1) = timedPoints[i]
        val (t2, v2) = timedPoints[i + 1]
        val intervalMs = t2 - t1

        // Keep TIR consistent with the graph: do not invent glucose behaviour
        // across genuine gaps in the data.
        if (intervalMs <= 0L || intervalMs > 35L * 60_000L) continue

        // Split the interval at the exact point where the interpolated line
        // crosses either target threshold. This mirrors drawThresholdSegment(),
        // so the coloured graph and the TIR durations use the same boundaries.
        val cuts = mutableListOf(0.0, 1.0)
        if (v1 != v2) {
            for (threshold in listOf(targetLow, targetHigh)) {
                val fraction = (threshold - v1) / (v2 - v1)
                if (fraction > 0.0 && fraction < 1.0) cuts.add(fraction)
            }
        }
        cuts.sort()

        for (j in 0 until cuts.lastIndex) {
            val a = cuts[j]
            val b = cuts[j + 1]
            val midpoint = (a + b) / 2.0
            val midpointValue = v1 + (v2 - v1) * midpoint
            val durationMs = intervalMs.toDouble() * (b - a)

            when {
                midpointValue < targetLow -> lowMs += durationMs
                midpointValue > targetHigh -> highMs += durationMs
                else -> inMs += durationMs
            }
        }
    }

    val totalMs = inMs + highMs + lowMs
    if (totalMs <= 0.0) return TirStats(0, 0, 0, 0, 0, 0, 0)

    // Round percentages while guaranteeing the displayed segments total 100%.
    val rawPercentages = listOf(inMs, highMs, lowMs).map { it * 100.0 / totalMs }
    val basePercentages = rawPercentages.map { floor(it).toInt() }.toMutableList()
    var percentageRemainder = 100 - basePercentages.sum()
    rawPercentages
        .mapIndexed { index, value -> index to (value - floor(value)) }
        .sortedByDescending { it.second }
        .forEach { (index, _) ->
            if (percentageRemainder > 0) {
                basePercentages[index]++
                percentageRemainder--
            }
        }

    val coverage = (totalMs / 60_000.0).roundToInt().coerceIn(0, 1440)
    val inMinutes = (inMs / 60_000.0).roundToInt()
    val highMinutes = (highMs / 60_000.0).roundToInt()
    val lowMinutes = (coverage - inMinutes - highMinutes).coerceAtLeast(0)

    return TirStats(
        inRangePct = basePercentages[0],
        highPct = basePercentages[1],
        lowPct = basePercentages[2],
        inRangeMinutes = inMinutes,
        highMinutes = highMinutes,
        lowMinutes = lowMinutes,
        coverageMinutes = coverage
    )
}

private fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun TimeInRangeCard(points: List<GraphPoint>, compact: Boolean, targetLow: Double, targetHigh: Double) {
    val stats = remember(points, targetLow, targetHigh) { calculateTir(points, targetLow, targetHigh) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Time in Range", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Last 24 hours", fontSize = 14.sp, color = MutedText)
            }
            Spacer(Modifier.height(18.dp))

            if (points.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("Building 24-hour history…", color = MutedText)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 150.dp else 180.dp), contentAlignment = Alignment.Center) {
                        TirDonut(stats)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${stats.inRangePct}%", fontSize = if (compact) 36.sp else 44.sp, fontWeight = FontWeight.Bold, color = GlucoGreen)
                            Text("In Range", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MutedText)
                            if (!compact) Text(durationText(stats.inRangeMinutes), fontSize = 12.sp, color = MutedText)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TirLegendRow(GlucoGreen, "In Range", String.format(Locale.UK, "%.1f–%.1f", targetLow, targetHigh), stats.inRangePct, if (!compact) durationText(stats.inRangeMinutes) else null)
                        TirLegendRow(GlucoBlue, "High", String.format(Locale.UK, ">%.1f", targetHigh), stats.highPct, if (!compact) durationText(stats.highMinutes) else null)
                        TirLegendRow(GlucoRed, "Low", String.format(Locale.UK, "<%.1f", targetLow), stats.lowPct, if (!compact) durationText(stats.lowMinutes) else null)
                    }
                }
                Spacer(Modifier.height(14.dp))
                val coverageText = if (stats.coverageMinutes >= 1380) {
                    "24-hour history"
                } else {
                    "${durationText(stats.coverageMinutes)} of history collected"
                }
                Text("Target range: ${String.format(Locale.UK, "%.1f", targetLow)} – ${String.format(Locale.UK, "%.1f", targetHigh)} mmol/L  •  $coverageText", fontSize = 12.sp, color = MutedText)
            }
        }
    }
}

@Composable
private fun TirLegendRow(color: Color, label: String, range: String, percent: Int, duration: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) { drawCircle(color) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(5.dp))
                Text("($range)", fontSize = 12.sp, color = MutedText)
            }
            if (duration != null) Text(duration, fontSize = 12.sp, color = MutedText)
        }
        Text("$percent%", fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TirDonut(stats: TirStats) {
    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val stroke = 17.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        var start = -90f
        val segments = listOf(
            stats.inRangePct to GlucoGreen,
            stats.highPct to GlucoBlue,
            stats.lowPct to GlucoRed
        )
        if (segments.sumOf { it.first } == 0) {
            drawArc(SoftGrid, -90f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
        } else {
            segments.forEach { (pct, color) ->
                if (pct > 0) {
                    val sweep = pct * 3.6f
                    drawArc(color, start, sweep, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
        }
    }
}

private fun parseLibreTimestampMs(timestamp: String): Long? {
    val formats = listOf("M/d/yyyy h:mm:ss a", "MM/dd/yyyy h:mm:ss a", "M/d/yyyy hh:mm:ss a")
    for (pattern in formats) {
        try {
            val parsed = SimpleDateFormat(pattern, Locale.US).parse(timestamp)
            if (parsed != null) return parsed.time
        } catch (_: Exception) { }
    }
    return null
}

private fun filterPointsByHours(points: List<GraphPoint>, hours: Int): List<GraphPoint> {
    if (points.isEmpty()) return emptyList()
    val cutoff = System.currentTimeMillis() - hours * 60L * 60_000L
    return points
        .mapNotNull { point -> parseLibreTimestampMs(point.timestamp)?.let { it to point } }
        .filter { it.first >= cutoff }
        .sortedBy { it.first }
        .map { it.second }
}

private fun historySpanMinutes(points: List<GraphPoint>, maxMinutes: Int): Int {
    val times = points.mapNotNull { parseLibreTimestampMs(it.timestamp) }.sorted()
    if (times.isEmpty()) return 0
    if (times.size == 1) return 1
    return (((times.last() - times.first()) / 60_000L) + 15L).toInt().coerceIn(0, maxMinutes)
}

@Composable
private fun GlucoseGraph(
    points: List<GraphPoint>,
    hours: Int,
    targetLow: Double,
    targetHigh: Double,
    modifier: Modifier = Modifier
) {
    val timedPoints = points.mapNotNull { point -> parseLibreTimestampMs(point.timestamp)?.let { it to point } }.sortedBy { it.first }
    val values = timedPoints.map { it.second.valueMmol }
    var minY = floor(minOf(targetLow - 1.0, values.minOrNull() ?: targetLow - 1.0)).coerceAtLeast(2.0)
    var maxY = ceil(maxOf(targetHigh + 1.0, values.maxOrNull() ?: targetHigh + 1.0)).coerceAtMost(25.0)
    if (maxY - minY < 8.0) maxY = minY + 8.0

    // The x-axis is real clock time, not point index. That means a partially
    // collected 24-hour history stays blank on the left instead of being
    // stretched misleadingly across the whole 24-hour chart.
    val windowEndMs = System.currentTimeMillis()
    val windowStartMs = windowEndMs - hours * 60L * 60_000L
    val windowDurationMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize().padding(start = 30.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                val plotW = size.width
                val plotH = size.height
                fun y(v: Double) = ((maxY - v) / (maxY - minY) * plotH).toFloat()
                fun x(timeMs: Long) = (((timeMs - windowStartMs).toDouble() / windowDurationMs) * plotW).toFloat().coerceIn(0f, plotW)

                listOf(targetLow, 7.0, targetHigh).distinct().forEach { level ->
                    if (level in minY..maxY) {
                        drawLine(SoftGrid, Offset(0f, y(level)), Offset(plotW, y(level)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    }
                }

                for (i in 0 until timedPoints.lastIndex) {
                    val (t1, p1) = timedPoints[i]
                    val (t2, p2) = timedPoints[i + 1]
                    // Libre graph points are normally about 15 minutes apart.
                    // If there is a real data gap, do not join across it.
                    if (t2 - t1 <= 35L * 60_000L) {
                        drawThresholdSegment(
                            x(t1), p1.valueMmol,
                            x(t2), p2.valueMmol,
                            ::y, targetLow, targetHigh
                        )
                    }
                }
                if (timedPoints.isNotEmpty()) {
                    val (lastTime, lastPoint) = timedPoints.last()
                    drawCircle(
                        glucoseColour(lastPoint.valueMmol, targetLow, targetHigh),
                        radius = 7f,
                        center = Offset(x(lastTime), y(lastPoint.valueMmol))
                    )
                }
            }

            // Put each Y-axis label at the exact same glucose coordinate used
            // by the Canvas grid line. Previously these three labels were simply
            // spaced top/middle/bottom, which made 4 / 7 / 10 look like the
            // chart's min/mid/max instead of their real mmol/L positions.
            BoxWithConstraints(Modifier.fillMaxHeight().width(30.dp)) {
                val plotTop = 4.dp
                val plotHeight = (maxHeight - 8.dp).coerceAtLeast(1.dp)

                fun labelOffset(level: Double) =
                    plotTop + plotHeight * ((maxY - level) / (maxY - minY)).toFloat() - 7.dp

                listOf(
                    targetHigh to String.format(Locale.UK, "%.1f", targetHigh),
                    7.0 to "7",
                    targetLow to String.format(Locale.UK, "%.1f", targetLow)
                ).distinctBy { it.first }.forEach { (level, label) ->
                    if (level in minY..maxY) {
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = MutedText,
                            modifier = Modifier.offset(y = labelOffset(level))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val slots = 6
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val format = SimpleDateFormat("HH:mm", Locale.UK)
            for (slot in 0..slots) {
                val timeMs = windowStartMs + (windowDurationMs * slot / slots)
                Text(
                    format.format(java.util.Date(timeMs)),
                    fontSize = 9.sp,
                    color = MutedText,
                    textAlign = when (slot) { 0 -> TextAlign.Start; slots -> TextAlign.End; else -> TextAlign.Center },
                    modifier = if (slot == 0 || slot == slots) Modifier.width(34.dp) else Modifier
                )
            }
        }
    }
}

private fun DrawScope.drawThresholdSegment(
    x1: Float,
    v1: Double,
    x2: Float,
    v2: Double,
    y: (Double) -> Float,
    targetLow: Double,
    targetHigh: Double
) {
    val cuts = mutableListOf(0.0, 1.0)
    if (v1 != v2) {
        listOf(targetLow, targetHigh).forEach { threshold ->
            val t = (threshold - v1) / (v2 - v1)
            if (t > 0.0 && t < 1.0) cuts.add(t)
        }
    }
    cuts.sort()
    for (j in 0 until cuts.lastIndex) {
        val a = cuts[j]
        val b = cuts[j + 1]
        val m = (a + b) / 2.0
        val va = v1 + (v2 - v1) * a
        val vb = v1 + (v2 - v1) * b
        val vm = v1 + (v2 - v1) * m
        val xa = x1 + (x2 - x1) * a.toFloat()
        val xb = x1 + (x2 - x1) * b.toFloat()
        drawLine(glucoseColour(vm, targetLow, targetHigh), Offset(xa, y(va)), Offset(xb, y(vb)), strokeWidth = 7f, cap = StrokeCap.Round)
    }
}

private fun glucoseColour(value: Double, targetLow: Double = 4.0, targetHigh: Double = 10.0): Color = when {
    value < targetLow -> GlucoRed
    value > targetHigh -> GlucoBlue
    else -> GlucoGreen
}

private fun updatedText(timestamp: String, nowMs: Long): String {
    if (timestamp.isBlank()) return "Updated recently"
    val formats = listOf("M/d/yyyy h:mm:ss a", "MM/dd/yyyy h:mm:ss a", "M/d/yyyy hh:mm:ss a")
    val parsed = formats.firstNotNullOfOrNull { pattern ->
        try { SimpleDateFormat(pattern, Locale.US).parse(timestamp) } catch (_: Exception) { null }
    } ?: return "Updated recently"
    val minutes = ((nowMs - parsed.time) / 60_000L).coerceAtLeast(0)
    return when {
        minutes <= 0 -> "Updated just now"
        minutes == 1L -> "Updated 1 min ago"
        else -> "Updated $minutes mins ago"
    }
}
