package com.example.healthconnectsample.data.readers

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Interface para estandarizar la lectura de diferentes tipos de datos de Health Connect
 */
interface DataReader<T : Record> {
    /**
     * Tipo de record que este reader maneja
     */
    val recordType: KClass<T>
    
    /**
     * Nombre del tipo de dato para logging y JSON
     */
    val dataTypeName: String
    
    /**
     * Lee records del tipo específico en el rango de tiempo dado
     * @return Lista de Maps con los datos serializados, incluyendo record_id
     */
    suspend fun readRecords(startTime: Instant, endTime: Instant): List<Map<String, Any?>>
    
    /**
     * Convierte un record individual a Map para serialización
     * IMPORTANTE: Debe incluir record_id de metadata
     */
    fun recordToMap(record: T): Map<String, Any?>
}