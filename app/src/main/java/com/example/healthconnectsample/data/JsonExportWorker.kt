package com.example.healthconnectsample.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class JsonExportWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val healthConnectManager = HealthConnectManager(appContext)

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // La lógica de exportación es la misma
                val endTime = Instant.now()
                val startTime = endTime.minusSeconds(30L * 24 * 60 * 60) // Últimos 30 días

                val exerciseData = readExerciseSessionsFiltered(startTime, endTime)
                val weightData = readWeightRecordsFiltered(startTime)

                if (exerciseData.isNotEmpty() || weightData.isNotEmpty()) {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                    val fileName = "health_data_SAMSUNG_AUTO_${timestamp}.json"
                    val jsonContent = generateSimpleHealthJSON(weightData, exerciseData, "AUTO_SAMSUNG_ONLY_WORKER")
                    shareJSONFile(appContext, jsonContent, fileName)
                }

                // --- ¡NUEVO! --- Al terminar, programar la siguiente ejecución
                scheduleNext()

                Result.success()

            } catch (e: Exception) {
                e.printStackTrace()
                // Si falla, reintentar más tarde (WorkManager lo maneja)
                Result.retry()
            }
        }
    }

    private fun scheduleNext() {
        val now = LocalDateTime.now()
        val startTimeWindow = LocalTime.of(5, 0) // 5:00 AM
        val endTimeWindow = LocalTime.of(21, 0)  // 9:00 PM (21:00)

        var nextRunDelayMinutes: Long

        if (now.toLocalTime().isBefore(startTimeWindow)) {
            // Caso 1: Es de madrugada (antes de las 5 AM)
            // Programar para las 5 AM de hoy
            val nextRunTime = now.with(startTimeWindow)
            nextRunDelayMinutes = Duration.between(now, nextRunTime).toMinutes()
        } else if (now.toLocalTime().isAfter(endTimeWindow)) {
            // Caso 2: Es de noche (después de las 9 PM)
            // Programar para las 5 AM de MAÑANA
            val nextRunTime = now.plusDays(1).with(startTimeWindow)
            nextRunDelayMinutes = Duration.between(now, nextRunTime).toMinutes()
        } else {
            // Caso 3: Estamos dentro de la ventana (5 AM - 9 PM)
            // Programar para dentro de 30 minutos
            nextRunDelayMinutes = 30
        }

        // Asegurarnos de que el delay no sea negativo
        if (nextRunDelayMinutes < 0) nextRunDelayMinutes = 0

        val nextWorkRequest = OneTimeWorkRequestBuilder<JsonExportWorker>()
            .setInitialDelay(nextRunDelayMinutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(appContext).enqueue(nextWorkRequest)
    }

    // --- Lógica de lectura y generación de JSON (sin cambios) ---

    private suspend fun readExerciseSessionsFiltered(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
        val samsungHealthRecords = allExerciseRecords.filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }

        val exerciseSessions = mutableListOf<Map<String, Any?>>()
        samsungHealthRecords.forEach { exerciseRecord ->
            val durationMinutes = java.time.Duration.between(exerciseRecord.startTime, exerciseRecord.endTime).toMinutes()
            if (durationMinutes >= 2) {
                val sessionData = try {
                    healthConnectManager.readAssociatedSessionData(exerciseRecord.metadata.id)
                } catch (e: Exception) { null }

                exerciseSessions.add(mapOf(
                    "session_id" to exerciseRecord.metadata.id,
                    "title" to (exerciseRecord.title ?: "Exercise Session"),
                    "exercise_type" to exerciseRecord.exerciseType.toString(),
                    "start_time" to exerciseRecord.startTime.toString(),
                    "end_time" to exerciseRecord.endTime.toString(),
                    "duration_minutes" to (sessionData?.totalActiveTime?.toMinutes() ?: durationMinutes),
                    "total_steps" to sessionData?.totalSteps,
                    "distance_meters" to sessionData?.totalDistance?.inMeters,
                    "calories_burned" to sessionData?.totalEnergyBurned?.inCalories,
                    "avg_heart_rate" to sessionData?.avgHeartRate,
                    "max_heart_rate" to sessionData?.maxHeartRate,
                    "min_heart_rate" to sessionData?.minHeartRate,
                    "data_origin" to exerciseRecord.metadata.dataOrigin.packageName,
                    "has_detailed_data" to (sessionData != null)
                ))
            }
        }
        return exerciseSessions
    }

    private suspend fun readWeightRecordsFiltered(startTime: Instant): List<WeightData> {
        val allWeightRecords = healthConnectManager.readWeightInputs(startTime, Instant.now())
        val sourceApps = healthConnectManager.healthConnectCompatibleApps
        return allWeightRecords
            .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
            .map { record ->
                WeightData(
                    id = record.metadata.id,
                    weight = record.weight,
                    time = record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()),
                    sourceAppInfo = sourceApps[record.metadata.dataOrigin.packageName]
                )
            }
    }

    private fun generateSimpleHealthJSON(
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
                append("        \"source\": \"${record.sourceAppInfo?.packageName ?: "com.sec.android.app.shealth"}\"\n")
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

    private fun shareJSONFile(context: Context, jsonContent: String, fileName: String) {
        try {
            val tempFile = File(context.cacheDir, fileName)
            FileWriter(tempFile).use { it.write(jsonContent) }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                ))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.google.android.apps.docs")
            }

            context.startActivity(shareIntent)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}