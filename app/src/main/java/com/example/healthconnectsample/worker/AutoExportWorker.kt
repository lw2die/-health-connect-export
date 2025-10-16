package com.example.healthconnectsample.worker

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
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

class AutoExportWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoExportWorker"
        private const val EXPORT_FOLDER = "HealthConnectExports"
    }

    private val tokenManager = ChangeTokenManager(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🚀 AutoExportWorker INICIADO")
            Log.d(TAG, "========================================")
            Log.d(TAG, "Iniciando export automático 24/7...")

            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                Log.e(TAG, "❌ Health Connect no disponible")
                return@withContext Result.retry()
            }

            val healthConnectClient = HealthConnectClient.getOrCreate(context)
            Log.d(TAG, "✅ HealthConnectClient creado")

            val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
            val requiredPermissions = setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(Vo2MaxRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
                HealthPermission.getReadPermission(HeightRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
                HealthPermission.getReadPermission(LeanBodyMassRecord::class),
                HealthPermission.getReadPermission(BoneMassRecord::class),
                HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
                HealthPermission.getReadPermission(BodyWaterMassRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getReadPermission(BloodGlucoseRecord::class)
            )

            Log.d(TAG, "Permisos otorgados: ${grantedPermissions.size}")
            Log.d(TAG, "Permisos requeridos: ${requiredPermissions.size}")

            if (!grantedPermissions.containsAll(requiredPermissions)) {
                Log.e(TAG, "❌ Permisos no otorgados")
                return@withContext Result.failure()
            }

            val isFirstExport = tokenManager.isFirstExport()
            Log.d(TAG, "¿Es primer export?: $isFirstExport")

            if (isFirstExport) {
                Log.d(TAG, "📦 Primera ejecución - Export completo + guardando token")
                performFullExport(healthConnectClient)
            } else {
                Log.d(TAG, "📝 Export incremental - Solo cambios desde último export")
                performDifferentialExport(healthConnectClient)
            }

            Log.d(TAG, "✅ Export completado exitosamente")
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en export automático", e)
            Log.e(TAG, "Stacktrace: ${e.stackTraceToString()}")
            return@withContext Result.retry()
        }
    }

    private suspend fun performFullExport(healthConnectClient: HealthConnectClient) {
        Log.d(TAG, "📦 Iniciando export COMPLETO (30 días)...")

        val healthConnectManager = HealthConnectManager(context)
        val endTime = Instant.now()
        val startTime = endTime.minus(java.time.Duration.ofDays(30))

        val weightData = readWeightRecords(healthConnectManager, startTime, endTime)
        val exerciseData = readExerciseRecords(healthConnectManager, startTime, endTime)
        val sleepData = readSleepRecords(healthConnectManager, startTime, endTime)
        val vo2MaxData = readVo2MaxRecords(healthConnectManager, startTime, endTime)
        val stepsData = readStepsRecords(healthConnectManager, startTime, endTime)
        val distanceData = readDistanceRecords(healthConnectManager, startTime, endTime)
        val totalCaloriesData = readTotalCaloriesRecords(healthConnectManager, startTime, endTime)
        val restingHRData = readRestingHeartRateRecords(healthConnectManager, startTime, endTime)
        val oxygenSatData = readOxygenSaturationRecords(healthConnectManager, startTime, endTime)
        val heightData = readHeightRecords(healthConnectManager, startTime, endTime)
        val bodyFatData = readBodyFatRecords(healthConnectManager, startTime, endTime)
        val leanBodyMassData = readLeanBodyMassRecords(healthConnectManager, startTime, endTime)
        val boneMassData = readBoneMassRecords(healthConnectManager, startTime, endTime)
        val basalMetabolicRateData = readBasalMetabolicRateRecords(healthConnectManager, startTime, endTime)
        val bodyWaterMassData = readBodyWaterMassRecords(healthConnectManager, startTime, endTime)
        val heartRateData = readHeartRateRecords(healthConnectManager, startTime, endTime)
        val bloodPressureData = readBloodPressureRecords(healthConnectManager, startTime, endTime)
        val bloodGlucoseData = readBloodGlucoseRecords(healthConnectManager, startTime, endTime)

        val jsonContent = generateHealthJSON(
            weightData,
            exerciseData,
            sleepData,
            vo2MaxData,
            stepsData,
            distanceData,
            totalCaloriesData,
            restingHRData,
            oxygenSatData,
            heightData,
            bodyFatData,
            leanBodyMassData,
            boneMassData,
            basalMetabolicRateData,
            bodyWaterMassData,
            heartRateData,
            bloodPressureData,
            bloodGlucoseData,
            "AUTO_FULL_EXPORT"
        )

        saveToFile(jsonContent, "FULL")

        val newToken = healthConnectClient.getChangesToken(
            ChangesTokenRequest(
                setOf(
                    WeightRecord::class,
                    ExerciseSessionRecord::class,
                    SleepSessionRecord::class,
                    Vo2MaxRecord::class,
                    StepsRecord::class,
                    DistanceRecord::class,
                    TotalCaloriesBurnedRecord::class,
                    RestingHeartRateRecord::class,
                    OxygenSaturationRecord::class,
                    HeightRecord::class,
                    BodyFatRecord::class,
                    LeanBodyMassRecord::class,
                    BoneMassRecord::class,
                    BasalMetabolicRateRecord::class,
                    BodyWaterMassRecord::class,
                    HeartRateRecord::class,
                    BloodPressureRecord::class,
                    BloodGlucoseRecord::class
                )
            )
        )
        tokenManager.saveToken(newToken)

        Log.d(TAG, "✅ Export completo: ${weightData.size}peso +${exerciseData.size}ej +${sleepData.size}sueño +${vo2MaxData.size}VO2 +${stepsData.size}pasos +${distanceData.size}dist +${totalCaloriesData.size}cal +${restingHRData.size}RHR +${oxygenSatData.size}SpO2 +${heightData.size}altura +${bodyFatData.size}grasa +${leanBodyMassData.size}magra +${boneMassData.size}hueso +${basalMetabolicRateData.size}BMR +${bodyWaterMassData.size}agua +${heartRateData.size}HR +${bloodPressureData.size}BP +${bloodGlucoseData.size}BG")
        Log.d(TAG, "✅ Nuevo token guardado")
    }

    private suspend fun performDifferentialExport(healthConnectClient: HealthConnectClient) {
        Log.d(TAG, "📝 Iniciando export DIFERENCIAL...")

        val token = tokenManager.getToken()
        if (token == null) {
            Log.e(TAG, "❌ Token no encontrado, haciendo export completo")
            performFullExport(healthConnectClient)
            return
        }

        Log.d(TAG, "Token actual: ${token.take(20)}...")

        val weightChanges = mutableListOf<Map<String, Any?>>()
        val exerciseChanges = mutableListOf<Map<String, Any?>>()
        val sleepChanges = mutableListOf<Map<String, Any?>>()
        val vo2maxChanges = mutableListOf<Map<String, Any?>>()
        val stepsChanges = mutableListOf<Map<String, Any?>>()
        val distanceChanges = mutableListOf<Map<String, Any?>>()
        val totalCaloriesChanges = mutableListOf<Map<String, Any?>>()
        val restingHRChanges = mutableListOf<Map<String, Any?>>()
        val oxygenSatChanges = mutableListOf<Map<String, Any?>>()
        val heightChanges = mutableListOf<Map<String, Any?>>()
        val bodyFatChanges = mutableListOf<Map<String, Any?>>()
        val leanBodyMassChanges = mutableListOf<Map<String, Any?>>()
        val boneMassChanges = mutableListOf<Map<String, Any?>>()
        val basalMetabolicRateChanges = mutableListOf<Map<String, Any?>>()
        val bodyWaterMassChanges = mutableListOf<Map<String, Any?>>()
        val heartRateChanges = mutableListOf<Map<String, Any?>>()
        val bloodPressureChanges = mutableListOf<Map<String, Any?>>()
        val bloodGlucoseChanges = mutableListOf<Map<String, Any?>>()
        val deletions = mutableListOf<String>()

        try {
            val changesResponse = healthConnectClient.getChanges(token)
            Log.d(TAG, "Changes obtenidos: ${changesResponse.changes.size}")

            changesResponse.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> {
                        when (val record = change.record) {
                            is WeightRecord -> {
                                weightChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "weight_kg" to record.weight.inKilograms,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Weight change detected")
                            }
                            is ExerciseSessionRecord -> {
                                val healthConnectManager = HealthConnectManager(context)
                                val sessionData = try {
                                    healthConnectManager.readAssociatedSessionData(record.metadata.id)
                                } catch (e: Exception) { null }

                                if (sessionData != null) {
                                    exerciseChanges.add(mapOf(
                                        "session_id" to record.metadata.id,
                                        "title" to (record.title ?: "Exercise"),
                                        "exercise_type" to record.exerciseType,
                                        "start_time" to record.startTime.toString(),
                                        "end_time" to record.endTime.toString(),
                                        "duration_minutes" to sessionData.totalActiveTime?.toMinutes(),
                                        "total_steps" to sessionData.totalSteps,
                                        "distance_meters" to sessionData.totalDistance?.inMeters,
                                        "calories_burned" to sessionData.totalEnergyBurned?.inCalories,
                                        "avg_heart_rate" to sessionData.avgHeartRate,
                                        "source" to record.metadata.dataOrigin.packageName,
                                        "change_type" to "UPSERT"
                                    ))
                                    Log.d(TAG, "  + Exercise change detected")
                                }
                            }
                            is SleepSessionRecord -> {
                                val stages = record.stages.map { stage ->
                                    mapOf(
                                        "stage_type" to stage.stage,
                                        "stage_name" to HealthDataSerializer.getSleepStageName(stage.stage),
                                        "start_time" to stage.startTime.toString(),
                                        "end_time" to stage.endTime.toString()
                                    )
                                }
                                sleepChanges.add(mapOf(
                                    "session_id" to record.metadata.id,
                                    "title" to (record.title ?: "Sleep"),
                                    "start_time" to record.startTime.toString(),
                                    "end_time" to record.endTime.toString(),
                                    "stages" to stages,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Sleep change detected")
                            }
                            is Vo2MaxRecord -> {
                                vo2maxChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "vo2_max" to record.vo2MillilitersPerMinuteKilogram,
                                    "measurement_method" to record.measurementMethod,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + VO2Max change detected")
                            }
                            is StepsRecord -> {
                                stepsChanges.add(mapOf(
                                    "start_time" to record.startTime.toString(),
                                    "end_time" to record.endTime.toString(),
                                    "count" to record.count,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Steps change detected")
                            }
                            is DistanceRecord -> {
                                distanceChanges.add(mapOf(
                                    "start_time" to record.startTime.toString(),
                                    "end_time" to record.endTime.toString(),
                                    "distance_meters" to record.distance.inMeters,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Distance change detected")
                            }
                            is TotalCaloriesBurnedRecord -> {
                                totalCaloriesChanges.add(mapOf(
                                    "start_time" to record.startTime.toString(),
                                    "end_time" to record.endTime.toString(),
                                    "energy_kcal" to record.energy.inKilocalories,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Total Calories change detected")
                            }
                            is RestingHeartRateRecord -> {
                                restingHRChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "bpm" to record.beatsPerMinute,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Resting HR change detected")
                            }
                            is OxygenSaturationRecord -> {
                                oxygenSatChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "percentage" to record.percentage.value,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Oxygen Saturation change detected")
                            }
                            is HeightRecord -> {
                                heightChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "height_meters" to record.height.inMeters,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Height change detected")
                            }
                            is BodyFatRecord -> {
                                bodyFatChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "percentage" to record.percentage.value,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Body Fat change detected")
                            }
                            is LeanBodyMassRecord -> {
                                leanBodyMassChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "mass_kg" to record.mass.inKilograms,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Lean Body Mass change detected")
                            }
                            is BoneMassRecord -> {
                                boneMassChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "mass_kg" to record.mass.inKilograms,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Bone Mass change detected")
                            }
                            is BasalMetabolicRateRecord -> {
                                basalMetabolicRateChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "kcal_per_day" to record.basalMetabolicRate.inKilocaloriesPerDay,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Basal Metabolic Rate change detected")
                            }
                            is BodyWaterMassRecord -> {
                                bodyWaterMassChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "mass_kg" to record.mass.inKilograms,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Body Water Mass change detected")
                            }
                            is HeartRateRecord -> {
                                val bpms = record.samples.map { it.beatsPerMinute }
                                heartRateChanges.add(mapOf(
                                    "start_time" to record.startTime.toString(),
                                    "end_time" to record.endTime.toString(),
                                    "samples_count" to record.samples.size,
                                    "avg_bpm" to if (bpms.isNotEmpty()) bpms.average().toLong() else null,
                                    "min_bpm" to if (bpms.isNotEmpty()) bpms.minOrNull() else null,
                                    "max_bpm" to if (bpms.isNotEmpty()) bpms.maxOrNull() else null,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Heart Rate change detected")
                            }
                            is BloodPressureRecord -> {
                                bloodPressureChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "systolic_mmhg" to record.systolic.inMillimetersOfMercury,
                                    "diastolic_mmhg" to record.diastolic.inMillimetersOfMercury,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                                Log.d(TAG, "  + Blood Pressure change detected")
                            }
                            is BloodGlucoseRecord -> {
                                try {
                                    val glucoseValue = record.level.inMillimolesPerLiter
                                    Log.d(TAG, "✅ Glucosa capturada: $glucoseValue mmol/L")
                                    bloodGlucoseChanges.add(mapOf(
                                        "timestamp" to record.time.toString(),
                                        "glucose_mmol_per_l" to glucoseValue,
                                        "specimen_source" to record.specimenSource,
                                        "source" to record.metadata.dataOrigin.packageName,
                                        "change_type" to "UPSERT"
                                    ))
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error al leer glucosa: ${e.javaClass.simpleName} - ${e.message}")
                                    Log.e(TAG, "Stacktrace: ${e.stackTraceToString()}")
                                    // Intenta guardar sin el valor
                                    bloodGlucoseChanges.add(mapOf(
                                        "timestamp" to record.time.toString(),
                                        "error_reading_glucose" to e.message,
                                        "specimen_source" to record.specimenSource,
                                        "source" to record.metadata.dataOrigin.packageName,
                                        "change_type" to "UPSERT"
                                    ))
                                }
                                Log.d(TAG, "  + Blood Glucose change detected")
                            }
                        }
                    }
                    is DeletionChange -> {
                        deletions.add(change.recordId)
                        Log.d(TAG, "  - Deletion detected: ${change.recordId}")
                    }
                }
            }

            if (weightChanges.isEmpty() && exerciseChanges.isEmpty() && sleepChanges.isEmpty() &&
                vo2maxChanges.isEmpty() && stepsChanges.isEmpty() && distanceChanges.isEmpty() &&
                totalCaloriesChanges.isEmpty() && restingHRChanges.isEmpty() && oxygenSatChanges.isEmpty() &&
                heightChanges.isEmpty() && bodyFatChanges.isEmpty() && leanBodyMassChanges.isEmpty() &&
                boneMassChanges.isEmpty() && basalMetabolicRateChanges.isEmpty() && bodyWaterMassChanges.isEmpty() &&
                heartRateChanges.isEmpty() && bloodPressureChanges.isEmpty() && bloodGlucoseChanges.isEmpty() && deletions.isEmpty()) {
                Log.d(TAG, "📭 No hay cambios nuevos")
                return
            }

            val jsonContent = generateDifferentialJSON(
                weightChanges,
                exerciseChanges,
                sleepChanges,
                vo2maxChanges,
                stepsChanges,
                distanceChanges,
                totalCaloriesChanges,
                restingHRChanges,
                oxygenSatChanges,
                heightChanges,
                bodyFatChanges,
                leanBodyMassChanges,
                boneMassChanges,
                basalMetabolicRateChanges,
                bodyWaterMassChanges,
                heartRateChanges,
                bloodPressureChanges,
                bloodGlucoseChanges,
                deletions
            )

            saveToFile(jsonContent, "DIFF")

            val newToken = changesResponse.nextChangesToken
            tokenManager.saveToken(newToken)

            Log.d(TAG, "✅ Export diferencial: ${weightChanges.size}peso +${exerciseChanges.size}ej +${sleepChanges.size}sueño +${vo2maxChanges.size}VO2 +${stepsChanges.size}pasos +${distanceChanges.size}dist +${totalCaloriesChanges.size}cal +${restingHRChanges.size}RHR +${oxygenSatChanges.size}SpO2 +${heightChanges.size}altura +${bodyFatChanges.size}grasa +${leanBodyMassChanges.size}magra +${boneMassChanges.size}hueso +${basalMetabolicRateChanges.size}BMR +${bodyWaterMassChanges.size}agua +${heartRateChanges.size}HR +${bloodPressureChanges.size}BP +${bloodGlucoseChanges.size}BG +${deletions.size}del")
            Log.d(TAG, "✅ Token actualizado")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en export diferencial, forzando export completo", e)
            tokenManager.clearToken()
            performFullExport(healthConnectClient)
        }
    }

    private suspend fun readWeightRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readWeightInputs(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "weight_kg" to record.weight.inKilograms,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading weight records", e)
            emptyList()
        }
    }

    private suspend fun readExerciseRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readExerciseSessions(startTime, endTime)
            records.mapNotNull { record ->
                val sessionData = try {
                    healthConnectManager.readAssociatedSessionData(record.metadata.id)
                } catch (e: Exception) { null }

                if (sessionData != null) {
                    mapOf(
                        "session_id" to record.metadata.id,
                        "title" to (record.title ?: "Exercise"),
                        "exercise_type" to record.exerciseType,
                        "start_time" to record.startTime.toString(),
                        "end_time" to record.endTime.toString(),
                        "duration_minutes" to sessionData.totalActiveTime?.toMinutes(),
                        "total_steps" to sessionData.totalSteps,
                        "distance_meters" to sessionData.totalDistance?.inMeters,
                        "calories_burned" to sessionData.totalEnergyBurned?.inCalories,
                        "avg_heart_rate" to sessionData.avgHeartRate,
                        "source" to record.metadata.dataOrigin.packageName
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading exercise records", e)
            emptyList()
        }
    }

    private suspend fun readSleepRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readSleepSessions(startTime, endTime)
            records.map { record ->
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
                    "title" to (record.title ?: "Sleep"),
                    "start_time" to record.startTime.toString(),
                    "end_time" to record.endTime.toString(),
                    "stages" to stages,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sleep records", e)
            emptyList()
        }
    }

    private suspend fun readVo2MaxRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readVo2MaxRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "vo2_max" to record.vo2MillilitersPerMinuteKilogram,
                    "measurement_method" to record.measurementMethod,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading VO2Max records", e)
            emptyList()
        }
    }

    private suspend fun readStepsRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readStepsRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "count" to record.count,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading steps records", e)
            emptyList()
        }
    }

    private suspend fun readDistanceRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readDistanceRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "distance_meters" to record.distance.inMeters,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading distance records", e)
            emptyList()
        }
    }

    private suspend fun readTotalCaloriesRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readTotalCaloriesBurnedRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "energy_kcal" to record.energy.inKilocalories,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading total calories records", e)
            emptyList()
        }
    }

    private suspend fun readRestingHeartRateRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readRestingHeartRateRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "bpm" to record.beatsPerMinute,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading resting heart rate records", e)
            emptyList()
        }
    }

    private suspend fun readOxygenSaturationRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readOxygenSaturationRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "percentage" to record.percentage.value,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading oxygen saturation records", e)
            emptyList()
        }
    }

    private suspend fun readHeightRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readHeightRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "height_meters" to record.height.inMeters,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading height records", e)
            emptyList()
        }
    }

    private suspend fun readBodyFatRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readBodyFatRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "percentage" to record.percentage.value,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading body fat records", e)
            emptyList()
        }
    }

    private suspend fun readLeanBodyMassRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readLeanBodyMassRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "mass_kg" to record.mass.inKilograms,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading lean body mass records", e)
            emptyList()
        }
    }

    private suspend fun readBoneMassRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readBoneMassRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "mass_kg" to record.mass.inKilograms,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading bone mass records", e)
            emptyList()
        }
    }

    private suspend fun readBasalMetabolicRateRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readBasalMetabolicRateRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "kcal_per_day" to record.basalMetabolicRate.inKilocaloriesPerDay,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading basal metabolic rate records", e)
            emptyList()
        }
    }

    private suspend fun readBodyWaterMassRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readBodyWaterMassRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "mass_kg" to record.mass.inKilograms,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading body water mass records", e)
            emptyList()
        }
    }

    private suspend fun readHeartRateRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readHeartRateRecords(startTime, endTime)
            records.map { record ->
                val bpms = record.samples.map { it.beatsPerMinute }
                mapOf(
                    "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
                    "samples_count" to record.samples.size,
                    "avg_bpm" to if (bpms.isNotEmpty()) bpms.average().toLong() else null,
                    "min_bpm" to if (bpms.isNotEmpty()) bpms.minOrNull() else null,
                    "max_bpm" to if (bpms.isNotEmpty()) bpms.maxOrNull() else null,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading heart rate records", e)
            emptyList()
        }
    }

    private suspend fun readBloodPressureRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readBloodPressureRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "systolic_mmhg" to record.systolic.inMillimetersOfMercury,
                    "diastolic_mmhg" to record.diastolic.inMillimetersOfMercury,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading blood pressure records", e)
            emptyList()
        }
    }

    private suspend fun readBloodGlucoseRecords(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readBloodGlucoseRecords(startTime, endTime)
            records.map { record ->
                mapOf(
                    "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
                    "glucose_mmol_per_l" to record.level.inMillimolesPerLiter,
                    "specimen_source" to record.specimenSource,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading blood glucose records", e)
            emptyList()
        }
    }

    private fun generateHealthJSON(
        weightData: List<Map<String, Any?>>,
        exerciseData: List<Map<String, Any?>>,
        sleepData: List<Map<String, Any?>>,
        vo2maxData: List<Map<String, Any?>>,
        stepsData: List<Map<String, Any?>>,
        distanceData: List<Map<String, Any?>>,
        totalCaloriesData: List<Map<String, Any?>>,
        restingHRData: List<Map<String, Any?>>,
        oxygenSatData: List<Map<String, Any?>>,
        heightData: List<Map<String, Any?>>,
        bodyFatData: List<Map<String, Any?>>,
        leanBodyMassData: List<Map<String, Any?>>,
        boneMassData: List<Map<String, Any?>>,
        basalMetabolicRateData: List<Map<String, Any?>>,
        bodyWaterMassData: List<Map<String, Any?>>,
        heartRateData: List<Map<String, Any?>>,
        bloodPressureData: List<Map<String, Any?>>,
        bloodGlucoseData: List<Map<String, Any?>>,
        exportType: String
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"$exportType\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Health Connect - All Sources\",\n")
            append("  \"weight_records\": {\n")
            append("    \"count\": ${weightData.size},\n")
            append("    \"data\": ${serializeMapList(weightData)}\n")
            append("  },\n")
            append("  \"exercise_sessions\": {\n")
            append("    \"count\": ${exerciseData.size},\n")
            append("    \"data\": ${serializeMapList(exerciseData)}\n")
            append("  },\n")
            append("  \"sleep_sessions\": {\n")
            append("    \"count\": ${sleepData.size},\n")
            append("    \"data\": ${serializeMapList(sleepData)}\n")
            append("  },\n")
            append("  \"vo2max_records\": {\n")
            append("    \"count\": ${vo2maxData.size},\n")
            append("    \"data\": ${serializeMapList(vo2maxData)}\n")
            append("  },\n")
            append("  \"steps_records\": {\n")
            append("    \"count\": ${stepsData.size},\n")
            append("    \"data\": ${serializeMapList(stepsData)}\n")
            append("  },\n")
            append("  \"distance_records\": {\n")
            append("    \"count\": ${distanceData.size},\n")
            append("    \"data\": ${serializeMapList(distanceData)}\n")
            append("  },\n")
            append("  \"total_calories_records\": {\n")
            append("    \"count\": ${totalCaloriesData.size},\n")
            append("    \"data\": ${serializeMapList(totalCaloriesData)}\n")
            append("  },\n")
            append("  \"resting_heart_rate_records\": {\n")
            append("    \"count\": ${restingHRData.size},\n")
            append("    \"data\": ${serializeMapList(restingHRData)}\n")
            append("  },\n")
            append("  \"oxygen_saturation_records\": {\n")
            append("    \"count\": ${oxygenSatData.size},\n")
            append("    \"data\": ${serializeMapList(oxygenSatData)}\n")
            append("  },\n")
            append("  \"height_records\": {\n")
            append("    \"count\": ${heightData.size},\n")
            append("    \"data\": ${serializeMapList(heightData)}\n")
            append("  },\n")
            append("  \"body_fat_records\": {\n")
            append("    \"count\": ${bodyFatData.size},\n")
            append("    \"data\": ${serializeMapList(bodyFatData)}\n")
            append("  },\n")
            append("  \"lean_body_mass_records\": {\n")
            append("    \"count\": ${leanBodyMassData.size},\n")
            append("    \"data\": ${serializeMapList(leanBodyMassData)}\n")
            append("  },\n")
            append("  \"bone_mass_records\": {\n")
            append("    \"count\": ${boneMassData.size},\n")
            append("    \"data\": ${serializeMapList(boneMassData)}\n")
            append("  },\n")
            append("  \"basal_metabolic_rate_records\": {\n")
            append("    \"count\": ${basalMetabolicRateData.size},\n")
            append("    \"data\": ${serializeMapList(basalMetabolicRateData)}\n")
            append("  },\n")
            append("  \"body_water_mass_records\": {\n")
            append("    \"count\": ${bodyWaterMassData.size},\n")
            append("    \"data\": ${serializeMapList(bodyWaterMassData)}\n")
            append("  },\n")
            append("  \"heart_rate_records\": {\n")
            append("    \"count\": ${heartRateData.size},\n")
            append("    \"data\": ${serializeMapList(heartRateData)}\n")
            append("  },\n")
            append("  \"blood_pressure_records\": {\n")
            append("    \"count\": ${bloodPressureData.size},\n")
            append("    \"data\": ${serializeMapList(bloodPressureData)}\n")
            append("  },\n")
            append("  \"blood_glucose_records\": {\n")
            append("    \"count\": ${bloodGlucoseData.size},\n")
            append("    \"data\": ${serializeMapList(bloodGlucoseData)}\n")
            append("  }\n")
            append("}")
        }
    }

    private fun generateDifferentialJSON(
        weightChanges: List<Map<String, Any?>>,
        exerciseChanges: List<Map<String, Any?>>,
        sleepChanges: List<Map<String, Any?>>,
        vo2maxChanges: List<Map<String, Any?>>,
        stepsChanges: List<Map<String, Any?>>,
        distanceChanges: List<Map<String, Any?>>,
        totalCaloriesChanges: List<Map<String, Any?>>,
        restingHRChanges: List<Map<String, Any?>>,
        oxygenSatChanges: List<Map<String, Any?>>,
        heightChanges: List<Map<String, Any?>>,
        bodyFatChanges: List<Map<String, Any?>>,
        leanBodyMassChanges: List<Map<String, Any?>>,
        boneMassChanges: List<Map<String, Any?>>,
        basalMetabolicRateChanges: List<Map<String, Any?>>,
        bodyWaterMassChanges: List<Map<String, Any?>>,
        heartRateChanges: List<Map<String, Any?>>,
        bloodPressureChanges: List<Map<String, Any?>>,
        bloodGlucoseChanges: List<Map<String, Any?>>,
        deletions: List<String>
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"AUTO_DIFFERENTIAL\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Health Connect - Changes Only\",\n")
            append("  \"weight_changes\": {\n")
            append("    \"count\": ${weightChanges.size},\n")
            append("    \"data\": ${serializeMapList(weightChanges)}\n")
            append("  },\n")
            append("  \"exercise_changes\": {\n")
            append("    \"count\": ${exerciseChanges.size},\n")
            append("    \"data\": ${serializeMapList(exerciseChanges)}\n")
            append("  },\n")
            append("  \"sleep_changes\": {\n")
            append("    \"count\": ${sleepChanges.size},\n")
            append("    \"data\": ${serializeMapList(sleepChanges)}\n")
            append("  },\n")
            append("  \"vo2max_changes\": {\n")
            append("    \"count\": ${vo2maxChanges.size},\n")
            append("    \"data\": ${serializeMapList(vo2maxChanges)}\n")
            append("  },\n")
            append("  \"steps_changes\": {\n")
            append("    \"count\": ${stepsChanges.size},\n")
            append("    \"data\": ${serializeMapList(stepsChanges)}\n")
            append("  },\n")
            append("  \"distance_changes\": {\n")
            append("    \"count\": ${distanceChanges.size},\n")
            append("    \"data\": ${serializeMapList(distanceChanges)}\n")
            append("  },\n")
            append("  \"total_calories_changes\": {\n")
            append("    \"count\": ${totalCaloriesChanges.size},\n")
            append("    \"data\": ${serializeMapList(totalCaloriesChanges)}\n")
            append("  },\n")
            append("  \"resting_heart_rate_changes\": {\n")
            append("    \"count\": ${restingHRChanges.size},\n")
            append("    \"data\": ${serializeMapList(restingHRChanges)}\n")
            append("  },\n")
            append("  \"oxygen_saturation_changes\": {\n")
            append("    \"count\": ${oxygenSatChanges.size},\n")
            append("    \"data\": ${serializeMapList(oxygenSatChanges)}\n")
            append("  },\n")
            append("  \"height_changes\": {\n")
            append("    \"count\": ${heightChanges.size},\n")
            append("    \"data\": ${serializeMapList(heightChanges)}\n")
            append("  },\n")
            append("  \"body_fat_changes\": {\n")
            append("    \"count\": ${bodyFatChanges.size},\n")
            append("    \"data\": ${serializeMapList(bodyFatChanges)}\n")
            append("  },\n")
            append("  \"lean_body_mass_changes\": {\n")
            append("    \"count\": ${leanBodyMassChanges.size},\n")
            append("    \"data\": ${serializeMapList(leanBodyMassChanges)}\n")
            append("  },\n")
            append("  \"bone_mass_changes\": {\n")
            append("    \"count\": ${boneMassChanges.size},\n")
            append("    \"data\": ${serializeMapList(boneMassChanges)}\n")
            append("  },\n")
            append("  \"basal_metabolic_rate_changes\": {\n")
            append("    \"count\": ${basalMetabolicRateChanges.size},\n")
            append("    \"data\": ${serializeMapList(basalMetabolicRateChanges)}\n")
            append("  },\n")
            append("  \"body_water_mass_changes\": {\n")
            append("    \"count\": ${bodyWaterMassChanges.size},\n")
            append("    \"data\": ${serializeMapList(bodyWaterMassChanges)}\n")
            append("  },\n")
            append("  \"heart_rate_changes\": {\n")
            append("    \"count\": ${heartRateChanges.size},\n")
            append("    \"data\": ${serializeMapList(heartRateChanges)}\n")
            append("  },\n")
            append("  \"blood_pressure_changes\": {\n")
            append("    \"count\": ${bloodPressureChanges.size},\n")
            append("    \"data\": ${serializeMapList(bloodPressureChanges)}\n")
            append("  },\n")
            append("  \"blood_glucose_changes\": {\n")
            append("    \"count\": ${bloodGlucoseChanges.size},\n")
            append("    \"data\": ${serializeMapList(bloodGlucoseChanges)}\n")
            append("  },\n")
            append("  \"deletions\": {\n")
            append("    \"count\": ${deletions.size},\n")
            append("    \"record_ids\": ${serializeStringList(deletions)}\n")
            append("  }\n")
            append("}")
        }
    }

    private fun serializeMapList(list: List<Map<String, Any?>>): String {
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
                        is List<*> -> append(serializeMapList(value as List<Map<String, Any?>>))
                        null -> append("null")
                        else -> append("\"$value\"")
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

    private fun serializeStringList(list: List<String>): String {
        if (list.isEmpty()) return "[]"
        return buildString {
            append("[")
            list.forEachIndexed { index, item ->
                append("\"$item\"")
                if (index < list.size - 1) append(", ")
            }
            append("]")
        }
    }

    private fun saveToFile(content: String, type: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportDir = File(downloadsDir, EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val fileName = "health_data_AUTO_${type}_$timestamp.json"
            val file = File(exportDir, fileName)

            FileWriter(file).use { it.write(content) }

            Log.d(TAG, "✅ Archivo guardado: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando archivo", e)
        }
    }
}

class ChangeTokenManager(context: Context) {
    private val prefs = context.getSharedPreferences("health_connect_prefs", Context.MODE_PRIVATE)
    private val TOKEN_KEY = "changes_token"

    fun saveToken(token: String) {
        prefs.edit().putString(TOKEN_KEY, token).apply()
    }

    fun getToken(): String? {
        return prefs.getString(TOKEN_KEY, null)
    }

    fun clearToken() {
        prefs.edit().remove(TOKEN_KEY).apply()
    }

    fun isFirstExport(): Boolean {
        return getToken() == null
    }
}