package com.example.healthconnectsample.data.readers

import android.util.Log
import androidx.health.connect.client.records.NutritionRecord
import com.example.healthconnectsample.data.HealthConnectManager
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

class NutritionReader(
    private val healthConnectManager: HealthConnectManager
) : DataReader<NutritionRecord> {
    
    override val recordType: KClass<NutritionRecord> = NutritionRecord::class
    override val dataTypeName: String = "nutrition"
    
    companion object {
        private const val TAG = "NutritionReader"
    }
    
    override suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>> {
        return try {
            val records = healthConnectManager.readNutritionRecords(startTime, endTime)
            records.map { record -> recordToMap(record) }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading nutrition records", e)
            emptyList()
        }
    }
    
    override fun recordToMap(record: NutritionRecord): Map<String, Any?> {
        return mapOf(
            "record_id" to record.metadata.id,
            "start_time" to record.startTime.atZone(record.startZoneOffset ?: ZoneId.systemDefault()).toString(),
            "end_time" to record.endTime.atZone(record.endZoneOffset ?: ZoneId.systemDefault()).toString(),
            "meal_type" to record.mealType,
            "name" to record.name,
            
            "energy_kcal" to record.energy?.inKilocalories,
            "protein_g" to record.protein?.inGrams,
            "carbs_g" to record.totalCarbohydrate?.inGrams,
            "fat_total_g" to record.totalFat?.inGrams,
            
            "fiber_g" to record.dietaryFiber?.inGrams,
            "sugar_g" to record.sugar?.inGrams,
            
            "fat_saturated_g" to record.saturatedFat?.inGrams,
            "fat_unsaturated_g" to record.unsaturatedFat?.inGrams,
            "fat_monounsaturated_g" to record.monounsaturatedFat?.inGrams,
            "fat_polyunsaturated_g" to record.polyunsaturatedFat?.inGrams,
            "fat_trans_g" to record.transFat?.inGrams,
            "cholesterol_mg" to record.cholesterol?.inMilligrams,
            
            "vitamin_a_mcg" to record.vitaminA?.inMicrograms,
            "vitamin_b6_mg" to record.vitaminB6?.inMilligrams,
            "vitamin_b12_mcg" to record.vitaminB12?.inMicrograms,
            "vitamin_c_mg" to record.vitaminC?.inMilligrams,
            "vitamin_d_mcg" to record.vitaminD?.inMicrograms,
            "vitamin_e_mg" to record.vitaminE?.inMilligrams,
            "vitamin_k_mcg" to record.vitaminK?.inMicrograms,
            
            "calcium_mg" to record.calcium?.inMilligrams,
            "iron_mg" to record.iron?.inMilligrams,
            "magnesium_mg" to record.magnesium?.inMilligrams,
            "phosphorus_mg" to record.phosphorus?.inMilligrams,
            "potassium_mg" to record.potassium?.inMilligrams,
            "sodium_mg" to record.sodium?.inMilligrams,
            "zinc_mg" to record.zinc?.inMilligrams,
            
            "caffeine_mg" to record.caffeine?.inMilligrams,
            
            "source" to record.metadata.dataOrigin.packageName,
            "client_record_id" to record.metadata.clientRecordId,
            "data_origin" to mapOf(
                "package_name" to record.metadata.dataOrigin.packageName
            )
        )
    }
}