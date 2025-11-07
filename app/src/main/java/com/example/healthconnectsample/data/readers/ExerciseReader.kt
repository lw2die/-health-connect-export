package com.example.healthconnectsample.data.readers

import android.util.Log
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.example.healthconnectsample.data.HealthConnectManager
import java.time.Instant
import kotlin.reflect.KClass

class ExerciseReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<ExerciseSessionRecord> {
    
    override val recordType: KClass<ExerciseSessionRecord> = ExerciseSessionRecord::class
    override val dataTypeName: String = "exercise"
    
    companion object {
        private const val TAG = "ExerciseReader"
    }
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readExerciseSessions(startTime, endTime)
            records.mapNotNull { record ->
                try {
                    recordToMap(record)
                } catch (e: Exception) {
                    Log.e(TAG, "Error mapping exercise record ${record.metadata.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading exercise records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: ExerciseSessionRecord): Map<String, Any?> {
        return mapOf(
            "record_id" to record.metadata.id,
            "session_id" to record.metadata.id,
            "title" to (record.title ?: "Exercise Session"),
            "notes" to record.notes,
            "exercise_type" to record.exerciseType,
            "start_time" to record.startTime.toString(),
            "end_time" to record.endTime.toString(),
            "start_zone_offset" to record.startZoneOffset?.toString(),
            "end_zone_offset" to record.endZoneOffset?.toString(),
            "source" to record.metadata.dataOrigin.packageName,
            "client_record_id" to record.metadata.clientRecordId,
            "data_origin" to mapOf(
                "package_name" to record.metadata.dataOrigin.packageName
            )
        )
    }
    
    suspend fun recordToMapWithSessionData(record: ExerciseSessionRecord): Map<String, Any?> {
        val baseMap = recordToMap(record).toMutableMap()
        
        try {
            val sessionData = healthConnectManager.readAssociatedSessionData(record.metadata.id)
            baseMap["duration_minutes"] = sessionData.totalActiveTime?.toMinutes()
            baseMap["total_steps"] = sessionData.totalSteps
            baseMap["distance_meters"] = sessionData.totalDistance?.inMeters
            baseMap["calories_burned"] = sessionData.totalEnergyBurned?.inCalories
            baseMap["avg_heart_rate"] = sessionData.avgHeartRate
            baseMap["max_heart_rate"] = sessionData.maxHeartRate
            baseMap["min_heart_rate"] = sessionData.minHeartRate
        } catch (e: Exception) {
            Log.w(TAG, "Could not read session data for ${record.metadata.id}", e)
        }
        
        return baseMap
    }
    
    suspend fun readRecordsWithSessionData(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readExerciseSessions(startTime, endTime)
            records.mapNotNull { record ->
                try {
                    recordToMapWithSessionData(record)
                } catch (e: Exception) {
                    Log.e(TAG, "Error mapping exercise record with session data ${record.metadata.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading exercise records", e)
            emptyList()
        }
    }
}