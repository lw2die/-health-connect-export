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
                val endTime = Instant.now()
                val startTime = endTime.minusSeconds(30L * 24 * 60 * 60)

                val exerciseData = readExerciseSessionsFiltered(startTime, endTime)
                val sleepData = readSleepSessionsFiltered(startTime, endTime)
                val weightData = readWeightRecordsFiltered(startTime)

                if (exerciseData.isNotEmpty() || sleepData.isNotEmpty() || weightData.isNotEmpty()) {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                    val fileName = "health_data_SAMSUNG_AUTO_${timestamp}.json"

                    val jsonContent = HealthDataSerializer.generateHealthJSON(
                        weightData,
                        exerciseData,
                        sleepData,
                        "AUTO_SAMSUNG_ONLY_WORKER"
                    )

                    shareJSONFile(appContext, jsonContent, fileName)
                }

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
        val startTimeWindow = LocalTime.of(5, 0)
        val endTimeWindow = LocalTime.of(21, 0)

        var nextRunDelayMinutes: Long

        if (now.toLocalTime().isBefore(startTimeWindow)) {
            val nextRunTime = now.with(startTimeWindow)
            nextRunDelayMinutes = Duration.between(now, nextRunTime).toMinutes()
        } else if (now.toLocalTime().isAfter(endTimeWindow)) {
            val nextRunTime = now.plusDays(1).with(startTimeWindow)
            nextRunDelayMinutes = Duration.between(now, nextRunTime).toMinutes()
        } else {
            nextRunDelayMinutes = 30
        }

        if (nextRunDelayMinutes < 0) nextRunDelayMinutes = 0

        val nextWorkRequest = OneTimeWorkRequestBuilder<JsonExportWorker>()
            .setInitialDelay(nextRunDelayMinutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(appContext).enqueue(nextWorkRequest)
    }

    private suspend fun readExerciseSessionsFiltered(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
        val samsungHealthRecords = allExerciseRecords.filter {
            it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
        }

        val exerciseSessions = mutableListOf<Map<String, Any?>>()

        samsungHealthRecords.forEach { exerciseRecord ->
            val durationMinutes = java.time.Duration.between(
                exerciseRecord.startTime,
                exerciseRecord.endTime
            ).toMinutes()

            if (durationMinutes >= 2) {
                val sessionData = try {
                    healthConnectManager.readAssociatedSessionData(exerciseRecord.metadata.id)
                } catch (e: Exception) { null }

                exerciseSessions.add(mapOf(
                    "session_id" to exerciseRecord.metadata.id,
                    "title" to (exerciseRecord.title ?: "Exercise Session"),
                    "exercise_type" to exerciseRecord.exerciseType,
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

    private suspend fun readSleepSessionsFiltered(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        val allSleepRecords = healthConnectManager.readSleepSessions(startTime, endTime)
        val samsungHealthRecords = allSleepRecords.filter {
            it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
        }

        return samsungHealthRecords.map { sleepRecord ->
            val durationMinutes = java.time.Duration.between(
                sleepRecord.startTime,
                sleepRecord.endTime
            ).toMinutes()

            val stages = sleepRecord.stages.map { stage ->
                mapOf(
                    "stage_type" to stage.stage,
                    "stage_name" to HealthDataSerializer.getSleepStageName(stage.stage),
                    "start_time" to stage.startTime.toString(),
                    "end_time" to stage.endTime.toString()
                )
            }

            mapOf(
                "session_id" to sleepRecord.metadata.id,
                "title" to (sleepRecord.title ?: "Sleep Session"),
                "notes" to sleepRecord.notes,
                "start_time" to sleepRecord.startTime.toString(),
                "end_time" to sleepRecord.endTime.toString(),
                "duration_minutes" to durationMinutes,
                "stages_count" to stages.size,
                "stages" to stages,
                "data_origin" to sleepRecord.metadata.dataOrigin.packageName
            )
        }
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