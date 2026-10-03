package com.healthcoach.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class Baseline(
    val sleepMinutes: Double?,
    val restingHr: Double?,
    val hrv: Double?,
    val respiratory: Double?,
    val steps: Double?
)

data class HealthSummary(
    val generatedAt: Instant,
    val summaryDate: LocalDate,
    val latestSleepDate: LocalDate?,
    val latestSleepStart: Instant?,
    val latestSleepEnd: Instant?,
    val sleepMinutes: Double?,
    val lightMinutes: Double?,
    val deepMinutes: Double?,
    val remMinutes: Double?,
    val awakeMinutes: Double?,
    val overnightHr: Double?,
    val restingHr: Double?,
    val restingHrAt: Instant?,
    val hrv: Double?,
    val hrvAt: Instant?,
    val respiratory: Double?,
    val respiratoryAt: Instant?,
    val stepsToday: Long,
    val distanceTodayMeters: Double,
    val stepsYesterday: Long,
    val distanceYesterdayMeters: Double,
    val exerciseMinutes7d: Long,
    val exerciseSessions7d: Int,
    val weightKg: Double?,
    val baseline7: Baseline,
    val baseline30: Baseline,
    val baseline90: Baseline,
    val recoveryScore: Int,
    val recoveryLabel: String,
    val recoveryReliable: Boolean,
    val sleepSource: String?,
    val historyDaysAvailable: Int
) {
    fun toJson(): JSONObject {
        fun putMaybe(o: JSONObject, key: String, value: Any?) {
            if (value == null) o.put(key, JSONObject.NULL) else o.put(key, value)
        }
        fun baselineJson(b: Baseline) = JSONObject().apply {
            putMaybe(this, "sleepMinutes", b.sleepMinutes)
            putMaybe(this, "restingHeartRate", b.restingHr)
            putMaybe(this, "hrvRmssdMs", b.hrv)
            putMaybe(this, "respiratoryRate", b.respiratory)
            putMaybe(this, "steps", b.steps)
        }

        return JSONObject().apply {
            put("schemaVersion", 3)
            put("generatedAt", generatedAt.toString())
            put("date", summaryDate.toString())
            put("app", "HealthCoach")
            put("appVersion", "2.1.1")
            put("privacy", "Résumé calculé localement depuis Santé Connect; exporté uniquement vers le dossier choisi par l'utilisateur.")
            put("current", JSONObject().apply {
                putMaybe(this, "sleepDate", latestSleepDate?.toString())
                putMaybe(this, "sleepStart", latestSleepStart?.toString())
                putMaybe(this, "sleepEnd", latestSleepEnd?.toString())
                putMaybe(this, "sleepMinutes", sleepMinutes)
                putMaybe(this, "lightMinutes", lightMinutes)
                putMaybe(this, "deepMinutes", deepMinutes)
                putMaybe(this, "remMinutes", remMinutes)
                putMaybe(this, "awakeMinutes", awakeMinutes)
                putMaybe(this, "overnightHeartRate", overnightHr)
                putMaybe(this, "restingHeartRate", restingHr)
                putMaybe(this, "restingHeartRateAt", restingHrAt?.toString())
                putMaybe(this, "hrvRmssdMs", hrv)
                putMaybe(this, "hrvAt", hrvAt?.toString())
                putMaybe(this, "respiratoryRate", respiratory)
                putMaybe(this, "respiratoryRateAt", respiratoryAt?.toString())
                put("stepsToday", stepsToday)
                put("distanceTodayMeters", distanceTodayMeters)
                put("stepsYesterday", stepsYesterday)
                put("distanceYesterdayMeters", distanceYesterdayMeters)
                put("exerciseMinutes7d", exerciseMinutes7d)
                put("exerciseSessions7d", exerciseSessions7d)
                putMaybe(this, "weightKg", weightKg)
                putMaybe(this, "sleepSource", sleepSource)
            })
            put("dataFreshness", JSONObject().apply {
                putMaybe(this, "latestSleepDate", latestSleepDate?.toString())
                put("latestSleepIsCurrent", latestSleepDate == summaryDate)
                put("recoveryReliable", recoveryReliable)
                putMaybe(this, "latestSleepEnd", latestSleepEnd?.toString())
                putMaybe(this, "restingHeartRateAt", restingHrAt?.toString())
                putMaybe(this, "hrvAt", hrvAt?.toString())
                putMaybe(this, "respiratoryRateAt", respiratoryAt?.toString())
                put(
                    "note",
                    if (recoveryReliable)
                        "Les données principales de récupération correspondent à la nuit la plus récente disponible."
                    else
                        "La récupération utilise des données plus anciennes ou incomplètes. Ne pas les présenter comme celles de la nuit courante."
                )
            })
            put("baseline7d", baselineJson(baseline7))
            put("baseline30d", baselineJson(baseline30))
            put("baseline90d", baselineJson(baseline90))
            put("recovery", JSONObject().apply {
                put("score", recoveryScore)
                put("label", recoveryLabel)
                put("isReliable", recoveryReliable)
                putMaybe(this, "sourceSleepDate", latestSleepDate?.toString())
                put("method", "Score local explicable basé principalement sur sommeil, VFC et FC de repos par rapport aux références personnelles.")
            })
            put("historyDaysAvailable", historyDaysAvailable)
            put("chatgpt", JSONObject().apply {
                put("suggestedRequest", "Analyse mes données HealthCoach. Vérifie d'abord dataFreshness et la date réelle de la dernière nuit. Si recovery.isReliable=false, ne présente pas les données anciennes comme celles d'aujourd'hui. Compare ensuite aux références 7/30/90 jours et donne 2 à 4 conseils courts.")
            })
        }
    }
}

