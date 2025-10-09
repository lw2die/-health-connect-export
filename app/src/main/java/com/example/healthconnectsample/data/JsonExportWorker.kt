package com.example.healthconnectsample.worker

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.healthconnectsample.data.HealthConnectManager
import com.example.healthconnectsample.data.HealthDataSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JsonExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            val fileName = "health_data_SAMSUNG_AUTO_${timestamp}.json"

            try {
                val healthConnectManager = HealthConnectManager(applicationContext)
                val endTime = Instant.now()
                val startTime = endTime.minus(java.time.Duration.ofDays(30))

                // WEIGHT DATA
                val weightData = healthConnectManager.readWeightInputs(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                            "weight_kg" to record.weight.inKilograms,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                // EXERCISE DATA
                val exerciseData = healthConnectManager.readExerciseSessions(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .mapNotNull { record ->
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
                val sleepData = healthConnectManager.readSleepSessions(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
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
                val vo2maxData = healthConnectManager.readVo2MaxRecords(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                            "vo2_max_ml_per_min_per_kg" to record.vo2MillilitersPerMinuteKilogram,
                            "measurement_method" to record.measurementMethod,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                // STEPS DATA - v1.7.0
                val stepsData = healthConnectManager.readStepsRecords(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                            "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                            "count" to record.count,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                // DISTANCE DATA - v1.7.0
                val distanceData = healthConnectManager.readDistanceRecords(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                            "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                            "distance_meters" to record.distance.inMeters,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                // TOTAL CALORIES DATA - v1.7.0
                val totalCaloriesData = healthConnectManager.readTotalCaloriesBurnedRecords(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                            "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                            "energy_kcal" to record.energy.inKilocalories,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                // RESTING HEART RATE DATA - v1.7.0
                val restingHRData = healthConnectManager.readRestingHeartRateRecords(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                            "bpm" to record.beatsPerMinute,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                // OXYGEN SATURATION DATA - v1.7.0
                val oxygenSaturationData = healthConnectManager.readOxygenSaturationRecords(startTime, endTime)
                    .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                    .map { record ->
                        mapOf(
                            "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                            "percentage" to record.percentage.value,
                            "source" to record.metadata.dataOrigin.packageName
                        )
                    }

                val jsonContent = HealthDataSerializer.generateHealthJSON(
                    weightRecords = weightData,
                    exerciseData = exerciseData,
                    sleepData = sleepData,
                    vo2maxData = vo2maxData,
                    stepsData = stepsData,
                    distanceData = distanceData,
                    totalCaloriesData = totalCaloriesData,
                    restingHRData = restingHRData,
                    oxygenSaturationData = oxygenSaturationData,
                    exportType = "AUTO_SAMSUNG_ONLY_WORKER"
                )

                shareJSONFile(applicationContext, jsonContent, fileName)

                scheduleNext()
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.retry()
            }
        }
    }

    private fun scheduleNext() {
        val now = LocalDateTime.now()
        // Schedule logic here if needed
    }

    private fun shareJSONFile(context: Context, jsonContent: String, fileName: String) {
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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.docs")
        }

        context.startActivity(Intent.createChooser(shareIntent, "Guardar en Drive").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}