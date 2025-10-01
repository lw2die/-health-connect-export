package com.example.healthconnectsample.worker

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.healthconnectsample.data.HealthConnectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
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
            Log.d(TAG, "Iniciando export automático 24/7...")

            // Verificar Health Connect
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                Log.e(TAG, "Health Connect no disponible")
                return@withContext Result.retry()
            }

            val healthConnectClient = HealthConnectClient.getOrCreate(context)

            // Verificar permisos
            val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
            val requiredPermissions = setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class)
            )

            if (!grantedPermissions.containsAll(requiredPermissions)) {
                Log.e(TAG, "Permisos no otorgados")
                return@withContext Result.failure()
            }

            // LÓGICA DIFFERENTIAL: Primera vez vs cambios incrementales
            val isFirstExport = tokenManager.isFirstExport()

            if (isFirstExport) {
                Log.d(TAG, "Primera ejecución - Export completo + guardando token")
                performFullExport(healthConnectClient)
            } else {
                Log.d(TAG, "Export incremental - Solo cambios desde último export")
                performDifferentialExport(healthConnectClient)
            }

            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Error en export automático", e)
            return@withContext Result.retry()
        }
    }

    /**
     * Primera vez: Export completo + guarda token para próximas veces
     */
    private suspend fun performFullExport(client: HealthConnectClient) {
        val healthConnectManager = HealthConnectManager(context)

        val endTime = Instant.now()
        val startTime = Instant.EPOCH

        // Leer todos los datos
        val exerciseData = readExerciseSessionsFiltered(healthConnectManager, startTime, endTime)
        val weightData = readWeightRecordsFiltered(client, startTime, endTime)

        // Generar JSON completo
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val fileName = "health_data_AUTO_FULL_${timestamp}.json"
        val jsonContent = generateHealthJSON(weightData, exerciseData, "AUTO_FULL_EXPORT")

        // Guardar en Downloads/HealthConnectExports
        saveToDownloads(fileName, jsonContent)

        // IMPORTANTE: Obtener y guardar token para próximas veces
        val token = client.getChangesToken(
            ChangesTokenRequest(
                recordTypes = setOf(WeightRecord::class, ExerciseSessionRecord::class)
            )
        )
        tokenManager.saveChangesToken(token)

        Log.d(TAG, "Export completo: ${weightData.size} weight + ${exerciseData.size} exercises")
        Log.d(TAG, "Token guardado para próximos exports incrementales")
        Log.d(TAG, "Archivo guardado: Downloads/$EXPORT_FOLDER/$fileName")
    }

    /**
     * Siguientes veces: Solo cambios desde último export
     */
    private suspend fun performDifferentialExport(client: HealthConnectClient) {
        val savedToken = tokenManager.getChangesToken() ?: run {
            Log.e(TAG, "Token no encontrado, forzando export completo")
            performFullExport(client)
            return
        }

        // Obtener cambios desde último export
        val changesResponse = client.getChanges(savedToken)

        val weightChanges = mutableListOf<Map<String, Any?>>()
        val exerciseChanges = mutableListOf<Map<String, Any?>>()
        val deletions = mutableListOf<String>()

        // Procesar cambios
        changesResponse.changes.forEach { change ->
            when (change) {
                is UpsertionChange -> {
                    when (val record = change.record) {
                        is WeightRecord -> {
                            if (record.metadata.dataOrigin.packageName == "com.sec.android.app.shealth") {
                                weightChanges.add(mapOf(
                                    "timestamp" to record.time.toString(),
                                    "weight_kg" to record.weight.inKilograms,
                                    "source" to record.metadata.dataOrigin.packageName,
                                    "change_type" to "UPSERT"
                                ))
                            }
                        }
                        is ExerciseSessionRecord -> {
                            if (record.metadata.dataOrigin.packageName == "com.sec.android.app.shealth") {
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
                                    }
                                }
                            }
                        }
                    }
                }
                is DeletionChange -> {
                    deletions.add(change.recordId)
                }
            }
        }

        // Si no hay cambios, no generar archivo
        if (weightChanges.isEmpty() && exerciseChanges.isEmpty() && deletions.isEmpty()) {
            Log.d(TAG, "No hay cambios desde último export")
            tokenManager.saveChangesToken(changesResponse.nextChangesToken)
            return
        }

        // Generar JSON con cambios incrementales
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val fileName = "health_data_AUTO_DIFF_${timestamp}.json"
        val jsonContent = generateDifferentialJSON(weightChanges, exerciseChanges, deletions)

        // Guardar en Downloads/HealthConnectExports
        saveToDownloads(fileName, jsonContent)

        // Guardar nuevo token para próxima vez
        tokenManager.saveChangesToken(changesResponse.nextChangesToken)

        Log.d(TAG, "Export diferencial: ${weightChanges.size} weight + ${exerciseChanges.size} exercises + ${deletions.size} deletions")
        Log.d(TAG, "Archivo guardado: Downloads/$EXPORT_FOLDER/$fileName")
    }

    /**
     * Guarda archivo en Downloads/HealthConnectExports
     * Autosync for Google Drive lo sube automáticamente
     */
    private fun saveToDownloads(fileName: String, content: String) {
        try {
            // Guardar en Downloads/HealthConnectExports
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportFolder = File(downloadsDir, EXPORT_FOLDER)

            if (!exportFolder.exists()) {
                exportFolder.mkdirs()
                Log.d(TAG, "Carpeta creada: ${exportFolder.absolutePath}")
            }

            // Guardar archivo
            val file = File(exportFolder, fileName)
            FileWriter(file).use { writer ->
                writer.write(content)
            }

            Log.d(TAG, "Archivo guardado: ${file.absolutePath}")
            Log.d(TAG, "Ubicación: Downloads/$EXPORT_FOLDER/$fileName")
            Log.d(TAG, "Autosync lo subirá automáticamente a Drive")

        } catch (e: Exception) {
            Log.e(TAG, "Error guardando archivo localmente", e)
            throw e
        }
    }

    private suspend fun readExerciseSessionsFiltered(
        healthConnectManager: HealthConnectManager,
        startTime: Instant,
        endTime: Instant
    ): List<Map<String, Any?>> {
        return try {
            val allExerciseRecords = healthConnectManager.readExerciseSessions(startTime, endTime)
            val samsungHealthRecords = allExerciseRecords.filter { record ->
                record.metadata.dataOrigin.packageName == "com.sec.android.app.shealth"
            }

            val exerciseSessions = mutableListOf<Map<String, Any?>>()

            samsungHealthRecords.forEach { exerciseRecord ->
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

    private suspend fun readWeightRecordsFiltered(
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

            response.records
                .filter { it.metadata.dataOrigin.packageName == "com.sec.android.app.shealth" }
                .map { record ->
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

    private fun generateHealthJSON(
        weightRecords: List<Map<String, Any?>>,
        exerciseData: List<Map<String, Any?>>,
        exportType: String
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"$exportType\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Samsung Health Only (Filtered)\",\n")
            append("  \"storage_location\": \"Local - Downloads/$EXPORT_FOLDER\",\n")
            append("  \"auto_sync_compatible\": true,\n")
            append("  \"weight_records\": {\n")
            append("    \"count\": ${weightRecords.size},\n")
            append("    \"data\": [\n")

            weightRecords.forEachIndexed { index, record ->
                append("      {\n")
                record.forEach { (key, value) ->
                    val valueStr = when (value) {
                        is String -> "\"$value\""
                        is Double -> String.format("%.2f", value)
                        null -> "null"
                        else -> value.toString()
                    }
                    append("        \"$key\": $valueStr")
                    if (key != record.keys.last()) append(",")
                    append("\n")
                }
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

    private fun generateDifferentialJSON(
        weightChanges: List<Map<String, Any?>>,
        exerciseChanges: List<Map<String, Any?>>,
        deletions: List<String>
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"AUTO_DIFFERENTIAL\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Samsung Health Only (Changes Since Last Export)\",\n")
            append("  \"storage_location\": \"Local - Downloads/$EXPORT_FOLDER\",\n")
            append("  \"auto_sync_compatible\": true,\n")
            append("  \"last_export_time\": \"${java.time.Instant.ofEpochMilli(tokenManager.getLastExportTime())}\",\n")
            append("  \"weight_changes\": {\n")
            append("    \"count\": ${weightChanges.size},\n")
            append("    \"data\": [\n")

            weightChanges.forEachIndexed { index, record ->
                append("      {\n")
                record.forEach { (key, value) ->
                    val valueStr = when (value) {
                        is String -> "\"$value\""
                        is Double -> String.format("%.2f", value)
                        null -> "null"
                        else -> value.toString()
                    }
                    append("        \"$key\": $valueStr")
                    if (key != record.keys.last()) append(",")
                    append("\n")
                }
                append("      }")
                if (index < weightChanges.size - 1) append(",")
                append("\n")
            }

            append("    ]\n")
            append("  },\n")
            append("  \"exercise_changes\": {\n")
            append("    \"count\": ${exerciseChanges.size},\n")
            append("    \"data\": [\n")

            exerciseChanges.forEachIndexed { index, session ->
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
                if (index < exerciseChanges.size - 1) append(",")
                append("\n")
            }

            append("    ]\n")
            append("  },\n")
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