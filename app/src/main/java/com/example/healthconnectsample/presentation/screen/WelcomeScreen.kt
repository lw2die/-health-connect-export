package com.example.healthconnectsample.presentation.screen.welcome

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.healthconnectsample.R
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import com.example.healthconnectsample.data.HealthConnectManager
import com.example.healthconnectsample.data.HealthDataSerializer
import com.example.healthconnectsample.presentation.component.InstalledMessage
import com.example.healthconnectsample.presentation.component.NotInstalledMessage
import com.example.healthconnectsample.presentation.component.NotSupportedMessage
import com.example.healthconnectsample.worker.AutoExportWorker
import com.example.healthconnectsample.worker.ChangeTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun WelcomeScreen(
    healthConnectAvailability: Int,
    onResumeAvailabilityCheck: () -> Unit,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nextExportTime by remember { mutableStateOf("Calculando...") }
    var nextExportMinutes by remember { mutableStateOf<Long?>(null) }
    var isWorkerActive by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    val currentOnAvailabilityCheck by rememberUpdatedState(onResumeAvailabilityCheck)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentOnAvailabilityCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val workManager = WorkManager.getInstance(context)
                    val workInfosFuture = workManager.getWorkInfosForUniqueWork("auto_export_health_data")
                    val workInfos = workInfosFuture.get()

                    withContext(Dispatchers.Main) {
                        isWorkerActive = workInfos.any { workInfo ->
                            workInfo.state == WorkInfo.State.RUNNING ||
                                    workInfo.state == WorkInfo.State.ENQUEUED
                        }
                    }
                }

                val currentMinute = System.currentTimeMillis() / (1000 * 60)
                val minutesSinceLastSlot = (currentMinute % 30).toInt()
                val minutesToNext = 30 - minutesSinceLastSlot

                nextExportMinutes = minutesToNext.toLong()

                if (minutesToNext <= 5) {
                    nextExportTime = "Próximo export: en $minutesToNext min"
                } else {
                    nextExportTime = "Próximo export: ~$minutesToNext min"
                }
            } catch (e: Exception) {
                nextExportTime = "Export automático activo"
            }
            delay(30_000)
        }
    }

    fun performManualExport() {
        if (isExporting) return

        isExporting = true
        Toast.makeText(context, "Iniciando export manual (últimos 30 días)...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                val healthConnectManager = HealthConnectManager(context)
                val endTime = Instant.now()
                val startTime = endTime.minus(java.time.Duration.ofDays(30))

                // WEIGHT DATA
                val allWeightRecords = healthConnectManager.readWeightInputs(startTime, endTime)
                val samsungWeight = allWeightRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val weightData = samsungWeight.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "weight_kg" to record.weight.inKilograms,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // EXERCISE DATA
                val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
                val samsungExercises = allExerciseRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val exerciseData = samsungExercises.mapNotNull { record ->
                    val durationMinutes = try {
                        java.time.Duration.between(record.startTime, record.endTime).toMinutes()
                    } catch (e: Exception) { 0L }

                    if (durationMinutes < 2) return@mapNotNull null

                    val sessionData = try {
                        healthConnectManager.readAssociatedSessionData(record.metadata.id)
                    } catch (e: Exception) { null }

                    if (sessionData == null) return@mapNotNull null

                    mapOf(
                        "session_id" to record.metadata.id,
                        "title" to (record.title ?: "Exercise Session"),
                        "exercise_type" to record.exerciseType,
                        "start_time" to record.startTime.toString(),
                        "end_time" to record.endTime.toString(),
                        "duration_minutes" to (sessionData.totalActiveTime?.toMinutes() ?: durationMinutes),
                        "total_steps" to sessionData.totalSteps,
                        "distance_meters" to sessionData.totalDistance?.inMeters,
                        "calories_burned" to sessionData.totalEnergyBurned?.inCalories,
                        "avg_heart_rate" to sessionData.avgHeartRate,
                        "max_heart_rate" to sessionData.maxHeartRate,
                        "min_heart_rate" to sessionData.minHeartRate,
                        "data_origin" to record.metadata.dataOrigin.packageName
                    )
                }

                // SLEEP DATA
                val allSleepRecords = healthConnectManager.readSleepSessions(startTime, endTime)
                val samsungSleep = allSleepRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val sleepData = samsungSleep.map { record ->
                    val durationMinutes = try {
                        java.time.Duration.between(record.startTime, record.endTime).toMinutes()
                    } catch (e: Exception) { 0L }

                    val stages = record.stages.map { stage ->
                        mapOf(
                            "stage_type" to stage.stage,
                            "stage_name" to HealthDataSerializer.getSleepStageName(stage.stage),
                            "start_time" to stage.startTime.toString(),
                            "end_time" to stage.endTime.toString()
                        )
                    }

                    mapOf(
                        "session_id" to record.metadata.id,
                        "title" to (record.title ?: "Sleep Session"),
                        "notes" to record.notes,
                        "start_time" to record.startTime.toString(),
                        "end_time" to record.endTime.toString(),
                        "duration_minutes" to durationMinutes,
                        "stages_count" to stages.size,
                        "stages" to stages,
                        "data_origin" to record.metadata.dataOrigin.packageName
                    )
                }

                // VO2MAX DATA
                val allVo2MaxRecords = healthConnectManager.readVo2MaxRecords(startTime, endTime)
                val samsungVo2Max = allVo2MaxRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val vo2MaxData = samsungVo2Max.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "vo2_max_ml_per_min_per_kg" to record.vo2MillilitersPerMinuteKilogram,
                        "measurement_method" to record.measurementMethod,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.7.0 - STEPS DATA
                val allStepsRecords = healthConnectManager.readStepsRecords(startTime, endTime)
                val samsungSteps = allStepsRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val stepsData = samsungSteps.map { record ->
                    mapOf(
                        "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                        "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                        "count" to record.count,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.7.0 - DISTANCE DATA
                val allDistanceRecords = healthConnectManager.readDistanceRecords(startTime, endTime)
                val samsungDistance = allDistanceRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val distanceData = samsungDistance.map { record ->
                    mapOf(
                        "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                        "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                        "distance_meters" to record.distance.inMeters,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.7.0 - TOTAL CALORIES DATA
                val allTotalCaloriesRecords = healthConnectManager.readTotalCaloriesBurnedRecords(startTime, endTime)
                val samsungTotalCalories = allTotalCaloriesRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val totalCaloriesData = samsungTotalCalories.map { record ->
                    mapOf(
                        "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                        "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                        "energy_kcal" to record.energy.inKilocalories,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.7.0 - RESTING HEART RATE DATA
                val allRestingHRRecords = healthConnectManager.readRestingHeartRateRecords(startTime, endTime)
                val samsungRestingHR = allRestingHRRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val restingHRData = samsungRestingHR.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "bpm" to record.beatsPerMinute,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.7.0 - OXYGEN SATURATION DATA
                val allOxygenSatRecords = healthConnectManager.readOxygenSaturationRecords(startTime, endTime)
                val samsungOxygenSat = allOxygenSatRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val oxygenSatData = samsungOxygenSat.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "percentage" to record.percentage.value,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.8.0 - HEIGHT DATA
                val allHeightRecords = healthConnectManager.readHeightRecords(startTime, endTime)
                val samsungHeight = allHeightRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val heightData = samsungHeight.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "height_meters" to record.height.inMeters,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.8.0 - BODY FAT DATA
                val allBodyFatRecords = healthConnectManager.readBodyFatRecords(startTime, endTime)
                val samsungBodyFat = allBodyFatRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val bodyFatData = samsungBodyFat.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "percentage" to record.percentage.value,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.8.0 - LEAN BODY MASS DATA
                val allLeanBodyMassRecords = healthConnectManager.readLeanBodyMassRecords(startTime, endTime)
                val samsungLeanBodyMass = allLeanBodyMassRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val leanBodyMassData = samsungLeanBodyMass.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "mass_kg" to record.mass.inKilograms,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                // v1.8.0 - BONE MASS DATA
                val allBoneMassRecords = healthConnectManager.readBoneMassRecords(startTime, endTime)
                val samsungBoneMass = allBoneMassRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val boneMassData = samsungBoneMass.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "mass_kg" to record.mass.inKilograms,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                val fileName = "health_data_SAMSUNG_MANUAL_${timestamp}.json"

                val jsonContent = buildString {
                    append("{\n")
                    append("  \"export_type\": \"MANUAL_SAMSUNG_ONLY\",\n")
                    append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
                    append("  \"data_source\": \"Samsung Health Only - Last 30 days\",\n")
                    append("  \"weight_records\": {\n")
                    append("    \"count\": ${weightData.size},\n")
                    append("    \"data\": ${serializeList(weightData)}\n")
                    append("  },\n")
                    append("  \"exercise_sessions\": {\n")
                    append("    \"count\": ${exerciseData.size},\n")
                    append("    \"data\": ${serializeList(exerciseData)}\n")
                    append("  },\n")
                    append("  \"sleep_sessions\": {\n")
                    append("    \"count\": ${sleepData.size},\n")
                    append("    \"data\": ${serializeList(sleepData)}\n")
                    append("  },\n")
                    append("  \"vo2max_records\": {\n")
                    append("    \"count\": ${vo2MaxData.size},\n")
                    append("    \"data\": ${serializeList(vo2MaxData)}\n")
                    append("  },\n")
                    // v1.7.0 - Essential Metrics
                    append("  \"steps_records\": {\n")
                    append("    \"count\": ${stepsData.size},\n")
                    append("    \"data\": ${serializeList(stepsData)}\n")
                    append("  },\n")
                    append("  \"distance_records\": {\n")
                    append("    \"count\": ${distanceData.size},\n")
                    append("    \"data\": ${serializeList(distanceData)}\n")
                    append("  },\n")
                    append("  \"total_calories_records\": {\n")
                    append("    \"count\": ${totalCaloriesData.size},\n")
                    append("    \"data\": ${serializeList(totalCaloriesData)}\n")
                    append("  },\n")
                    append("  \"resting_heart_rate_records\": {\n")
                    append("    \"count\": ${restingHRData.size},\n")
                    append("    \"data\": ${serializeList(restingHRData)}\n")
                    append("  },\n")
                    append("  \"oxygen_saturation_records\": {\n")
                    append("    \"count\": ${oxygenSatData.size},\n")
                    append("    \"data\": ${serializeList(oxygenSatData)}\n")
                    append("  },\n")
                    // v1.8.0 - Body Measurements Group 2
                    append("  \"height_records\": {\n")
                    append("    \"count\": ${heightData.size},\n")
                    append("    \"data\": ${serializeList(heightData)}\n")
                    append("  },\n")
                    append("  \"body_fat_records\": {\n")
                    append("    \"count\": ${bodyFatData.size},\n")
                    append("    \"data\": ${serializeList(bodyFatData)}\n")
                    append("  },\n")
                    append("  \"lean_body_mass_records\": {\n")
                    append("    \"count\": ${leanBodyMassData.size},\n")
                    append("    \"data\": ${serializeList(leanBodyMassData)}\n")
                    append("  },\n")
                    append("  \"bone_mass_records\": {\n")
                    append("    \"count\": ${boneMassData.size},\n")
                    append("    \"data\": ${serializeList(boneMassData)}\n")
                    append("  }\n")
                    append("}")
                }

                val tempFile = File(context.cacheDir, fileName)
                FileWriter(tempFile).use { it.write(jsonContent) }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    ))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage("com.google.android.apps.docs")
                }

                context.startActivity(Intent.createChooser(shareIntent, "Guardar en Drive").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })

                Toast.makeText(
                    context,
                    "Export: ${weightData.size}peso +${exerciseData.size}ej +${sleepData.size}sueño +${vo2MaxData.size}VO2 +${stepsData.size}pasos +${distanceData.size}dist +${totalCaloriesData.size}cal +${restingHRData.size}RHR +${oxygenSatData.size}SpO2 +${heightData.size}altura +${bodyFatData.size}grasa +${leanBodyMassData.size}magra +${boneMassData.size}hueso",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isExporting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            modifier = Modifier.fillMaxWidth(0.5f),
            painter = painterResource(id = R.drawable.ic_health_connect_logo),
            contentDescription = stringResource(id = R.string.health_connect_logo)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(id = R.string.welcome_message),
            color = MaterialTheme.colors.onBackground
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            backgroundColor = if (isWorkerActive)
                Color(0xFF1B5E20)
            else
                Color(0xFF424242),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (isWorkerActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Export Automático DIFERENCIAL",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nextExportTime,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Solo cambios nuevos cada 30 min, 24/7",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                nextExportMinutes?.let { minutes ->
                    if (minutes in 1..30) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val progress = 1f - (minutes.toFloat() / 30f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(3.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(
                                        color = Color(0xFF4CAF50),
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { performManualExport() },
            enabled = !isExporting,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF4CAF50)
            ),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                text = if (isExporting) "Exportando..." else "Export Manual (30 días)",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val workRequest = OneTimeWorkRequestBuilder<AutoExportWorker>()
                    .build()

                WorkManager.getInstance(context).enqueue(workRequest)

                Toast.makeText(
                    context,
                    "Export diferencial inmediato iniciado",
                    Toast.LENGTH_SHORT
                ).show()
            },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF2196F3)
            ),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                text = "Export DIFF Ahora",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                ChangeTokenManager(context).clearToken()
                Toast.makeText(
                    context,
                    "Token reseteado. Próximo export será completo.",
                    Toast.LENGTH_LONG
                ).show()
            },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFFF6B6B)
            ),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                text = "Reset Export (Debug)",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (healthConnectAvailability) {
            SDK_AVAILABLE -> InstalledMessage()
            SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> NotInstalledMessage()
            SDK_UNAVAILABLE -> NotSupportedMessage()
        }
    }
}

private fun serializeList(list: List<Map<String, Any?>>): String {
    if (list.isEmpty()) return "[]"
    return buildString {
        append("[\n")
        list.forEachIndexed { index, map ->
            append("      {\n")
            map.entries.forEachIndexed { entryIndex, (key, value) ->
                append("        \"$key\": ")
                when (value) {
                    is String -> append("\"$value\"")
                    is Number -> append(value.toString())
                    is List<*> -> append(serializeList(value as List<Map<String, Any?>>))
                    null -> append("null")
                    else -> append(value.toString())
                }
                if (entryIndex < map.size - 1) append(",")
                append("\n")
            }
            append("      }")
            if (index < list.size - 1) append(",")
            append("\n")
        }
        append("    ]")
    }
}