package com.example.healthconnectsample.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Configura y programa el export automático cada 30 minutos
 * SIN AFECTAR el proceso manual existente
 */
object WorkerScheduler {

    private const val TAG = "WorkerScheduler"
    private const val WORK_NAME = "auto_export_health_data"
    private const val REPEAT_INTERVAL_MINUTES = 30L

    /**
     * Inicia el export automático en segundo plano
     * Se ejecuta cada 30 minutos entre 5 AM y 9 PM
     */
    fun scheduleAutoExport(context: Context) {
        Log.d(TAG, "Programando export automático cada $REPEAT_INTERVAL_MINUTES minutos")

        // Configurar restricciones para el Worker
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)  // Requiere internet
            .setRequiresBatteryNotLow(true)                 // No ejecutar si batería baja
            .build()

        // Crear request para trabajo periódico
        val workRequest = PeriodicWorkRequestBuilder<AutoExportWorker>(
            repeatInterval = REPEAT_INTERVAL_MINUTES,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 15,  // Flexibilidad de 15 minutos
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("health_export")
            .build()

        // Programar trabajo (reemplaza si ya existe)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,  // Mantener el existente si ya hay uno
            workRequest
        )

        Log.d(TAG, "Export automático programado exitosamente")
    }

    /**
     * Detiene el export automático
     */
    fun cancelAutoExport(context: Context) {
        Log.d(TAG, "Cancelando export automático")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Verifica el estado del export automático
     */
    fun checkAutoExportStatus(context: Context): WorkInfo.State? {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()

        return workInfos.firstOrNull()?.state
    }

    /**
     * Ejecuta un export manual inmediato (one-time)
     * NO interfiere con el export periódico
     */
    fun triggerManualExport(context: Context) {
        Log.d(TAG, "Ejecutando export manual inmediato")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<AutoExportWorker>()
            .setConstraints(constraints)
            .addTag("manual_export")
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeRequest)
    }
}