/*
 * Copyright 2024 The Android Open Source Project
 */
package com.example.healthconnectsample.presentation.screen.inputreadings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.units.Mass
import com.example.healthconnectsample.R
import com.example.healthconnectsample.data.HealthConnectAppInfo
import com.example.healthconnectsample.data.HealthConnectManager
import com.example.healthconnectsample.data.WeightData
import com.example.healthconnectsample.presentation.theme.HealthConnectTheme
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * Mapper de tipos de ejercicio de Health Connect a nombres legibles
 * Basado en ExerciseSessionRecord.EXERCISE_SESSION_TYPE_*
 */
object ExerciseTypeMapper {
    fun getExerciseTypeName(exerciseType: Int): String {
        return when (exerciseType) {
            0 -> "Unknown"
            1 -> "Badminton"
            2 -> "Baseball"
            3 -> "Basketball"
            4 -> "Biking"
            5 -> "Biking (Stationary)"
            6 -> "Boot Camp"
            7 -> "Boxing"
            8 -> "Calisthenics"
            9 -> "Cricket"
            10 -> "Dancing"
            11 -> "Exercise Class"
            12 -> "Fencing"
            13 -> "Football (American)"
            14 -> "Football (Australian)"
            15 -> "Frisbee"
            16 -> "Golf"
            17 -> "Guided Breathing"
            18 -> "Gymnastics"
            19 -> "Handball"
            20 -> "HIIT"
            21 -> "Hiking"
            22 -> "Ice Hockey"
            23 -> "Ice Skating"
            24 -> "Martial Arts"
            25 -> "Paddling"
            26 -> "Paragliding"
            27 -> "Pilates"
            28 -> "Racquetball"
            29 -> "Rock Climbing"
            30 -> "Roller Hockey"
            31 -> "Rowing"
            32 -> "Rugby"
            33 -> "Running"
            34 -> "Running (Treadmill)"
            35 -> "Sailing"
            36 -> "Scuba Diving"
            37 -> "Skating"
            38 -> "Skiing"
            39 -> "Snowboarding"
            40 -> "Snowshoeing"
            41 -> "Soccer"
            42 -> "Softball"
            43 -> "Squash"
            44 -> "Stair Climbing"
            45 -> "Strength Training"
            46 -> "Stretching"
            47 -> "Surfing"
            48 -> "Swimming (Open Water)"
            49 -> "Swimming (Pool)"
            50 -> "Table Tennis"
            51 -> "Tennis"
            52 -> "Volleyball"
            53 -> "Walking"
            54 -> "Water Polo"
            55 -> "Weightlifting"
            56 -> "Wheelchair"
            57 -> "Yoga"
            58 -> "Other Workout"
            59 -> "Stair Climbing Machine"
            60 -> "Elliptical"
            61 -> "Rowing Machine"
            else -> "Other ($exerciseType)"
        }
    }

    fun getExerciseTypeEmoji(exerciseType: Int): String {
        return when (exerciseType) {
            4, 5 -> "🚴"
            33, 34 -> "🏃"
            45 -> "💪"
            49, 48 -> "🏊"
            53 -> "🚶"
            57 -> "🧘"
            else -> "🏋️"
        }
    }
}

