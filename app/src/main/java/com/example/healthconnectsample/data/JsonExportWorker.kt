package com.example.healthconnectsample.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.healthconnectsample.data.HealthDataSerializer.generateHealthJSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JsonExportWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "JsonExportWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🚀 JsonExportWorker INICIADO")
            Log.d(TAG, "========================================")

            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                Log.e(TAG, "❌ Health Connect no disponible")
                return@withContext Result.failure()
            }

            val healthConnectManager = HealthConnectManager(context)
            val endTime = Instant.now()
            val startTime = endTime.minus(Duration.ofDays(30))

            Log.d(TAG, "📅 Rango: ${startTime} a ${endTime}")

            withContext(Dispatchers.Main) {
                Log.d(TAG, "📊 Leyendo datos de Health Connect...")

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

                val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
                val samsungExercises = allExerciseRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val exerciseData = samsungExercises.mapNotNull { record ->
                    val durationMinutes = try {
                        Duration.between(record.startTime, record.endTime).toMinutes()
                    } catch (e: Exception) {
                        0L
                    }

                    if (durationMinutes < 2) return@mapNotNull null

                    val sessionData = try {
                        healthConnectManager.readAssociatedSessionData(record.metadata.id)
                    } catch (e: Exception) {
                        null
                    }

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

                val allSleepRecords = healthConnectManager.readSleepSessions(startTime, endTime)
                val samsungSleep = allSleepRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val sleepData = samsungSleep.map { record ->
                    val durationMinutes = try {
                        Duration.between(record.startTime, record.endTime).toMinutes()
                    } catch (e: Exception) {
                        0L
                    }

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

                val allVo2MaxRecords = healthConnectManager.readVo2MaxRecords(startTime, endTime)
                val samsungVo2Max = allVo2MaxRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val vo2maxData = samsungVo2Max.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "vo2_max_ml_per_min_per_kg" to record.vo2MillilitersPerMinuteKilogram,
                        "measurement_method" to record.measurementMethod,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

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

                val allOxygenSatRecords = healthConnectManager.readOxygenSaturationRecords(startTime, endTime)
                val samsungOxygenSat = allOxygenSatRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val oxygenSaturationData = samsungOxygenSat.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "percentage" to record.percentage.value,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

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

                val allBasalMetabolicRateRecords = healthConnectManager.readBasalMetabolicRateRecords(startTime, endTime)
                val samsungBasalMetabolicRate = allBasalMetabolicRateRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val basalMetabolicRateData = samsungBasalMetabolicRate.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "kcal_per_day" to record.basalMetabolicRate.inKilocaloriesPerDay,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                val allBodyWaterMassRecords = healthConnectManager.readBodyWaterMassRecords(startTime, endTime)
                val samsungBodyWaterMass = allBodyWaterMassRecords.filter {
                    it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
                }

                val bodyWaterMassData = samsungBodyWaterMass.map { record ->
                    mapOf(
                        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                        "mass_kg" to record.mass.inKilograms,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                }

                Log.d(TAG, "✅ Datos leídos exitosamente")
                Log.d(TAG, "   Weight: ${weightData.size}")
                Log.d(TAG, "   Exercise: ${exerciseData.size}")
                Log.d(TAG, "   Sleep: ${sleepData.size}")
                Log.d(TAG, "   VO2Max: ${vo2maxData.size}")
                Log.d(TAG, "   Steps: ${stepsData.size}")
                Log.d(TAG, "   Distance: ${distanceData.size}")
                Log.d(TAG, "   Total Calories: ${totalCaloriesData.size}")
                Log.d(TAG, "   Resting HR: ${restingHRData.size}")
                Log.d(TAG, "   Oxygen Sat: ${oxygenSaturationData.size}")
                Log.d(TAG, "   Height: ${heightData.size}")
                Log.d(TAG, "   Body Fat: ${bodyFatData.size}")
                Log.d(TAG, "   Lean Body Mass: ${leanBodyMassData.size}")
                Log.d(TAG, "   Bone Mass: ${boneMassData.size}")
                Log.d(TAG, "   Basal Metabolic Rate: ${basalMetabolicRateData.size}")
                Log.d(TAG, "   Body Water Mass: ${bodyWaterMassData.size}")

                val jsonContent = generateHealthJSON(
                    weightRecords = weightData,
                    exerciseData = exerciseData,
                    sleepData = sleepData,
                    vo2maxData = vo2maxData,
                    stepsData = stepsData,
                    distanceData = distanceData,
                    totalCaloriesData = totalCaloriesData,
                    restingHRData = restingHRData,
                    oxygenSaturationData = oxygenSaturationData,
                    heightData = heightData,
                    bodyFatData = bodyFatData,
                    leanBodyMassData = leanBodyMassData,
                    boneMassData = boneMassData,
                    basalMetabolicRateData = basalMetabolicRateData,
                    bodyWaterMassData = bodyWaterMassData,
                    exportType = "AUTO_SAMSUNG_ONLY_WORKER"
                )

                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
                val fileName = "health_data_SAMSUNG_AUTO_$timestamp.json"

                val tempFile = File(context.cacheDir, fileName)
                FileWriter(tempFile).use { writer ->
                    writer.write(jsonContent)
                }

                Log.d(TAG, "✅ Archivo JSON creado: ${tempFile.absolutePath}")

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile
                        )
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage("com.google.android.apps.docs")
                }

                context.startActivity(
                    Intent.createChooser(shareIntent, "Guardar en Google Drive").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )

                Log.d(TAG, "✅ Intent de compartir lanzado")
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "✅ JsonExportWorker COMPLETADO")
            Log.d(TAG, "========================================")

            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en JsonExportWorker", e)
            Log.e(TAG, "Stacktrace completo:", e)
            return@withContext Result.failure()
        }
    }
}
