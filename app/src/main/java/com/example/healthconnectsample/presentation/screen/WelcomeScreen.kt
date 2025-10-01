package com.example.healthconnectsample.presentation.screen.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.healthconnectsample.R
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import com.example.healthconnectsample.presentation.component.InstalledMessage
import com.example.healthconnectsample.presentation.component.NotInstalledMessage
import com.example.healthconnectsample.presentation.component.NotSupportedMessage
import com.example.healthconnectsample.worker.ChangeTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun WelcomeScreen(
    healthConnectAvailability: Int,
    onResumeAvailabilityCheck: () -> Unit,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var nextExportTime by remember { mutableStateOf("Calculando...") }
    var nextExportMinutes by remember { mutableStateOf<Long?>(null) }
    var isWorkerActive by remember { mutableStateOf(false) }
    val currentOnAvailabilityCheck by rememberUpdatedState(onResumeAvailabilityCheck)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentOnAvailabilityCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val workManager = WorkManager.getInstance(context)
                    val workInfosFuture = workManager.getWorkInfosForUniqueWork("auto_export_health_data")
                    val workInfos = workInfosFuture.get()

                    withContext(Dispatchers.Main) {
                        isWorkerActive = workInfos.any { workInfo ->
                            workInfo.state == WorkInfo.State.RUNNING ||
                                    workInfo.state == WorkInfo.State.ENQUEUED
                        }
                    }
                }

                val currentMinute = System.currentTimeMillis() / (1000 * 60)
                val minutesSinceLastSlot = (currentMinute % 30).toInt()
                val minutesToNext = 30 - minutesSinceLastSlot

                nextExportMinutes = minutesToNext.toLong()

                if (minutesToNext <= 5) {
                    nextExportTime = "Próximo export: en $minutesToNext min"
                } else {
                    nextExportTime = "Próximo export: ~$minutesToNext min"
                }
            } catch (e: Exception) {
                nextExportTime = "Export automático activo"
            }
            delay(30_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            modifier = Modifier.fillMaxWidth(0.5f),
            painter = painterResource(id = R.drawable.ic_health_connect_logo),
            contentDescription = stringResource(id = R.string.health_connect_logo)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(id = R.string.welcome_message),
            color = MaterialTheme.colors.onBackground
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            backgroundColor = if (isWorkerActive)
                Color(0xFF1B5E20)
            else
                Color(0xFF424242),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (isWorkerActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Export Automático",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nextExportTime,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Cada 30 min, 24/7 - Solo cambios incrementales",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                nextExportMinutes?.let { minutes ->
                    if (minutes in 1..30) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val progress = 1f - (minutes.toFloat() / 30f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(3.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(
                                        color = Color(0xFF4CAF50),
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                ChangeTokenManager(context).clearToken()
                android.widget.Toast.makeText(
                    context,
                    "Token reseteado. Próximo export será completo.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFFF6B6B)
            ),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                text = "🔄 Reset Export (Debug)",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (healthConnectAvailability) {
            SDK_AVAILABLE -> InstalledMessage()
            SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> NotInstalledMessage()
            SDK_UNAVAILABLE -> NotSupportedMessage()
        }
    }
}