@Composable
fun InputReadingsScreen(
    permissions: Set<String>,
    permissionsGranted: Boolean,
    readingsList: List<WeightData>,
    uiState: InputReadingsViewModel.UiState,
    onInsertClick: (Double) -> Unit = {},
    onDeleteClick: (String) -> Unit = {},
    onError: (Throwable?) -> Unit = {},
    onPermissionsResult: () -> Unit = {},
    weeklyAvg: Mass?,
    onPermissionsLaunch: (Set<String>) -> Unit = {},
    healthConnectManager: HealthConnectManager? = null
) {

    val errorId = rememberSaveable { mutableStateOf(UUID.randomUUID()) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    suspend fun readExerciseSessionsFiltered(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        if (healthConnectManager == null) return emptyList()

        return try {
            val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
            val samsungHealthRecords = allExerciseRecords.filter { record ->
                record.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
            }

            android.util.Log.d("HealthExport", "Total records from Samsung Health: ${samsungHealthRecords.size}")

            val exerciseSessions = mutableListOf<Map<String, Any?>>()
            val exerciseTypeCounts = mutableMapOf<Int, Int>()

            samsungHealthRecords.forEach { exerciseRecord ->
                val durationMinutes = try {
                    java.time.Duration.between(exerciseRecord.startTime, exerciseRecord.endTime).toMinutes()
                } catch (e: Exception) { 0L }

                // Filtrar ejercicios muy cortos
                if (durationMinutes < 2) return@forEach

                val sessionData = try {
                    healthConnectManager.readAssociatedSessionData(exerciseRecord.metadata.id)
                } catch (e: Exception) {
                    null
                }

                // No exportar ejercicios sin datos detallados
                if (sessionData == null) return@forEach

                // Contar tipos de ejercicio para diagnóstico
                val exerciseType = exerciseRecord.exerciseType
                exerciseTypeCounts[exerciseType] = (exerciseTypeCounts[exerciseType] ?: 0) + 1

                val typeName = ExerciseTypeMapper.getExerciseTypeName(exerciseType)
                val typeEmoji = ExerciseTypeMapper.getExerciseTypeEmoji(exerciseType)

                exerciseSessions.add(mapOf(
                    "session_id" to exerciseRecord.metadata.id,
                    "title" to (exerciseRecord.title ?: "Exercise Session"),
                    "exercise_type" to exerciseType,
                    "exercise_type_name" to typeName,
                    "exercise_emoji" to typeEmoji,
                    "start_time" to exerciseRecord.startTime.toString(),
                    "end_time" to exerciseRecord.endTime.toString(),
                    "duration_minutes" to (sessionData.totalActiveTime?.toMinutes() ?: durationMinutes),
                    "total_steps" to sessionData.totalSteps,
                    "distance_meters" to sessionData.totalDistance?.inMeters,
                    "calories_burned" to sessionData.totalEnergyBurned?.inCalories,
                    "avg_heart_rate" to sessionData.avgHeartRate,
                    "max_heart_rate" to sessionData.maxHeartRate,
                    "min_heart_rate" to sessionData.minHeartRate,
                    "data_origin" to exerciseRecord.metadata.dataOrigin.packageName,
                    "has_detailed_data" to true
                ))
            }

            // Log de diagnóstico de tipos encontrados
            android.util.Log.d("HealthExport", "=== TIPOS DE EJERCICIO ENCONTRADOS ===")
            exerciseTypeCounts.forEach { (type, count) ->
                val name = ExerciseTypeMapper.getExerciseTypeName(type)
                android.util.Log.d("HealthExport", "Tipo $type ($name): $count sesiones")
            }
            android.util.Log.d("HealthExport", "Total ejercicios exportados: ${exerciseSessions.size}")

            exerciseSessions
        } catch (e: Exception) {
            android.util.Log.e("HealthExport", "Error reading exercises", e)
            Toast.makeText(context, "Error reading exercises: ${e.message}", Toast.LENGTH_SHORT).show()
            emptyList()
        }
    }

    fun getFilteredWeightRecords(allRecords: List<WeightData>): List<WeightData> {
        return allRecords.filter { record ->
            record.sourceAppInfo?.packageName == "com.sec.android.app.shealth"
        }
    }

    fun generateSimpleHealthJSON(
        weightRecords: List<WeightData>,
        exerciseData: List<Map<String, Any?>>,
        exportType: String
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"$exportType\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Samsung Health Only (Filtered)\",\n")
            append("  \"weight_records\": {\n")
            append("    \"count\": ${weightRecords.size},\n")
            append("    \"data\": [\n")

            weightRecords.forEachIndexed { index, record ->
                append("      {\n")
                append("        \"timestamp\": \"${record.time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
                append("        \"weight_kg\": ${String.format("%.2f", record.weight.inKilograms)},\n")
                append("        \"source\": \"${record.sourceAppInfo?.packageName ?: "unknown"}\"\n")
                append("      }")
                if (index < weightRecords.size - 1) append(",")
                append("\n")
            }

            append("    ]\n")
            append("  },\n")
            append("  \"exercise_sessions\": {\n")
            append("    \"count\": ${exerciseData.size},\n")
            append("    \"data\": [\n")

            exerciseData.forEachIndexed { index, session ->
                append("      {\n")
                session.forEach { (key, value) ->
                    val valueStr = when (value) {
                        is String -> "\"$value\""
                        is Double -> String.format("%.2f", value)
                        null -> "null"
                        else -> value.toString()
                    }
                    append("        \"$key\": $valueStr")
                    if (key != session.keys.last()) append(",")
                    append("\n")
                }
                append("      }")
                if (index < exerciseData.size - 1) append(",")
                append("\n")
            }

            append("    ]\n")
            append("  }\n")
            append("}")
        }
    }

    fun shareJSONFile(context: Context, jsonContent: String, fileName: String, exportType: String) {
        try {
            val tempFile = File(context.cacheDir, fileName)
            FileWriter(tempFile).use { writer ->
                writer.write(jsonContent)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                ))
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, "Health Connect data filtered for Samsung Health only")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.google.android.apps.docs")
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Save to Google Drive")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)

        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is InputReadingsViewModel.UiState.Uninitialized) {
            onPermissionsResult()
        }
        if (uiState is InputReadingsViewModel.UiState.Error && errorId.value != uiState.uuid) {
            onError(uiState.exception)
            errorId.value = uiState.uuid
        }
    }

    var weightInput by remember { mutableStateOf("") }

    fun hasValidDoubleInRange(weight: String): Boolean {
        val tempVal = weight.toDoubleOrNull()
        return if (tempVal == null) false else tempVal <= 1000
    }

    fun shareWeightDataToGoogleDrive() {
        Toast.makeText(context, "Iniciando export SOLO Samsung Health...", Toast.LENGTH_SHORT).show()

        coroutineScope.launch {
            try {
                val endTime = Instant.now()
                val startTime = Instant.EPOCH

                val exerciseData = readExerciseSessionsFiltered(startTime, endTime)
                val filteredWeightRecords = getFilteredWeightRecords(readingsList)

                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                val fileName = "health_data_SAMSUNG_MANUAL_${timestamp}.json"

                val jsonContent = generateSimpleHealthJSON(filteredWeightRecords, exerciseData, "MANUAL_SAMSUNG_ONLY")
                shareJSONFile(context, jsonContent, fileName, "MANUAL")

                Toast.makeText(
                    context,
                    "EXPORT SAMSUNG: ${filteredWeightRecords.size} weight + ${exerciseData.size} exercises",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (uiState != InputReadingsViewModel.UiState.Uninitialized) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!permissionsGranted) {
                item {
                    Button(onClick = { onPermissionsLaunch(permissions) }) {
                        Text(text = stringResource(R.string.permissions_button_label))
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = { weightInput = it },
                        label = { Text(stringResource(id = R.string.weight_input)) },
                        isError = !hasValidDoubleInRange(weightInput),
                        keyboardActions = KeyboardActions { !hasValidDoubleInRange(weightInput) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (!hasValidDoubleInRange(weightInput)) {
                        Text(
                            text = stringResource(id = R.string.valid_weight_error_message),
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }

                    Button(
                        enabled = hasValidDoubleInRange(weightInput),
                        onClick = {
                            onInsertClick(weightInput.toDouble())
                            weightInput = ""
                        },
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Text(text = stringResource(id = R.string.add_readings_button))
                    }

                    Button(
                        onClick = { shareWeightDataToGoogleDrive() },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(text = "Export SOLO Samsung Health")
                    }

                    Text(
                        text = "Auto-Export: Programado en segundo plano",
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Text(
                        text = "Se ejecuta cada 30 min (aprox) de 5 AM a 9 PM.",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.caption
                    )

                    Text(
                        text = stringResource(id = R.string.previous_readings),
                        fontSize = 24.sp,
                        color = MaterialTheme.colors.primary
                    )
                }

                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                items(readingsList) { reading ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            modifier = Modifier.padding(2.dp).height(16.dp).width(16.dp),
                            painter = rememberDrawablePainter(drawable = reading.sourceAppInfo?.icon),
                            contentDescription = "App Icon"
                        )
                        Text(
                            text = "%.1f ${stringResource(id = R.string.kilograms)}"
                                .format(reading.weight.inKilograms)
                        )
                        Text(text = formatter.format(reading.time))
                        IconButton(onClick = { onDeleteClick(reading.id) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete_button_readings))
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(id = R.string.weekly_avg),
                        fontSize = 24.sp,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                    if (weeklyAvg == null) {
                        Text(text = "0.0")
                    } else {
                        Text(
                            text = "%.1f ${stringResource(id = R.string.kilograms)}"
                                .format(weeklyAvg.inKilograms)
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun InputReadingsScreenPreview() {
    val context = LocalContext.current
    val appInfo = HealthConnectAppInfo(
        packageName = "com.example.myfitnessapp",
        appLabel = "My Fitness App",
        icon = context.getDrawable(R.drawable.ic_launcher_foreground)!!
    )
    HealthConnectTheme(darkTheme = false) {
        InputReadingsScreen(
            permissions = setOf(),
            weeklyAvg = Mass.kilograms(54.5),
            permissionsGranted = true,
            readingsList = listOf(
                WeightData(
                    weight = Mass.kilograms(54.01231),
                    id = UUID.randomUUID().toString(),
                    time = ZonedDateTime.now(),
                    sourceAppInfo = appInfo
                ),
                WeightData(
                    weight = Mass.kilograms(55.578),
                    id = UUID.randomUUID().toString(),
                    time = ZonedDateTime.now().minusMinutes(5),
                    sourceAppInfo = appInfo
                )
            ),
            uiState = InputReadingsViewModel.UiState.Done
        )
    }
}