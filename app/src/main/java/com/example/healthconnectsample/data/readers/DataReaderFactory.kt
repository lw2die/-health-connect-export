package com.example.healthconnectsample.data.readers

import androidx.health.connect.client.records.Record
import com.example.healthconnectsample.data.HealthConnectManager

class DataReaderFactory(
    private val healthConnectManager: HealthConnectManager
) {
    
    fun getAllReaders(): List<DataReader<out Record>> {
        return listOf(
            WeightReader(healthConnectManager),
            ExerciseReader(healthConnectManager),
            SleepReader(healthConnectManager),
            Vo2MaxReader(healthConnectManager),
            StepsReader(healthConnectManager),
            DistanceReader(healthConnectManager),
            TotalCaloriesReader(healthConnectManager),
            RestingHeartRateReader(healthConnectManager),
            HeartRateReader(healthConnectManager),
            OxygenSaturationReader(healthConnectManager),
            HeightReader(healthConnectManager),
            BodyFatReader(healthConnectManager),
            LeanBodyMassReader(healthConnectManager),
            BoneMassReader(healthConnectManager),
            BodyWaterMassReader(healthConnectManager),
            BasalMetabolicRateReader(healthConnectManager),
            BloodPressureReader(healthConnectManager),
            BloodGlucoseReader(healthConnectManager),
            NutritionReader(healthConnectManager)
        )
    }
    
    fun getReaderByType(recordClassName: String): DataReader<out Record>? {
        return getAllReaders().find { 
            it.recordType.simpleName == recordClassName 
        }
    }
}