package com.example.healthconnectsample.presentation

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.healthconnectsample.presentation.navigation.HealthConnectApp
import com.example.healthconnectsample.worker.WorkerScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val healthConnectManager = (application as BaseApplication).healthConnectManager

        // Programar export automático diferencial
        WorkerScheduler.scheduleAutoExport(this)

        // Verificar si el worker se programó correctamente
        try {
            val workInfos = WorkManager.getInstance(this)
                .getWorkInfosForUniqueWork("auto_export_health_data")
                .get()

            if (workInfos.isEmpty()) {
                Log.e("MainActivity", "❌ Worker NO programado - lista vacía")
            } else {
                workInfos.forEach { workInfo ->
                    Log.d("MainActivity", "========================================")
                    Log.d("MainActivity", "Worker Status:")
                    Log.d("MainActivity", "  State: ${workInfo.state}")
                    Log.d("MainActivity", "  Tags: ${workInfo.tags}")
                    Log.d("MainActivity", "  Run attempt: ${workInfo.runAttemptCount}")

                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> {
                            Log.d("MainActivity", "  Status: En cola, esperando ejecución")
                            Log.d("MainActivity", "  Próxima ejecución: WorkManager decidirá según optimizaciones")
                        }
                        WorkInfo.State.RUNNING -> {
                            Log.d("MainActivity", "  Status: EJECUTÁNDOSE AHORA")
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Log.d("MainActivity", "  Status: Completado exitosamente")
                        }
                        WorkInfo.State.FAILED -> {
                            Log.e("MainActivity", "  Status: FALLÓ")
                        }
                        WorkInfo.State.CANCELLED -> {
                            Log.e("MainActivity", "  Status: CANCELADO")
                        }
                        WorkInfo.State.BLOCKED -> {
                            Log.w("MainActivity", "  Status: BLOQUEADO por dependencias")
                        }
                    }

                    Log.d("MainActivity", "========================================")
                }

                // Calcular tiempo estimado hasta próximo slot de 30 min
                val currentMinute = System.currentTimeMillis() / (1000 * 60)
                val minutesSinceLastSlot = (currentMinute % 30).toInt()
                val minutesToNext = 30 - minutesSinceLastSlot

                Log.d("MainActivity", "Tiempo estimado hasta próxima ventana de ejecución: ~$minutesToNext minutos")
                Log.d("MainActivity", "NOTA: WorkManager puede agregar hasta 15 min adicionales de flexibilidad")
                Log.d("MainActivity", "Monitorea logcat buscando 'AutoExportWorker' para ver cuando se ejecute")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error verificando worker: ${e.message}")
            Log.e("MainActivity", "Stacktrace: ${e.stackTraceToString()}")
        }

        setContent {
            HealthConnectApp(healthConnectManager = healthConnectManager)
        }
    }
}