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
            else -> "Unknown"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializeNestedList(list: List<Map<String, Any?>>): String {
        if (list.isEmpty()) return "[]"
        return buildString {
            append("[\n")
            list.forEachIndexed { index, map ->
                append("          {\n")
                map.entries.forEachIndexed { entryIndex, (key, value) ->
                    append("            \"$key\": ")
                    when (value) {
                        is String -> append("\"$value\"")
                        is Int -> append(value.toString())
                        null -> append("null")
                        else -> append(value.toString())
                    }
                    if (entryIndex < map.size - 1) append(",")
                    append("\n")
                }
                append("          }")
                if (index < list.size - 1) append(",")
                append("\n")
            }
            append("        ]")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializeMapList(data: List<Map<String, Any?>>): String {
        if (data.isEmpty()) return "[]"
        return buildString {
            append("[\n")
            data.forEachIndexed { index, map ->
                append("      {\n")
                map.entries.forEachIndexed { entryIndex, (key, value) ->
                    append("        \"$key\": ")
                    when (value) {
                        is String -> append("\"$value\"")
                        is Long -> append(value.toString())
                        is Int -> append(value.toString())
                        is Double -> append(String.format("%.2f", value))
                        is List<*> -> append(serializeNestedList(value as List<Map<String, Any?>>))
                        null -> append("null")
                        else -> append(value.toString())
                    }
                    if (entryIndex < map.size - 1) append(",")
                    append("\n")
                }
                append("      }")
                if (index < data.size - 1) append(",")
                append("\n")
            }
            append("    ]")
        }
    }

    private fun serializeWeightData(records: List<WeightData>): String {
        if (records.isEmpty()) return "[]"
        return buildString {
            append("[\n")
            records.forEachIndexed { index, record ->
                append("      {\n")
                append("        \"timestamp\": \"${record.time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
                append("        \"weight_kg\": ${String.format("%.2f", record.weight.inKilograms)},\n")
                append("        \"source\": \"${record.sourceAppInfo?.packageName ?: "unknown"}\"\n")
                append("      }")
                if (index < records.size - 1) append(",")
                append("\n")
            }
            append("    ]")
        }
    }

    fun generateHealthJSON(
        weightRecords: List<WeightData>,
        exerciseData: List<Map<String, Any?>>,
        sleepData: List<Map<String, Any?>>,
        exportType: String
    ): String {
        return buildString {
            append("{\n")
            append("  \"export_type\": \"$exportType\",\n")
            append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
            append("  \"data_source\": \"Samsung Health Only (Filtered - Last 30 days)\",\n")

            append("  \"weight_records\": {\n")
            append("    \"count\": ${weightRecords.size},\n")
            append("    \"data\": ")
            append(serializeWeightData(weightRecords))
            append("\n  },\n")

            append("  \"exercise_sessions\": {\n")
            append("    \"count\": ${exerciseData.size},\n")
            append("    \"data\": ")
            append(serializeMapList(exerciseData))
            append("\n  },\n")

            append("  \"sleep_sessions\": {\n")
            append("    \"count\": ${sleepData.size},\n")
            append("    \"data\": ")
            append(serializeMapList(sleepData))
            append("\n  }\n")

            append("}")
        }
    }
}