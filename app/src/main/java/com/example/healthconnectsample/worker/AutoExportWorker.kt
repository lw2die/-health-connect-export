package com.example.healthconnectsample.worker

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.healthconnectsample.data.HealthConnectManager
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AutoExportWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoExportWorker"
        private const val START_HOUR = 5  // 5 AM
        private const val END_HOUR = 21   // 9 PM
        private const val DRIVE_FOLDER_ID = "1PK2P9457IH9TUqzvhD4osF1l8d9icfas"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Verificar horario
            val currentHour = LocalTime.now().hour
            if (currentHour !in START_HOUR until END_HOUR) {
                Log.d(TAG, "Fuera del horario permitido. Hora actual: $currentHour")
                return@withContext Result.success()
            }

            Log.d(TAG, "Iniciando export automático...")

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

            val healthConnectManager = HealthConnectManager(context)

            // Leer datos
            val endTime = Instant.now()
            val startTime = Instant.EPOCH

            val exerciseData = readExerciseSessionsFiltered(healthConnectManager, startTime, endTime)
            val weightData = readWeightRecordsFiltered(healthConnectClient, startTime, endTime)

            // Generar JSON
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            val fileName = "health_data_AUTO_${timestamp}.json"
            val jsonContent = generateHealthJSON(weightData, exerciseData, "AUTO_SAMSUNG_ONLY")

            // Guardar archivo temporalmente
            val tempFile = File(context.cacheDir, fileName)
            FileWriter(tempFile).use { writer ->
                writer.write(jsonContent)
            }

            // Subir a Google Drive automáticamente
            uploadToGoogleDrive(tempFile, fileName)

            Log.d(TAG, "Export automático completado: ${weightData.size} weight + ${exerciseData.size} exercises")

            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Error en export automático", e)
            return@withContext Result.retry()
        }
    }

    private fun uploadToGoogleDrive(file: File, fileName: String) {
        try {
            Log.d(TAG, "Iniciando subida a Google Drive...")

            // Leer credenciales del service account
            val credentials = context.assets.open("service_account.json").use { inputStream ->
                ServiceAccountCredentials.fromStream(inputStream)
                    .createScoped(listOf(DriveScopes.DRIVE_FILE))
            }

            // Crear servicio de Drive
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            val driveService = Drive.Builder(
                httpTransport,
                jsonFactory,
                HttpCredentialsAdapter(credentials)
            )
                .setApplicationName("Health Connect Auto Export")
                .build()

            // Metadatos del archivo
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = fileName
                parents = listOf(DRIVE_FOLDER_ID)
            }

            // Contenido del archivo
            val mediaContent = FileContent("application/json", file)

            // Subir archivo
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name, webViewLink")
                .execute()

            Log.d(TAG, "Archivo subido exitosamente: ${uploadedFile.name}")
            Log.d(TAG, "ID: ${uploadedFile.id}")
            Log.d(TAG, "Link: ${uploadedFile.webViewLink}")

            // Eliminar archivo temporal
            file.delete()

        } catch (e: Exception) {
            Log.e(TAG, "Error subiendo a Google Drive", e)
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
                } catch (e: Exception) {
                    null
                }

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