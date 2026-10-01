package fr.simondornier.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

class HealthExporter(private val context: Context) {
    private val client = HealthConnectClient.getOrCreate(context)

    suspend fun exportLive(windowHours: Long = 72): ExportResult {
        val granted = client.permissionController.getGrantedPermissions()
        val now = Instant.now()
        val start = now.minus(Duration.ofHours(windowHours))
        val timeFilter = TimeRangeFilter.between(start, now)

        val data = JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAt", now.toString())
            put("window", JSONObject().apply {
                put("from", start.toString())
                put("to", now.toString())
                put("hours", windowHours)
            })
            put("note", "Données brutes Santé Connect. Les champs originPackage et metadata.id servent à éviter les doubles comptages entre sources.")
        }
        val records = JSONObject()
        val errors = JSONArray()

        suspend fun <T : Record> guarded(
            key: String,
            type: KClass<T>,
            mapper: (T) -> JSONObject,
        ) {
            val permission = HealthPermission.getReadPermission(type)
            if (permission !in granted) {
                records.put(key, JSONObject().apply {
                    put("permissionGranted", false)
                    put("items", JSONArray())
                })
                return
            }
            try {
                val items = readAll(type, timeFilter).map(mapper)
                records.put(key, JSONObject().apply {
                    put("permissionGranted", true)
                    put("count", items.size)
                    put("items", JsonUtil.array(items))
                })
            } catch (e: Exception) {
                records.put(key, JSONObject().apply {
                    put("permissionGranted", true)
                    put("items", JSONArray())
                    put("error", e.message ?: e.javaClass.simpleName)
                })
                errors.put("$key: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        guarded("sleep", SleepSessionRecord::class) { r ->
            JSONObject().apply {
                put("startTime", r.startTime.toString())
                put("endTime", r.endTime.toString())
                put("durationMinutes", Duration.between(r.startTime, r.endTime).toMinutes())
                put("title", r.title ?: JSONObject.NULL)
                put("notes", r.notes ?: JSONObject.NULL)
                put("stages", JSONArray().apply {
                    r.stages.forEach { s ->
                        put(JSONObject().apply {
                            put("startTime", s.startTime.toString())
                            put("endTime", s.endTime.toString())
                            put("stage", s.stage)
                        })
                    }
                })
                put("metadata", JsonUtil.metadata(r))
            }
        }

        guarded("heartRate", HeartRateRecord::class) { r ->
            JSONObject().apply {
                put("startTime", r.startTime.toString())
                put("endTime", r.endTime.toString())
                put("samples", JSONArray().apply {
                    r.samples.forEach { s ->
                        put(JSONObject().apply {
                            put("time", s.time.toString())
                            put("bpm", s.beatsPerMinute)
                        })
                    }
                })
                put("metadata", JsonUtil.metadata(r))
            }
        }

        guarded("restingHeartRate", RestingHeartRateRecord::class) { r -> instant(r.time, "bpm", r.beatsPerMinute, r) }
        guarded("hrvRmssd", HeartRateVariabilityRmssdRecord::class) { r -> instant(r.time, "rmssdMs", r.heartRateVariabilityMillis, r) }
        guarded("respiratoryRate", RespiratoryRateRecord::class) { r -> instant(r.time, "breathsPerMinute", r.rate, r) }
        guarded("oxygenSaturation", OxygenSaturationRecord::class) { r -> instant(r.time, "percentage", r.percentage.value, r) }
        guarded("vo2Max", Vo2MaxRecord::class) { r ->
            JSONObject().apply {
                put("time", r.time.toString())
                put("mlPerMinPerKg", r.vo2MillilitersPerMinuteKilogram)
                put("measurementMethod", r.measurementMethod)
                put("metadata", JsonUtil.metadata(r))
            }
        }
        guarded("weight", WeightRecord::class) { r -> instant(r.time, "kg", r.weight.inKilograms, r) }
        guarded("bodyFat", BodyFatRecord::class) { r -> instant(r.time, "percentage", r.percentage.value, r) }

        guarded("steps", StepsRecord::class) { r -> interval(r.startTime, r.endTime, "count", r.count, r) }
        guarded("distance", DistanceRecord::class) { r -> interval(r.startTime, r.endTime, "meters", r.distance.inMeters, r) }
        guarded("activeCalories", ActiveCaloriesBurnedRecord::class) { r -> interval(r.startTime, r.endTime, "kcal", r.energy.inKilocalories, r) }
        guarded("totalCalories", TotalCaloriesBurnedRecord::class) { r -> interval(r.startTime, r.endTime, "kcal", r.energy.inKilocalories, r) }
        guarded("elevationGained", ElevationGainedRecord::class) { r -> interval(r.startTime, r.endTime, "meters", r.elevation.inMeters, r) }
        guarded("floorsClimbed", FloorsClimbedRecord::class) { r -> interval(r.startTime, r.endTime, "floors", r.floors, r) }
        guarded("hydration", HydrationRecord::class) { r -> interval(r.startTime, r.endTime, "liters", r.volume.inLiters, r) }

        guarded("exerciseSessions", ExerciseSessionRecord::class) { r ->
            JSONObject().apply {
                put("startTime", r.startTime.toString())
                put("endTime", r.endTime.toString())
                put("durationMinutes", Duration.between(r.startTime, r.endTime).toMinutes())
                put("exerciseType", r.exerciseType)
                put("title", r.title ?: JSONObject.NULL)
                put("notes", r.notes ?: JSONObject.NULL)
                put("laps", r.laps.size)
                put("segments", r.segments.size)
                put("metadata", JsonUtil.metadata(r))
            }
        }

        data.put("records", records)
        data.put("errors", errors)

        val status = buildStatus(now, windowHours, records, errors)
        return ExportResult(data.toString(2), status.toString(2), errors.length())
    }

    private fun buildStatus(now: Instant, windowHours: Long, records: JSONObject, errors: JSONArray): JSONObject {
        val counts = JSONObject()
        val keys = records.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val section = records.optJSONObject(key)
            counts.put(key, section?.optInt("count", section?.optJSONArray("items")?.length() ?: 0) ?: 0)
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("lastSync", now.toString())
            put("windowHours", windowHours)
            put("recordCounts", counts)
            put("errors", errors)
            put("backgroundPermission", HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    private fun instant(time: Instant, valueName: String, value: Any, record: Record): JSONObject =
        JSONObject().apply {
            put("time", time.toString())
            put(valueName, value)
            put("metadata", JsonUtil.metadata(record))
        }

    private fun interval(start: Instant, end: Instant, valueName: String, value: Any, record: Record): JSONObject =
        JSONObject().apply {
            put("startTime", start.toString())
            put("endTime", end.toString())
            put(valueName, value)
            put("metadata", JsonUtil.metadata(record))
        }

    private suspend fun <T : Record> readAll(type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    ascendingOrder = true,
                    pageSize = 1000,
                    pageToken = pageToken,
                )
            )
            all += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }
}

data class ExportResult(
    val liveJson: String,
    val statusJson: String,
    val errorCount: Int,
)