class HealthRepository(private val context: Context) {
    private val zone = ZoneId.systemDefault()

    suspend fun load(client: HealthConnectClient): HealthSummary {
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val ninetyDaysAgo = now.minus(Duration.ofDays(90))
        val thirtyDaysAgo = now.minus(Duration.ofDays(30))

        val range = try {
            val test = TimeRangeFilter.between(ninetyDaysAgo, now)
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, test))
            test
        } catch (_: Exception) {
            TimeRangeFilter.between(thirtyDaysAgo, now)
        }

        val sleeps = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range))
            .records.sortedBy { it.endTime }
        val rhr = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range))
            .records.sortedBy { it.time }
        val hrv = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range))
            .records.sortedBy { it.time }
        val resp = client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range))
            .records.sortedBy { it.time }
        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records
        val distances = client.readRecords(ReadRecordsRequest(DistanceRecord::class, range)).records
        val exercises = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
        val weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, range))
            .records.sortedBy { it.time }

        // Santé Connect peut contenir plusieurs sessions le même jour (sieste ou doublon).
        // Pour la récupération et les références, on conserve la session principale la plus longue.
        val primarySleepByDate = sleeps
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) ->
                records.maxByOrNull { Duration.between(it.startTime, it.endTime).toMinutes() }!!
            }
        val latestSleep = primarySleepByDate.values.maxByOrNull { it.endTime }
        val latestSleepDate = latestSleep?.endTime?.atZone(zone)?.toLocalDate()
        val sleepMinutes = latestSleep?.let { Duration.between(it.startTime, it.endTime).toMinutes().toDouble() }
        var light = 0.0
        var deep = 0.0
        var rem = 0.0
        var awake = 0.0
        latestSleep?.stages?.forEach { st ->
            val minutes = Duration.between(st.startTime, st.endTime).toMinutes().toDouble()
            when (st.stage) {
                1, 7 -> awake += minutes
                4 -> light += minutes
                5 -> deep += minutes
                6 -> rem += minutes
            }
        }

        val overnightHr = if (latestSleep != null) {
            val hrs = client.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(latestSleep.startTime, latestSleep.endTime)
                )
            ).records.flatMap { it.samples }.map { it.beatsPerMinute.toDouble() }
            hrs.averageOrNull()
        } else null

        val todayStart = today.atStartOfDay(zone).toInstant()
        val todayAggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(todayStart, now)
            )
        )
        val stepsToday = todayAggregate[StepsRecord.COUNT_TOTAL] ?: 0L
        val distanceToday = todayAggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0

        val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant()
        val yesterdayAggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(yesterdayStart, todayStart)
            )
        )
        val stepsYesterday = yesterdayAggregate[StepsRecord.COUNT_TOTAL] ?: 0L
        val distanceYesterday = yesterdayAggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0

        val weekStart = now.minus(Duration.ofDays(7))
        val recentExercise = exercises.filter { it.endTime.isAfter(weekStart) }
        val exerciseMinutes = recentExercise.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

        val dailySleep = primarySleepByDate.map { (date, s) ->
            date to Duration.between(s.startTime, s.endTime).toMinutes().toDouble()
        }

        val dailyRhr = rhr.map { it.time.atZone(zone).toLocalDate() to it.beatsPerMinute.toDouble() }
        val dailyHrv = hrv.map { it.time.atZone(zone).toLocalDate() to it.heartRateVariabilityMillis }
        val dailyResp = resp.map { it.time.atZone(zone).toLocalDate() to it.rate }
        val dailySteps = steps.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> records.sumOf { it.count }.toDouble() }

        fun baseline(days: Long): Baseline {
            val cutoff = today.minusDays(days - 1)
            return Baseline(
                sleepMinutes = dailySleep.filter { !it.first.isBefore(cutoff) }.map { it.second }.averageOrNull(),
                restingHr = dailyRhr.filter { !it.first.isBefore(cutoff) }.map { it.second }.averageOrNull(),
                hrv = dailyHrv.filter { !it.first.isBefore(cutoff) }.map { it.second }.averageOrNull(),
                respiratory = dailyResp.filter { !it.first.isBefore(cutoff) }.map { it.second }.averageOrNull(),
                steps = dailySteps.filterKeys { !it.isBefore(cutoff) }.values.toList().averageOrNull()
            )
        }

        val b7 = baseline(7)
        val b30 = baseline(30)
        val b90 = baseline(90)
        val latestRhrRecord = rhr.lastOrNull()
        val latestHrvRecord = hrv.lastOrNull()
        val latestRespRecord = resp.lastOrNull()
        val latestRhr = latestRhrRecord?.beatsPerMinute?.toDouble()
        val latestHrv = latestHrvRecord?.heartRateVariabilityMillis
        val latestResp = latestRespRecord?.rate

        val recoveryMetricTimes = listOfNotNull(latestRhrRecord?.time, latestHrvRecord?.time)
        val recoveryReliable =
            latestSleepDate == today &&
            recoveryMetricTimes.isNotEmpty() &&
            recoveryMetricTimes.all { !it.isBefore(now.minus(Duration.ofHours(36))) }

        var score = 80.0
        if (sleepMinutes != null && b30.sleepMinutes != null && b30.sleepMinutes > 0)
            score += ((sleepMinutes / b30.sleepMinutes) - 1.0) * 35.0
        if (latestHrv != null && b30.hrv != null && b30.hrv > 0)
            score += ((latestHrv / b30.hrv) - 1.0) * 25.0
        if (latestRhr != null && b30.restingHr != null)
            score -= (latestRhr - b30.restingHr) * 2.0

        val finalScore = score.coerceIn(20.0, 100.0).roundToInt()
        val label = if (!recoveryReliable) {
            "À actualiser"
        } else when {
            finalScore >= 80 -> "Bonne"
            finalScore >= 60 -> "Moyenne"
            else -> "Faible"
        }

        val summary = HealthSummary(
            generatedAt = now,
            summaryDate = today,
            latestSleepDate = latestSleepDate,
            latestSleepStart = latestSleep?.startTime,
            latestSleepEnd = latestSleep?.endTime,
            sleepMinutes = sleepMinutes,
            lightMinutes = if (latestSleep?.stages?.isNotEmpty() == true) light else null,
            deepMinutes = if (latestSleep?.stages?.isNotEmpty() == true) deep else null,
            remMinutes = if (latestSleep?.stages?.isNotEmpty() == true) rem else null,
            awakeMinutes = if (latestSleep?.stages?.isNotEmpty() == true) awake else null,
            overnightHr = overnightHr,
            restingHr = latestRhr,
            restingHrAt = latestRhrRecord?.time,
            hrv = latestHrv,
            hrvAt = latestHrvRecord?.time,
            respiratory = latestResp,
            respiratoryAt = latestRespRecord?.time,
            stepsToday = stepsToday,
            distanceTodayMeters = distanceToday,
            stepsYesterday = stepsYesterday,
            distanceYesterdayMeters = distanceYesterday,
            exerciseMinutes7d = exerciseMinutes,
            exerciseSessions7d = recentExercise.size,
            weightKg = weights.lastOrNull()?.weight?.inKilograms,
            baseline7 = b7,
            baseline30 = b30,
            baseline90 = b90,
            recoveryScore = finalScore,
            recoveryLabel = label,
            recoveryReliable = recoveryReliable,
            sleepSource = latestSleep?.metadata?.dataOrigin?.packageName,
            historyDaysAvailable = dailySleep.map { it.first }.distinct().size
        )
        saveLocalHistory(summary)
        return summary
    }

    private fun saveLocalHistory(summary: HealthSummary) {
        val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
        val raw = prefs.getString("daily_history", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val today = summary.summaryDate.toString()
        val out = JSONArray()
        var replaced = false

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("date") == today) {
                out.put(historyItem(summary))
                replaced = true
            } else out.put(item)
        }
        if (!replaced) out.put(historyItem(summary))

        while (out.length() > 400) {
            val trimmed = JSONArray()
            for (i in 1 until out.length()) trimmed.put(out.get(i))
            prefs.edit().putString("daily_history", trimmed.toString()).apply()
            return
        }
        prefs.edit().putString("daily_history", out.toString()).apply()
    }

    private fun historyItem(s: HealthSummary) = JSONObject().apply {
        put("date", s.summaryDate.toString())
        put("generatedAt", s.generatedAt.toString())
        putNullable("sleepDate", s.latestSleepDate?.toString())
        put("recoveryReliable", s.recoveryReliable)
        putNullable("sleepMinutes", s.sleepMinutes)
        putNullable("restingHeartRate", s.restingHr)
        putNullable("hrvRmssdMs", s.hrv)
        putNullable("respiratoryRate", s.respiratory)
        put("steps", s.stepsToday)
        put("distanceMeters", s.distanceTodayMeters)
        put("recoveryScore", s.recoveryScore)
    }

    fun localHistoryJson(): JSONObject {
        val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
        val raw = prefs.getString("daily_history", "[]") ?: "[]"
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAt", Instant.now().toString())
            put("days", try { JSONArray(raw) } catch (_: Exception) { JSONArray() })
        }
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
