package com.example.healthconnectsample.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HealthDataSerializer {

    fun getSleepStageName(stageType: Int): String {
        return when (stageType) {
            1 -> "Awake"
            2 -> "Sleeping"
            3 -> "Out of bed"
            4 -> "Light sleep"
            5 -> "Deep sleep"
            6 -> "REM sleep"
            else -> "Unknown ($stageType)"
        }
    }

    fun generateHealthJSON(
        weightRecords: List<Map<String, Any?>>,
        exerciseData: List<Map<String, Any?>>,
        sleepData: List<Map<String, Any?>>,
        vo2maxData: List<Map<String, Any?>>,
        stepsData: List<Map<String, Any?>>,
        distanceData: List<Map<String, Any?>>,
        totalCaloriesData: List<Map<String, Any?>>,
        restingHRData: List<Map<String, Any?>>,
        oxygenSaturationData: List<Map<String, Any?>>,
        heightData: List<Map<String, Any?>>,
        bodyFatData: List<Map<String, Any?>>,
        leanBodyMassData: List<Map<String, Any?>>,
        boneMassData: List<Map<String, Any?>>,
        basalMetabolicRateData: List<Map<String, Any?>>,
        bodyWaterMassData: List<Map<String, Any?>>,
        exportType: String
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"$exportType\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Samsung Health Only - Last 30 days\",\n")
            append("  \"weight_records\": {\n")
            append("    \"count\": ${weightRecords.size},\n")
            append("    \"data\": ${serializeList(weightRecords)}\n")
            append("  },\n")
            append("  \"exercise_sessions\": {\n")
            append("    \"count\": ${exerciseData.size},\n")
            append("    \"data\": ${serializeList(exerciseData)}\n")
            append("  },\n")
            append("  \"sleep_sessions\": {\n")
            append("    \"count\": ${sleepData.size},\n")
            append("    \"data\": ${serializeList(sleepData)}\n")
            append("  },\n")
            append("  \"vo2max_records\": {\n")
            append("    \"count\": ${vo2maxData.size},\n")
            append("    \"data\": ${serializeList(vo2maxData)}\n")
            append("  },\n")
            append("  \"steps_records\": {\n")
            append("    \"count\": ${stepsData.size},\n")
            append("    \"data\": ${serializeList(stepsData)}\n")
            append("  },\n")
            append("  \"distance_records\": {\n")
            append("    \"count\": ${distanceData.size},\n")
            append("    \"data\": ${serializeList(distanceData)}\n")
            append("  },\n")
            append("  \"total_calories_records\": {\n")
            append("    \"count\": ${totalCaloriesData.size},\n")
            append("    \"data\": ${serializeList(totalCaloriesData)}\n")
            append("  },\n")
            append("  \"resting_heart_rate_records\": {\n")
            append("    \"count\": ${restingHRData.size},\n")
            append("    \"data\": ${serializeList(restingHRData)}\n")
            append("  },\n")
            append("  \"oxygen_saturation_records\": {\n")
            append("    \"count\": ${oxygenSaturationData.size},\n")
            append("    \"data\": ${serializeList(oxygenSaturationData)}\n")
            append("  },\n")
            append("  \"height_records\": {\n")
            append("    \"count\": ${heightData.size},\n")
            append("    \"data\": ${serializeList(heightData)}\n")
            append("  },\n")
            append("  \"body_fat_records\": {\n")
            append("    \"count\": ${bodyFatData.size},\n")
            append("    \"data\": ${serializeList(bodyFatData)}\n")
            append("  },\n")
            append("  \"lean_body_mass_records\": {\n")
            append("    \"count\": ${leanBodyMassData.size},\n")
            append("    \"data\": ${serializeList(leanBodyMassData)}\n")
            append("  },\n")
            append("  \"bone_mass_records\": {\n")
            append("    \"count\": ${boneMassData.size},\n")
            append("    \"data\": ${serializeList(boneMassData)}\n")
            append("  },\n")
            append("  \"basal_metabolic_rate_records\": {\n")
            append("    \"count\": ${basalMetabolicRateData.size},\n")
            append("    \"data\": ${serializeList(basalMetabolicRateData)}\n")
            append("  },\n")
            append("  \"body_water_mass_records\": {\n")
            append("    \"count\": ${bodyWaterMassData.size},\n")
            append("    \"data\": ${serializeList(bodyWaterMassData)}\n")
            append("  }\n")
            append("}")
        }
    }

    private fun serializeList(list: List<Map<String, Any?>>): String {
        if (list.isEmpty()) return "[]"
        return buildString {
            append("[\n")
            list.forEachIndexed { index, map ->
                append("      {\n")
                map.entries.forEachIndexed { entryIndex, (key, value) ->
                    append("        \"$key\": ")
                    when (value) {
                        is String -> append("\"$value\"")
                        is Number -> append(value.toString())
                        is List<*> -> append(serializeList(value as List<Map<String, Any?>>))
                        null -> append("null")
                        else -> append("\"$value\"")
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
}
