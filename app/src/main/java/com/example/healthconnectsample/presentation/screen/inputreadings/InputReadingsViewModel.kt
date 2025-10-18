/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.healthconnectsample.presentation.screen.inputreadings

import android.os.RemoteException
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.healthconnectsample.data.HealthConnectManager
import com.example.healthconnectsample.data.WeightData
import com.example.healthconnectsample.data.dateTimeWithOffsetOrDefault
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class InputReadingsViewModel(private val healthConnectManager: HealthConnectManager) :
    ViewModel() {
    private val healthConnectCompatibleApps = healthConnectManager.healthConnectCompatibleApps

    val permissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        // v1.7.0 - Essential Metrics
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        // v1.8.0 - Body Measurements Group 2
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getWritePermission(HeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        // v2.0.0 - Complete Body Measurements
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class)
    )

    var weeklyAvg: MutableState<Mass?> = mutableStateOf(Mass.kilograms(0.0))
        private set

    var permissionsGranted = mutableStateOf(false)
        private set

    var readingsList: MutableState<List<WeightData>> = mutableStateOf(listOf())
        private set

    var uiState: UiState by mutableStateOf(UiState.Uninitialized)
        private set

    val permissionsLauncher = healthConnectManager.requestPermissionsActivityContract()

    fun initialLoad() {
        viewModelScope.launch {
            tryWithPermissionsCheck {
                readWeightInputs()
            }
        }
    }

    fun inputReadings(inputValue: Double) {
        viewModelScope.launch {
            tryWithPermissionsCheck {
                val time = ZonedDateTime.now().withNano(0)
                val weight = WeightRecord(
                    metadata = Metadata.manualEntry(),
                    weight = Mass.kilograms(inputValue),
                    time = time.toInstant(),
                    zoneOffset = time.offset
                )
                healthConnectManager.writeWeightInput(weight)
                readWeightInputs()
            }
        }
    }

    fun deleteWeightInput(uid: String) {
        viewModelScope.launch {
            tryWithPermissionsCheck {
                healthConnectManager.deleteWeightInput(uid)
                readWeightInputs()
            }
        }
    }

    private suspend fun readWeightInputs() {
        val startOfDay = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS)
        val now = Instant.now()
        val endofWeek = startOfDay.toInstant().plus(7, ChronoUnit.DAYS)
        readingsList.value = healthConnectManager
            .readWeightInputs(Instant.EPOCH, now)
            .map { record ->
                val packageName = record.metadata.dataOrigin.packageName
                WeightData(
                    weight = record.weight,
                    id = record.metadata.id,
                    time = dateTimeWithOffsetOrDefault(record.time, record.zoneOffset),
                    sourceAppInfo = healthConnectCompatibleApps[packageName]
                )
            }
        weeklyAvg.value =
            healthConnectManager.computeWeeklyAverage(startOfDay.toInstant(), endofWeek)
    }

    private suspend fun tryWithPermissionsCheck(block: suspend () -> Unit) {
        permissionsGranted.value = healthConnectManager.hasAllPermissions(permissions)
        uiState = try {
            if (permissionsGranted.value) {
                block()
            }
            UiState.Done
        } catch (remoteException: RemoteException) {
            UiState.Error(remoteException)
        } catch (securityException: SecurityException) {
            UiState.Error(securityException)
        } catch (ioException: IOException) {
            UiState.Error(ioException)
        } catch (illegalStateException: IllegalStateException) {
            UiState.Error(illegalStateException)
        }
    }

    sealed class UiState {
        object Uninitialized : UiState()
        object Done : UiState()
        data class Error(val exception: Throwable, val uuid: UUID = UUID.randomUUID()) : UiState()
    }
}

class InputReadingsViewModelFactory(
    private val healthConnectManager: HealthConnectManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InputReadingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InputReadingsViewModel(
                healthConnectManager = healthConnectManager
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
