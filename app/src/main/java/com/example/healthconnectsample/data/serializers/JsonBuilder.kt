package com.example.healthconnectsample.data.serializers

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class JsonBuilder {
    
    companion object {
        fun buildFullExportJson(
            dataByType: Map<String, List<Map<String, Any?>>>,
            exportType: String = "AUTO_FULL_EXPORT"
        ): String {
            return buildString {
                append("{\n")
                append("  \"export_type\": \"$exportType\",\n")
                append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
                append("  \"data_source\": \"Health Connect - All Sources\",\n")
                
                val entries = dataByType.entries.toList()
                entries.forEachIndexed { index, (dataType, records) ->
                    append("  \"${dataType}_records\": {\n")
                    append("    \"count\": ${records.size},\n")
                    append("    \"data\": ${serializeMapList(records)}\n")
                    append("  }")
                    if (index < entries.size - 1) append(",")
                    append("\n")
                }
                
                append("}")
            }
        }
        
        fun buildDifferentialExportJson(
            changesByType: Map<String, List<Map<String, Any?>>>,
            deletions: List<String>
        ): String {
            return buildString {
                append("{\n")
                append("  \"export_type\": \"AUTO_DIFFERENTIAL\",\n")
                append("  \"timestamp\": \"${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\",\n")
                append("  \"data_source\": \"Health Connect - Changes Only\",\n")
                
                val entries = changesByType.entries.toList()
                entries.forEachIndexed { index, (dataType, changes) ->
                    append("  \"${dataType}_changes\": {\n")
                    append("    \"count\": ${changes.size},\n")
                    append("    \"data\": ${serializeMapList(changes)}\n")
                    append("  },\n")
                }
                
                append("  \"deletions\": {\n")
                append("    \"count\": ${deletions.size},\n")
                append("    \"record_ids\": ${serializeStringList(deletions)}\n")
                append("  }\n")
                append("}")
            }
        }
        
        private fun serializeMapList(list: List<Map<String, Any?>>): String {
            if (list.isEmpty()) return "[]"
            
            return buildString {
                append("[\n")
                list.forEachIndexed { index, map ->
                    append("      {\n")
                    
                    val entries = map.entries.toList()
                    entries.forEachIndexed { entryIndex, (key, value) ->
                        append("        \"$key\": ")
                        when (value) {
                            is String -> append("\"${escapeJsonString(value)}\"")
                            is Number -> append(value.toString())
                            is Boolean -> append(value.toString())
                            is List<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                append(serializeMapList(value as List<Map<String, Any?>>))
                            }
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                append(serializeMap(value as Map<String, Any?>))
                            }
                            null -> append("null")
                            else -> append("\"$value\"")
                        }
                        if (entryIndex < entries.size - 1) append(",")
                        append("\n")
                    }
                    
                    append("      }")
                    if (index < list.size - 1) append(",")
                    append("\n")
                }
                append("    ]")
            }
        }
        
        private fun serializeMap(map: Map<String, Any?>): String {
            if (map.isEmpty()) return "{}"
            
            return buildString {
                append("{\n")
                val entries = map.entries.toList()
                entries.forEachIndexed { index, (key, value) ->
                    append("          \"$key\": ")
                    when (value) {
                        is String -> append("\"${escapeJsonString(value)}\"")
                        is Number -> append(value.toString())
                        is Boolean -> append(value.toString())
                        null -> append("null")
                        else -> append("\"$value\"")
                    }
                    if (index < entries.size - 1) append(",")
                    append("\n")
                }
                append("        }")
            }
        }
        
        private fun serializeStringList(list: List<String>): String {
            if (list.isEmpty()) return "[]"
            
            return buildString {
                append("[")
                list.forEachIndexed { index, item ->
                    append("\"${escapeJsonString(item)}\"")
                    if (index < list.size - 1) append(", ")
                }
                append("]")
            }
        }
        
        private fun escapeJsonString(str: String): String {
            return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}