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
                // v1.7.0 - Essential Metrics
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class)
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

    private suspend fun performFullExport(client: HealthConnectClient) {
        Log.d(TAG, "Iniciando FULL export...")
        val healthConnectManager = HealthConnectManager(context)

        val endTime = Instant.now()
        val startTime = Instant.EPOCH

        val exerciseData = readExerciseSessions(healthConnectManager, startTime, endTime)
        val weightData = readWeightRecords(client, startTime, endTime)
        val vo2maxData = readVo2MaxRecords(healthConnectManager, startTime, endTime)
        // v1.7.0 - Essential Metrics
        val stepsData = readStepsRecords(healthConnectManager, startTime, endTime)
        val distanceData = readDistanceRecords(healthConnectManager, startTime, endTime)
        val totalCaloriesData = readTotalCaloriesRecords(healthConnectManager, startTime, endTime)
        val restingHRData = readRestingHeartRateRecords(healthConnectManager, startTime, endTime)
        val oxygenSatData = readOxygenSaturationRecords(healthConnectManager, startTime, endTime)

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val fileName = "health_data_AUTO_FULL_${timestamp}.json"
        val jsonContent = generateHealthJSON(
            weightData,
            exerciseData,
            vo2maxData,
            stepsData,
            distanceData,
            totalCaloriesData,
            restingHRData,
            oxygenSatData,
            "AUTO_FULL_EXPORT"
        )

        saveToDownloads(fileName, jsonContent)

        val token = client.getChangesToken(
            ChangesTokenRequest(
                recordTypes = setOf(
                    WeightRecord::class,
                    ExerciseSessionRecord::class,
                    SleepSessionRecord::class,
                    Vo2MaxRecord::class,
                    StepsRecord::class,
                    DistanceRecord::class,
                    TotalCaloriesBurnedRecord::class,
                    RestingHeartRateRecord::class,
                    OxygenSaturationRecord::class
                )
            )
        )
        tokenManager.saveChangesToken(token)

        Log.d(TAG, "✅ Export completo: ${weightData.size} weight + ${exerciseData.size} exercises + ${vo2maxData.size} VO2Max + ${stepsData.size} steps + ${distanceData.size} distance + ${totalCaloriesData.size} calories + ${restingHRData.size} RHR + ${oxygenSatData.size} SpO2")
        Log.d(TAG, "Token guardado para próximos exports incrementales")
    }

    private suspend fun performDifferentialExport(client: HealthConnectClient) {
        Log.d(TAG, "Iniciando DIFFERENTIAL export...")

        val savedToken = tokenManager.getChangesToken() ?: run {
            Log.e(TAG, "Token no encontrado, forzando export completo")
            performFullExport(client)
            return
        }

        Log.d(TAG, "Token encontrado, leyendo cambios...")
        val changesResponse = client.getChanges(savedToken)
        Log.d(TAG, "Cambios recibidos: ${changesResponse.changes.size}")

        val weightChanges = mutableListOf<Map<String, Any?>>()
        val exerciseChanges = mutableListOf<Map<String, Any?>>()
        val sleepChanges = mutableListOf<Map<String, Any?>>()
        val vo2maxChanges = mutableListOf<Map<String, Any?>>()
        // v1.7.0 - Essential Metrics
        val stepsChanges = mutableListOf<Map<String, Any?>>()
        val distanceChanges = mutableListOf<Map<String, Any?>>()
        val totalCaloriesChanges = mutableListOf<Map<String, Any?>>()
        val restingHRChanges = mutableListOf<Map<String, Any?>>()
        val oxygenSatChanges = mutableListOf<Map<String, Any?>>()
        val deletions = mutableListOf<String>()

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
                        is Vo2MaxRecord -> {
                            vo2maxChanges.add(mapOf(
                                "timestamp" to record.time.toString(),
                                "vo2_ml_kg_min" to record.vo2MillilitersPerMinuteKilogram,
                                "measurement_method" to getMeasurementMethodName(record.measurementMethod),
                                "source" to record.metadata.dataOrigin.packageName,
                                "change_type" to "UPSERT"
                            ))
                            Log.d(TAG, "  + VO2Max change detected")
                        }
                        // v1.7.0 - Essential Metrics
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
                            Log.d(TAG, "  + Resting Heart Rate change detected")
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
                        is ExerciseSessionRecord -> {
                            val durationMinutes = try {
                                java.time.Duration.between(record.startTime, record.endTime).toMinutes()
                            } catch (e: Exception) { 0L }

                            if (durationMinutes >= 2) {
                                val healthConnectManager = HealthConnectManager(context)
                                val sessionData = try {
                                    healthConnectManager.readAssociatedSessionData(record.metadata.id)
                                } catch (e: Exception) { null }

                                if (sessionData != null) {
                                    exerciseChanges.add(mapOf(
                                        "session_id" to record.metadata.id,
                                        "title" to (record.title ?: "Exercise Session"),
                                        "exercise_type" to record.exerciseType,
                                        "exercise_type_name" to getExerciseTypeName(record.exerciseType),
                                        "exercise_emoji" to getExerciseTypeEmoji(record.exerciseType),
                                        "start_time" to record.startTime.toString(),
                                        "end_time" to record.endTime.toString(),
                                        "duration_minutes" to (sessionData.totalActiveTime?.toMinutes() ?: durationMinutes),
                                        "total_steps" to sessionData.totalSteps,
                                        "distance_meters" to sessionData.totalDistance?.inMeters,
                                        "calories_burned" to sessionData.totalEnergyBurned?.inCalories,
                                        "avg_heart_rate" to sessionData.avgHeartRate,
                                        "max_heart_rate" to sessionData.maxHeartRate,
                                        "min_heart_rate" to sessionData.minHeartRate,
                                        "data_origin" to record.metadata.dataOrigin.packageName,
                                        "has_detailed_data" to true,
                                        "change_type" to "UPSERT"
                                    ))
                                    Log.d(TAG, "  + Exercise change detected")
                                }
                            }
                        }
                        is SleepSessionRecord -> {
                            val durationMinutes = try {
                                java.time.Duration.between(record.startTime, record.endTime).toMinutes()
                            } catch (e: Exception) { 0L }

                            sleepChanges.add(mapOf(
                                "session_id" to record.metadata.id,
                                "start_time" to record.startTime.toString(),
                                "end_time" to record.endTime.toString(),
                                "duration_minutes" to durationMinutes,
                                "title" to (record.title ?: "Sleep Session"),
                                "notes" to record.notes,
                                "stages" to record.stages.map { stage ->
                                    mapOf(
                                        "start_time" to stage.startTime.toString(),
                                        "end_time" to stage.endTime.toString(),
                                        "stage_type" to stage.stage,
                                        "stage_name" to HealthDataSerializer.getSleepStageName(stage.stage)
                                    )
                                },
                                "source" to record.metadata.dataOrigin.packageName,
                                "change_type" to "UPSERT"
                            ))
                            Log.d(TAG, "  + Sleep change detected")
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
            deletions.isEmpty()) {
            Log.d(TAG, "ℹ️ No hay cambios desde último export")
            tokenManager.saveChangesToken(changesResponse.nextChangesToken)
            return
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val fileName = "health_data_AUTO_DIFF_${timestamp}.json"
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
            deletions
        )

        saveToDownloads(fileName, jsonContent)
        tokenManager.saveChangesToken(changesResponse.nextChangesToken)

        Log.d(TAG, "✅ Export diferencial: ${weightChanges.size} weight + ${exerciseChanges.size} exercises + ${sleepChanges.size} sleep + ${vo2maxChanges.size} VO2Max + ${stepsChanges.size} steps + ${distanceChanges.size} distance + ${totalCaloriesChanges.size} calories + ${restingHRChanges.size} RHR + ${oxygenSatChanges.size} SpO2 + ${deletions.size} deletions")
    }

    private fun saveToDownloads(fileName: String, content: String) {
        try {
            Log.d(TAG, "Guardando archivo: $fileName")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportFolder = File(downloadsDir, EXPORT_FOLDER)

            if (!exportFolder.exists()) {
                exportFolder.mkdirs()
                Log.d(TAG, "Carpeta creada: ${exportFolder.absolutePath}")
            }

            val file = File(exportFolder, fileName)
            FileWriter(file).use { writer ->
                writer.write(content)
            }

            Log.d(TAG, "✅ Archivo guardado: ${file.absolutePath}")
            Log.d(TAG, "📍 Ubicación: Downloads/$EXPORT_FOLDER/$fileName")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando archivo localmente", e)
            throw e
        }
    }

    private suspend fun readExerciseSessions(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
            val exerciseSessions = mutableListOf<Map<String, Any?>>()

            allExerciseRecords.forEach { exerciseRecord ->
                val durationMinutes = try {
                    java.time.Duration.between(exerciseRecord.startTime, exerciseRecord.endTime).toMinutes()
                } catch (e: Exception) { 0L }

                if (durationMinutes < 2) return@forEach

                val sessionData = try {
                    healthConnectManager.readAssociatedSessionData(exerciseRecord.metadata.id)
                } catch (e: Exception) { null }

                if (sessionData == null) return@forEach

                val exerciseType = exerciseRecord.exerciseType
                val typeName = getExerciseTypeName(exerciseType)
                val typeEmoji = getExerciseTypeEmoji(exerciseType)

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

            exerciseSessions
        } catch (e: Exception) {
            Log.e(TAG, "Error reading exercises", e)
            emptyList()
        }
    }

    private suspend fun readWeightRecords(
        client: HealthConnectClient,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val request = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = client.readRecords(request)

            response.records.map { record ->
                mapOf(
                    "timestamp" to record.time.toString(),
                    "weight_kg" to record.weight.inKilograms,
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading weight records", e)
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
                    "timestamp" to record.time.toString(),
                    "vo2_ml_kg_min" to record.vo2MillilitersPerMinuteKilogram,
                    "measurement_method" to getMeasurementMethodName(record.measurementMethod),
                    "source" to record.metadata.dataOrigin.packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading VO2Max records", e)
            emptyList()
        }
    }

    // v1.7.0 - NUEVAS FUNCIONES PARA 5 MÉTRICAS ESENCIALES

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
            Log.e(TAG, "Error reading Steps records", e)
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
            Log.e(TAG, "Error reading Distance records", e)
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
            Log.e(TAG, "Error reading Total Calories records", e)
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
            Log.e(TAG, "Error reading Resting Heart Rate records", e)
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
            Log.e(TAG, "Error reading Oxygen Saturation records", e)
            emptyList()
        }
    }

    private fun getMeasurementMethodName(method: Int): String {
        return when (method) {
            Vo2MaxRecord.MEASUREMENT_METHOD_OTHER -> "Other"
            Vo2MaxRecord.MEASUREMENT_METHOD_METABOLIC_CART -> "Metabolic Cart"
            Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO -> "Heart Rate Ratio"
            Vo2MaxRecord.MEASUREMENT_METHOD_COOPER_TEST -> "Cooper Test"
            Vo2MaxRecord.MEASUREMENT_METHOD_MULTISTAGE_FITNESS_TEST -> "Multistage Fitness Test"
            Vo2MaxRecord.MEASUREMENT_METHOD_ROCKPORT_FITNESS_TEST -> "Rockport Fitness Test"
            else -> "Unknown"
        }
    }

    private fun generateHealthJSON(
        weightRecords: List<Map<String, Any?>>,
        exerciseData: List<Map<String, Any?>>,
        vo2maxData: List<Map<String, Any?>>,
        stepsData: List<Map<String, Any?>>,
        distanceData: List<Map<String, Any?>>,
        totalCaloriesData: List<Map<String, Any?>>,
        restingHRData: List<Map<String, Any?>>,
        oxygenSatData: List<Map<String, Any?>>,
        exportType: String
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"$exportType\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"All Apps (No Filter - Filter in Python)\",\n")
            append("  \"storage_location\": \"Local - Downloads/$EXPORT_FOLDER\",\n")
            append("  \"auto_sync_compatible\": true,\n")

            // Weight records
            append("  \"weight_records\": {\n")
            append("    \"count\": ${weightRecords.size},\n")
            append("    \"data\": ${serializeMapList(weightRecords)}\n")
            append("  },\n")

            // Exercise sessions
            append("  \"exercise_sessions\": {\n")
            append("    \"count\": ${exerciseData.size},\n")
            append("    \"data\": ${serializeMapList(exerciseData)}\n")
            append("  },\n")

            // VO2Max records
            append("  \"vo2max_records\": {\n")
            append("    \"count\": ${vo2maxData.size},\n")
            append("    \"data\": ${serializeMapList(vo2maxData)}\n")
            append("  },\n")

            // v1.7.0 - Essential Metrics
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
        deletions: List<String>
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"AUTO_DIFFERENTIAL\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"All Apps (No Filter - Filter in Python)\",\n")
            append("  \"storage_location\": \"Local - Downloads/$EXPORT_FOLDER\",\n")
            append("  \"auto_sync_compatible\": true,\n")
            append("  \"last_export_time\": \"${java.time.Instant.ofEpochMilli(tokenManager.getLastExportTime())}\",\n")

            // Weight changes
            append("  \"weight_changes\": {\n")
            append("    \"count\": ${weightChanges.size},\n")
            append("    \"data\": ${serializeMapList(weightChanges)}\n")
            append("  },\n")

            // Exercise changes
            append("  \"exercise_changes\": {\n")
            append("    \"count\": ${exerciseChanges.size},\n")
            append("    \"data\": ${serializeMapList(exerciseChanges)}\n")
            append("  },\n")

            // Sleep changes
            append("  \"sleep_changes\": {\n")
            append("    \"count\": ${sleepChanges.size},\n")
            append("    \"data\": ${serializeMapListWithNested(sleepChanges)}\n")
            append("  },\n")

            // VO2Max changes
            append("  \"vo2max_changes\": {\n")
            append("    \"count\": ${vo2maxChanges.size},\n")
            append("    \"data\": ${serializeMapList(vo2maxChanges)}\n")
            append("  },\n")

            // v1.7.0 - Essential Metrics changes
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

            // Deletions
            append("  \"deletions\": {\n")
            append("    \"count\": ${deletions.size},\n")
            append("    \"record_ids\": [\n")
            deletions.forEachIndexed { index, id ->
                append("      \"$id\"")
                if (index < deletions.size - 1) append(",")
                append("\n")
            }
            append("    ]\n")
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
                        is Double -> append(String.format("%.2f", value))
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

    private fun serializeMapListWithNested(list: List<Map<String, Any?>>): String {
        if (list.isEmpty()) return "[]"
        return buildString {
            append("[\n")
            list.forEachIndexed { index, map ->
                append("      {\n")
                map.entries.forEachIndexed { entryIndex, (key, value) ->
                    append("        \"$key\": ")
                    when (value) {
                        is String -> append("\"$value\"")
                        is List<*> -> {
                            val stages = value as List<Map<String, Any?>>
                            append("[\n")
                            stages.forEachIndexed { stageIndex, stage ->
                                append("          {")
                                stage.entries.forEachIndexed { sEntryIndex, (k, v) ->
                                    append("\"$k\": ")
                                    when (v) {
                                        is String -> append("\"$v\"")
                                        else -> append("\"$v\"")
                                    }
                                    if (sEntryIndex < stage.size - 1) append(", ")
                                }
                                append("}")
                                if (stageIndex < stages.size - 1) append(",")
                                append("\n")
                            }
                            append("        ]")
                        }
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

    private fun getExerciseTypeName(exerciseType: Int): String {
        return when (exerciseType) {
            0 -> "Unknown"
            4 -> "Biking"
            33 -> "Running"
            45 -> "Strength Training"
            49 -> "Swimming (Pool)"
            53 -> "Walking"
            57 -> "Yoga"
            else -> "Other ($exerciseType)"
        }
    }

    private fun getExerciseTypeEmoji(exerciseType: Int): String {
        return when (exerciseType) {
            4 -> "🚴"
            33 -> "🏃"
            45 -> "💪"
            49 -> "🏊"
            53 -> "🚶"
            57 -> "🧘"
            else -> "🏋️"
        }
    }
}