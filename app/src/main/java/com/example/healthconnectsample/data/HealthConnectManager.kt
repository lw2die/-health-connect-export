/*
     * Copyright 2024 The Android Open Source Project
     */
package com.example.healthconnectsample.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources.NotFoundException
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.mutableStateOf
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import com.example.healthconnectsample.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.io.InvalidObjectException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.reflect.KClass

const val MIN_SUPPORTED_SDK = Build.VERSION_CODES.O_MR1

class HealthConnectManager(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val healthConnectCompatibleApps by lazy {
        val intent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        packages.associate {
            val icon = try { context.packageManager.getApplicationIcon(it.activityInfo.packageName) } catch (e: NotFoundException) { null }
            val label = context.packageManager.getApplicationLabel(it.activityInfo.applicationInfo).toString()
            it.activityInfo.packageName to HealthConnectAppInfo(packageName = it.activityInfo.packageName, icon = icon, appLabel = label)
        }
    }

    var availability = mutableStateOf(SDK_UNAVAILABLE)
        private set

    fun checkAvailability() { availability.value = HealthConnectClient.getSdkStatus(context) }
    init { checkAvailability() }

    suspend fun hasAllPermissions(permissions: Set<String>): Boolean {
        return healthConnectClient.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    fun requestPermissionsActivityContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    suspend fun revokeAllPermissions() { healthConnectClient.permissionController.revokeAllPermissions() }

    suspend fun readExerciseSessions(start: Instant, end: Instant): List<ExerciseSessionRecord> {
        val request = ReadRecordsRequest(recordType = ExerciseSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun writeExerciseSession(start: ZonedDateTime, end: ZonedDateTime): InsertRecordsResponse {
        return healthConnectClient.insertRecords(listOf(
            ExerciseSessionRecord(metadata = Metadata.manualEntry(), startTime = start.toInstant(), startZoneOffset = start.offset,
                endTime = end.toInstant(), endZoneOffset = end.offset, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = "My Run #${Random.nextInt(0, 60)}"),
            StepsRecord(metadata = Metadata.manualEntry(), startTime = start.toInstant(), startZoneOffset = start.offset,
                endTime = end.toInstant(), endZoneOffset = end.offset, count = (1000 + 1000 * Random.nextInt(3)).toLong()),
            DistanceRecord(metadata = Metadata.manualEntry(), startTime = start.toInstant(), startZoneOffset = start.offset,
                endTime = end.toInstant(), endZoneOffset = end.offset, distance = Length.meters((1000 + 100 * Random.nextInt(20)).toDouble())),
            TotalCaloriesBurnedRecord(metadata = Metadata.manualEntry(), startTime = start.toInstant(), startZoneOffset = start.offset,
                endTime = end.toInstant(), endZoneOffset = end.offset, energy = Energy.calories(140 + (Random.nextInt(20)) * 0.01))
        ) + buildHeartRateSeries(start, end))
    }

    suspend fun deleteExerciseSession(uid: String) {
        val exerciseSession = healthConnectClient.readRecord(ExerciseSessionRecord::class, uid)
        healthConnectClient.deleteRecords(ExerciseSessionRecord::class, recordIdsList = listOf(uid), clientRecordIdsList = emptyList())
        val timeRangeFilter = TimeRangeFilter.between(exerciseSession.record.startTime, exerciseSession.record.endTime)
        val rawDataTypes: Set<KClass<out Record>> = setOf(HeartRateRecord::class, SpeedRecord::class, DistanceRecord::class, StepsRecord::class, TotalCaloriesBurnedRecord::class)
        rawDataTypes.forEach { rawType -> healthConnectClient.deleteRecords(rawType, timeRangeFilter) }
    }

    suspend fun readAssociatedSessionData(uid: String): ExerciseSessionData {
        val exerciseSession = healthConnectClient.readRecord(ExerciseSessionRecord::class, uid)
        val timeRangeFilter = TimeRangeFilter.between(startTime = exerciseSession.record.startTime, endTime = exerciseSession.record.endTime)
        val aggregateDataTypes = setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL,
            TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_MIN)
        val aggregateRequest = AggregateRequest(metrics = aggregateDataTypes, timeRangeFilter = timeRangeFilter)
        val aggregateData = try { healthConnectClient.aggregate(aggregateRequest) } catch (e: Exception) { null }

        var fallbackSteps: Long? = null; var fallbackDistance: Length? = null; var fallbackTotalCalories: Energy? = null
        var fallbackActiveCalories: Energy? = null; var fallbackAvgHeartRate: Long? = null; var fallbackMaxHeartRate: Long? = null; var fallbackMinHeartRate: Long? = null
        val needsFallback = aggregateData == null || aggregateData.dataOrigins.isEmpty()

        if (needsFallback) {
            try { val sr = healthConnectClient.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter)).records; if (sr.isNotEmpty()) fallbackSteps = sr.sumOf { it.count } } catch (e: Exception) { }
            try { val dr = healthConnectClient.readRecords(ReadRecordsRequest(DistanceRecord::class, timeRangeFilter)).records; if (dr.isNotEmpty()) fallbackDistance = Length.meters(dr.sumOf { it.distance.inMeters }) } catch (e: Exception) { }
            try { val cr = healthConnectClient.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter)).records; if (cr.isNotEmpty()) fallbackTotalCalories = Energy.calories(cr.sumOf { it.energy.inCalories }) } catch (e: Exception) { }
            try { val acr = healthConnectClient.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter)).records; if (acr.isNotEmpty()) fallbackActiveCalories = Energy.calories(acr.sumOf { it.energy.inCalories }) } catch (e: Exception) { }
            try { val hrr = healthConnectClient.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter)).records; if (hrr.isNotEmpty()) { val bpm = hrr.flatMap { it.samples }.map { it.beatsPerMinute }; if (bpm.isNotEmpty()) { fallbackAvgHeartRate = bpm.average().toLong(); fallbackMaxHeartRate = bpm.maxOrNull(); fallbackMinHeartRate = bpm.minOrNull() }}} catch (e: Exception) { }
        }
        return ExerciseSessionData(uid = uid, totalActiveTime = aggregateData?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
            totalSteps = aggregateData?.get(StepsRecord.COUNT_TOTAL) ?: fallbackSteps, totalDistance = aggregateData?.get(DistanceRecord.DISTANCE_TOTAL) ?: fallbackDistance,
            totalEnergyBurned = aggregateData?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL) ?: fallbackTotalCalories, minHeartRate = aggregateData?.get(HeartRateRecord.BPM_MIN) ?: fallbackMinHeartRate,
            maxHeartRate = aggregateData?.get(HeartRateRecord.BPM_MAX) ?: fallbackMaxHeartRate, avgHeartRate = aggregateData?.get(HeartRateRecord.BPM_AVG) ?: fallbackAvgHeartRate,
            activeCalories = aggregateData?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) ?: fallbackActiveCalories)
    }

    suspend fun deleteAllSleepData() { healthConnectClient.deleteRecords(SleepSessionRecord::class, TimeRangeFilter.before(Instant.now())) }

    suspend fun generateSleepData() {
        val records = mutableListOf<Record>()
        val lastDay = ZonedDateTime.now().minusDays(1).truncatedTo(ChronoUnit.DAYS)
        val notes = context.resources.getStringArray(R.array.sleep_notes_array)
        for (i in 0..7) {
            val wakeUp = lastDay.minusDays(i.toLong()).withHour(Random.nextInt(7, 10)).withMinute(Random.nextInt(0, 60))
            val bedtime = wakeUp.minusDays(1).withHour(Random.nextInt(19, 22)).withMinute(Random.nextInt(0, 60))
            records.add(SleepSessionRecord(metadata = Metadata.manualEntry(), notes = notes[Random.nextInt(0, notes.size)],
                startTime = bedtime.toInstant(), startZoneOffset = bedtime.offset, endTime = wakeUp.toInstant(),
                endZoneOffset = wakeUp.offset, stages = generateSleepStages(bedtime, wakeUp)))
        }
        healthConnectClient.insertRecords(records)
    }

    suspend fun readSleepSessions(): List<SleepSessionData> {
        val lastDay = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).minusDays(1).withHour(12)
        val firstDay = lastDay.minusDays(7)
        val sessions = mutableListOf<SleepSessionData>()
        val sleepSessionRequest = ReadRecordsRequest(recordType = SleepSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(firstDay.toInstant(), lastDay.toInstant()), ascendingOrder = false)
        val sleepSessions = healthConnectClient.readRecords(sleepSessionRequest)
        sleepSessions.records.forEach { session ->
            val sessionTimeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
            val durationAggregateRequest = AggregateRequest(metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), timeRangeFilter = sessionTimeFilter)
            val aggregateResponse = healthConnectClient.aggregate(durationAggregateRequest)
            sessions.add(SleepSessionData(uid = session.metadata.id, title = session.title, notes = session.notes,
                startTime = session.startTime, startZoneOffset = session.startZoneOffset, endTime = session.endTime,
                endZoneOffset = session.endZoneOffset, duration = aggregateResponse[SleepSessionRecord.SLEEP_DURATION_TOTAL], stages = session.stages))
        }
        return sessions
    }

    suspend fun readSleepSessions(start: Instant, end: Instant): List<SleepSessionRecord> {
        val request = ReadRecordsRequest(recordType = SleepSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun writeWeightInput(weight: WeightRecord) { healthConnectClient.insertRecords(listOf(weight)) }

    suspend fun readWeightInputs(start: Instant, end: Instant): List<WeightRecord> {
        val request = ReadRecordsRequest(recordType = WeightRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readVo2MaxRecords(start: Instant, end: Instant): List<Vo2MaxRecord> {
        val request = ReadRecordsRequest(recordType = Vo2MaxRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readStepsRecords(start: Instant, end: Instant): List<StepsRecord> {
        val request = ReadRecordsRequest(recordType = StepsRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readDistanceRecords(start: Instant, end: Instant): List<DistanceRecord> {
        val request = ReadRecordsRequest(recordType = DistanceRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readTotalCaloriesBurnedRecords(start: Instant, end: Instant): List<TotalCaloriesBurnedRecord> {
        val request = ReadRecordsRequest(recordType = TotalCaloriesBurnedRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readRestingHeartRateRecords(start: Instant, end: Instant): List<RestingHeartRateRecord> {
        val request = ReadRecordsRequest(recordType = RestingHeartRateRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readOxygenSaturationRecords(start: Instant, end: Instant): List<OxygenSaturationRecord> {
        val request = ReadRecordsRequest(recordType = OxygenSaturationRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readHeightRecords(start: Instant, end: Instant): List<HeightRecord> {
        val request = ReadRecordsRequest(recordType = HeightRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readBodyFatRecords(start: Instant, end: Instant): List<BodyFatRecord> {
        val request = ReadRecordsRequest(recordType = BodyFatRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readLeanBodyMassRecords(start: Instant, end: Instant): List<LeanBodyMassRecord> {
        val request = ReadRecordsRequest(recordType = LeanBodyMassRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readBoneMassRecords(start: Instant, end: Instant): List<BoneMassRecord> {
        val request = ReadRecordsRequest(recordType = BoneMassRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readBasalMetabolicRateRecords(start: Instant, end: Instant): List<BasalMetabolicRateRecord> {
        val request = ReadRecordsRequest(recordType = BasalMetabolicRateRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readBodyWaterMassRecords(start: Instant, end: Instant): List<BodyWaterMassRecord> {
        val request = ReadRecordsRequest(recordType = BodyWaterMassRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readHeartRateRecords(start: Instant, end: Instant): List<HeartRateRecord> {
        val request = ReadRecordsRequest(recordType = HeartRateRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readBloodPressureRecords(start: Instant, end: Instant): List<BloodPressureRecord> {
        val request = ReadRecordsRequest(recordType = BloodPressureRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun readBloodGlucoseRecords(start: Instant, end: Instant): List<BloodGlucoseRecord> {
        val request = ReadRecordsRequest(recordType = BloodGlucoseRecord::class, timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.readRecords(request).records
    }

    suspend fun computeWeeklyAverage(start: Instant, end: Instant): Mass? {
        val request = AggregateRequest(metrics = setOf(WeightRecord.WEIGHT_AVG), timeRangeFilter = TimeRangeFilter.between(start, end))
        return healthConnectClient.aggregate(request)[WeightRecord.WEIGHT_AVG]
    }

    suspend fun deleteWeightInput(uid: String) {
        healthConnectClient.deleteRecords(WeightRecord::class, recordIdsList = listOf(uid), clientRecordIdsList = emptyList())
    }

    suspend fun getChangesToken(dataTypes: Set<KClass<out Record>>): String {
        return healthConnectClient.getChangesToken(ChangesTokenRequest(dataTypes))
    }

    suspend fun getChanges(token: String): Flow<ChangesMessage> = flow {
        var nextChangesToken = token
        do {
            val response = healthConnectClient.getChanges(nextChangesToken)
            if (response.changesTokenExpired) throw IOException("Changes token has expired")
            emit(ChangesMessage.ChangeList(response.changes))
            nextChangesToken = response.nextChangesToken
        } while (response.hasMore)
        emit(ChangesMessage.NoMoreChanges(nextChangesToken))
    }

    private fun generateSleepStages(start: ZonedDateTime, end: ZonedDateTime): List<SleepSessionRecord.Stage> {
        val sleepStages = mutableListOf<SleepSessionRecord.Stage>()
        var stageStart = start
        while (stageStart < end) {
            val stageEnd = stageStart.plusMinutes(Random.nextLong(30, 120))
            val checkedEnd = if (stageEnd > end) end else stageEnd
            sleepStages.add(SleepSessionRecord.Stage(stage = randomSleepStage(), startTime = stageStart.toInstant(), endTime = checkedEnd.toInstant()))
            stageStart = checkedEnd
        }
        return sleepStages
    }

    suspend fun fetchSeriesRecordsFromUid(recordType: KClass<out Record>, uid: String, seriesRecordsType: KClass<out Record>): List<Record> {
        val recordResponse = healthConnectClient.readRecord(recordType, uid)
        val timeRangeFilter = when (recordResponse.record) {
            is ExerciseSessionRecord -> { val record = recordResponse.record as ExerciseSessionRecord; TimeRangeFilter.between(startTime = record.startTime, endTime = record.endTime) }
            is SleepSessionRecord -> { val record = recordResponse.record as SleepSessionRecord; TimeRangeFilter.between(startTime = record.startTime, endTime = record.endTime) }
            else -> throw InvalidObjectException("Record with unregistered data type returned")
        }
        return healthConnectClient.readRecords(ReadRecordsRequest(recordType = seriesRecordsType, timeRangeFilter = timeRangeFilter)).records
    }

    private fun buildHeartRateSeries(sessionStartTime: ZonedDateTime, sessionEndTime: ZonedDateTime): HeartRateRecord {
        val samples = mutableListOf<HeartRateRecord.Sample>()
        var time = sessionStartTime
        while (time.isBefore(sessionEndTime)) {
            samples.add(HeartRateRecord.Sample(time = time.toInstant(), beatsPerMinute = (80 + Random.nextInt(80)).toLong()))
            time = time.plusSeconds(30)
        }
        return HeartRateRecord(metadata = Metadata.manualEntry(), startTime = sessionStartTime.toInstant(), startZoneOffset = sessionStartTime.offset,
            endTime = sessionEndTime.toInstant(), endZoneOffset = sessionEndTime.offset, samples = samples)
    }

    fun isFeatureAvailable(feature: Int): Boolean {
        return healthConnectClient.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    private fun randomSleepStage(): Int {
        return listOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM).random()
    }

    sealed class ChangesMessage {
        data class NoMoreChanges(val nextChangesToken: String) : ChangesMessage()
        data class ChangeList(val changes: List<Change>) : ChangesMessage()
    }
}