package com.example.healthconnectsample.data.readers

import android.util.Log
import androidx.health.connect.client.records.*
import com.example.healthconnectsample.data.HealthConnectManager
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

class StepsReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<StepsRecord> {
    override val recordType: KClass<StepsRecord> = StepsRecord::class
    override val dataTypeName: String = "steps"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readStepsRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: StepsRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
        "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
        "count" to record.count,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class DistanceReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<DistanceRecord> {
    override val recordType: KClass<DistanceRecord> = DistanceRecord::class
    override val dataTypeName: String = "distance"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readDistanceRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: DistanceRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
        "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
        "distance_meters" to record.distance.inMeters,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class TotalCaloriesReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<TotalCaloriesBurnedRecord> {
    override val recordType: KClass<TotalCaloriesBurnedRecord> = TotalCaloriesBurnedRecord::class
    override val dataTypeName: String = "total_calories"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readTotalCaloriesBurnedRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: TotalCaloriesBurnedRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
        "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
        "energy_kcal" to record.energy.inKilocalories,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class Vo2MaxReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<Vo2MaxRecord> {
    override val recordType: KClass<Vo2MaxRecord> = Vo2MaxRecord::class
    override val dataTypeName: String = "vo2max"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readVo2MaxRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: Vo2MaxRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "vo2_max_ml_per_min_per_kg" to record.vo2MillilitersPerMinuteKilogram,
        "measurement_method" to record.measurementMethod,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class RestingHeartRateReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<RestingHeartRateRecord> {
    override val recordType: KClass<RestingHeartRateRecord> = RestingHeartRateRecord::class
    override val dataTypeName: String = "resting_heart_rate"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readRestingHeartRateRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: RestingHeartRateRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "bpm" to record.beatsPerMinute,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class HeartRateReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<HeartRateRecord> {
    override val recordType: KClass<HeartRateRecord> = HeartRateRecord::class
    override val dataTypeName: String = "heart_rate"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readHeartRateRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: HeartRateRecord): Map<String, Any?> {
        val bpms = record.samples.map { it.beatsPerMinute }
        return mapOf(
            "record_id" to record.metadata.id,
            "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
            "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
            "samples_count" to record.samples.size,
            "avg_bpm" to if (bpms.isNotEmpty()) bpms.average().toLong() else null,
            "min_bpm" to bpms.minOrNull(),
            "max_bpm" to bpms.maxOrNull(),
            "source" to record.metadata.dataOrigin.packageName,
            "client_record_id" to record.metadata.clientRecordId
        )
    }
}

class OxygenSaturationReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<OxygenSaturationRecord> {
    override val recordType: KClass<OxygenSaturationRecord> = OxygenSaturationRecord::class
    override val dataTypeName: String = "oxygen_saturation"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readOxygenSaturationRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: OxygenSaturationRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "percentage" to record.percentage.value,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class HeightReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<HeightRecord> {
    override val recordType: KClass<HeightRecord> = HeightRecord::class
    override val dataTypeName: String = "height"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readHeightRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: HeightRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "height_meters" to record.height.inMeters,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class BodyFatReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<BodyFatRecord> {
    override val recordType: KClass<BodyFatRecord> = BodyFatRecord::class
    override val dataTypeName: String = "body_fat"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readBodyFatRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: BodyFatRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "percentage" to record.percentage.value,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class LeanBodyMassReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<LeanBodyMassRecord> {
    override val recordType: KClass<LeanBodyMassRecord> = LeanBodyMassRecord::class
    override val dataTypeName: String = "lean_body_mass"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readLeanBodyMassRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: LeanBodyMassRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "mass_kg" to record.mass.inKilograms,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class BoneMassReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<BoneMassRecord> {
    override val recordType: KClass<BoneMassRecord> = BoneMassRecord::class
    override val dataTypeName: String = "bone_mass"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readBoneMassRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: BoneMassRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "mass_kg" to record.mass.inKilograms,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class BodyWaterMassReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<BodyWaterMassRecord> {
    override val recordType: KClass<BodyWaterMassRecord> = BodyWaterMassRecord::class
    override val dataTypeName: String = "body_water_mass"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readBodyWaterMassRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: BodyWaterMassRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "mass_kg" to record.mass.inKilograms,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class BasalMetabolicRateReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<BasalMetabolicRateRecord> {
    override val recordType: KClass<BasalMetabolicRateRecord> = BasalMetabolicRateRecord::class
    override val dataTypeName: String = "basal_metabolic_rate"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readBasalMetabolicRateRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: BasalMetabolicRateRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "kcal_per_day" to record.basalMetabolicRate.inKilocaloriesPerDay,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class BloodPressureReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<BloodPressureRecord> {
    override val recordType: KClass<BloodPressureRecord> = BloodPressureRecord::class
    override val dataTypeName: String = "blood_pressure"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readBloodPressureRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: BloodPressureRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "systolic_mmhg" to record.systolic.inMillimetersOfMercury,
        "diastolic_mmhg" to record.diastolic.inMillimetersOfMercury,
        "body_position" to record.bodyPosition,
        "measurement_location" to record.measurementLocation,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}

class BloodGlucoseReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<BloodGlucoseRecord> {
    override val recordType: KClass<BloodGlucoseRecord> = BloodGlucoseRecord::class
    override val dataTypeName: String = "blood_glucose"
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            healthConnectManager.readBloodGlucoseRecords(startTime, endTime).map { recordToMap(it) }
        } catch (e: Exception) {
            Log.e(dataTypeName, "Error reading records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: BloodGlucoseRecord): Map<String, Any?> = mapOf(
        "record_id" to record.metadata.id,
        "timestamp" to record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toString(),
        "glucose_mmol_per_l" to record.level.inMillimolesPerLiter,
        "specimen_source" to record.specimenSource,
        "meal_type" to record.mealType,
        "relation_to_meal" to record.relationToMeal,
        "source" to record.metadata.dataOrigin.packageName,
        "client_record_id" to record.metadata.clientRecordId
    )
}