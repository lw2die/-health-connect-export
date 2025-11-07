package com.example.healthconnectsample.data.readers

import android.util.Log
import androidx.health.connect.client.records.SleepSessionRecord
import com.example.healthconnectsample.data.HealthConnectManager
import com.example.healthconnectsample.data.HealthDataSerializer
import java.time.Instant
import kotlin.reflect.KClass

class SleepReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<SleepSessionRecord> {
    
    override val recordType: KClass<SleepSessionRecord> = SleepSessionRecord::class
    override val dataTypeName: String = "sleep"
    
    companion object {
        private const val TAG = "SleepReader"
    }
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readSleepSessions(startTime, endTime)
            records.map { record -> recordToMap(record) }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sleep records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: SleepSessionRecord): Map<String, Any?> {
        val durationMinutes = try {
            java.time.Duration.between(record.startTime, record.endTime).toMinutes()
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
        
        return mapOf(
            "record_id" to record.metadata.id,
            "session_id" to record.metadata.id,
            "title" to (record.title ?: "Sleep Session"),
            "notes" to record.notes,
            "start_time" to record.startTime.toString(),
            "end_time" to record.endTime.toString(),
            "start_zone_offset" to record.startZoneOffset?.toString(),
            "end_zone_offset" to record.endZoneOffset?.toString(),
            "duration_minutes" to durationMinutes,
            "stages_count" to stages.size,
            "stages" to stages,
            "source" to record.metadata.dataOrigin.packageName,
            "client_record_id" to record.metadata.clientRecordId,
            "data_origin" to mapOf(
                "package_name" to record.metadata.dataOrigin.packageName
            )
        )
    }
}