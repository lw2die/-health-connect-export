package com.example.healthconnectsample.data.readers

import android.util.Log
import androidx.health.connect.client.records.WeightRecord
import com.example.healthconnectsample.data.HealthConnectManager
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

class WeightReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<WeightRecord> {
    
    override val recordType: KClass<WeightRecord> = WeightRecord::class
    override val dataTypeName: String = "weight"
    
    companion object {
        private const val TAG = "WeightReader"
    }
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readWeightInputs(startTime, endTime)
            records.map { record -> recordToMap(record) }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading weight records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: WeightRecord): Map<String, Any?> {
        return mapOf(
            "record_id" to record.metadata.id,
            "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
            "weight_kg" to record.weight.inKilograms,
            "source" to record.metadata.dataOrigin.packageName,
            "client_record_id" to record.metadata.clientRecordId,
            "data_origin" to mapOf(
                "package_name" to record.metadata.dataOrigin.packageName
            )
        )
    }
}