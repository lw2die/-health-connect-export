package com.example.healthconnectsample.worker

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.healthconnectsample.data.HealthConnectManager
import com.example.healthconnectsample.data.readers.*
import com.example.healthconnectsample.data.serializers.JsonBuilder
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
            Log.d(TAG, "========================================")
            Log.d(TAG, "🚀 AutoExportWorker INICIADO (Refactorizado)")
            Log.d(TAG, "========================================")

            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                Log.e(TAG, "❌ Health Connect no disponible")
                return@withContext Result.retry()
            }

            val healthConnectClient = HealthConnectClient.getOrCreate(context)
            Log.d(TAG, "✅ HealthConnectClient creado")

            if (!checkPermissions(healthConnectClient)) {
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

    private suspend fun checkPermissions(healthConnectClient: HealthConnectClient): Boolean {
        val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
        val requiredPermissions = setOf(
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
            BloodGlucoseRecord::class,
            NutritionRecord::class
        ).map { HealthPermission.getReadPermission(it) }.toSet()

        Log.d(TAG, "Permisos otorgados: ${grantedPermissions.size}")
        Log.d(TAG, "Permisos requeridos: ${requiredPermissions.size}")

        return grantedPermissions.containsAll(requiredPermissions)
    }

    private suspend fun performFullExport(healthConnectClient: HealthConnectClient) {
        Log.d(TAG, "📦 Iniciando export COMPLETO (30 días)...")

        val healthConnectManager = HealthConnectManager(context)
        val readerFactory = DataReaderFactory(healthConnectManager)
        
        val endTime = Instant.now()
        val startTime = endTime.minus(java.time.Duration.ofDays(30))

        val dataByType = mutableMapOf<String, List<Map<String, Any?>>>()
        
        val allReaders = readerFactory.getAllReaders()
        
        allReaders.forEach { reader ->
            try {
                val data = if (reader is ExerciseReader) {
                    reader.readRecordsWithSessionData(startTime, endTime)
                } else {
                    reader.readRecords(startTime, endTime)
                }
                dataByType[reader.dataTypeName] = data
                Log.d(TAG, "  ✅ ${reader.dataTypeName}: ${data.size} records")
            } catch (e: Exception) {
                Log.e(TAG, "  ❌ Error reading ${reader.dataTypeName}", e)
                dataByType[reader.dataTypeName] = emptyList()
            }
        }

        val jsonContent = JsonBuilder.buildFullExportJson(dataByType, "AUTO_FULL_EXPORT")
        saveToFile(jsonContent, "FULL")

        val newToken = healthConnectClient.getChangesToken(
            ChangesTokenRequest(allReaders.map { it.recordType }.toSet())
        )
        tokenManager.saveToken(newToken)

        val totalRecords = dataByType.values.sumOf { it.size }
        Log.d(TAG, "✅ Export completo: $totalRecords records totales")
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

        val healthConnectManager = HealthConnectManager(context)
        val readerFactory = DataReaderFactory(healthConnectManager)
        
        val changesByType = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        val deletions = mutableListOf<String>()

        try {
            val changesResponse = healthConnectClient.getChanges(token)
            Log.d(TAG, "Changes obtenidos: ${changesResponse.changes.size}")

            changesResponse.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> {
                        processUpsertionChange(change, readerFactory, healthConnectManager, changesByType)
                    }
                    is DeletionChange -> {
                        deletions.add(change.recordId)
                        Log.d(TAG, "  - Deletion detected: ${change.recordId}")
                    }
                }
            }

            val totalChanges = changesByType.values.sumOf { it.size } + deletions.size
            if (totalChanges == 0) {
                Log.d(TAG, "📭 No hay cambios nuevos")
                return
            }

            val jsonContent = JsonBuilder.buildDifferentialExportJson(changesByType, deletions)
            saveToFile(jsonContent, "DIFF")

            tokenManager.saveToken(changesResponse.nextChangesToken)

            Log.d(TAG, "✅ Export diferencial: $totalChanges cambios totales")
            Log.d(TAG, "✅ Token actualizado")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en export diferencial, forzando export completo", e)
            tokenManager.clearToken()
            performFullExport(healthConnectClient)
        }
    }

    private suspend fun processUpsertionChange(
        change: UpsertionChange,
        readerFactory: DataReaderFactory,
        healthConnectManager: HealthConnectManager,
        changesByType: MutableMap<String, MutableList<Map<String, Any?>>>
    ) {
        val record = change.record
        val recordClassName = record::class.simpleName ?: return
        
        val reader = readerFactory.getReaderByType(recordClassName)
        
        if (reader != null) {
            try {
                val recordMap = when (record) {
                    is ExerciseSessionRecord -> {
                        val exerciseReader = reader as ExerciseReader
                        exerciseReader.recordToMapWithSessionData(record)
                    }
                    is SleepSessionRecord -> {
                        (reader as SleepReader).recordToMap(record)
                    }
                    is WeightRecord -> {
                        (reader as WeightReader).recordToMap(record)
                    }
                    is Vo2MaxRecord -> {
                        (reader as Vo2MaxReader).recordToMap(record)
                    }
                    is StepsRecord -> {
                        (reader as StepsReader).recordToMap(record)
                    }
                    is DistanceRecord -> {
                        (reader as DistanceReader).recordToMap(record)
                    }
                    is TotalCaloriesBurnedRecord -> {
                        (reader as TotalCaloriesReader).recordToMap(record)
                    }
                    is RestingHeartRateRecord -> {
                        (reader as RestingHeartRateReader).recordToMap(record)
                    }
                    is HeartRateRecord -> {
                        (reader as HeartRateReader).recordToMap(record)
                    }
                    is OxygenSaturationRecord -> {
                        (reader as OxygenSaturationReader).recordToMap(record)
                    }
                    is HeightRecord -> {
                        (reader as HeightReader).recordToMap(record)
                    }
                    is BodyFatRecord -> {
                        (reader as BodyFatReader).recordToMap(record)
                    }
                    is LeanBodyMassRecord -> {
                        (reader as LeanBodyMassReader).recordToMap(record)
                    }
                    is BoneMassRecord -> {
                        (reader as BoneMassReader).recordToMap(record)
                    }
                    is BodyWaterMassRecord -> {
                        (reader as BodyWaterMassReader).recordToMap(record)
                    }
                    is BasalMetabolicRateRecord -> {
                        (reader as BasalMetabolicRateReader).recordToMap(record)
                    }
                    is BloodPressureRecord -> {
                        (reader as BloodPressureReader).recordToMap(record)
                    }
                    is BloodGlucoseRecord -> {
                        (reader as BloodGlucoseReader).recordToMap(record)
                    }
                    is NutritionRecord -> {
                        (reader as NutritionReader).recordToMap(record)
                    }
                    else -> null
                }
                
                recordMap?.let { map ->
                    val mutableMap = map.toMutableMap()
                    mutableMap["change_type"] = "UPSERT"
                    
                    val dataTypeName = reader.dataTypeName
                    changesByType.getOrPut(dataTypeName) { mutableListOf() }.add(mutableMap)
                    Log.d(TAG, "  + $dataTypeName change detected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing $recordClassName change", e)
            }
        } else {
            Log.w(TAG, "No reader found for $recordClassName")
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