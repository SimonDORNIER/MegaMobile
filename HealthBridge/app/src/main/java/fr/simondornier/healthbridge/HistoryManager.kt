package fr.simondornier.healthbridge

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

object HistoryManager {
    fun merge(existingText: String?, liveText: String): String {
        val root = runCatching { if (existingText.isNullOrBlank()) JSONObject() else JSONObject(existingText) }.getOrElse { JSONObject() }
        root.put("schemaVersion", 1)
        root.put("updatedAt", Instant.now().toString())
        root.put("description", "Résumé longitudinal compact. Les données brutes récentes restent dans health_live.json.")
        val days = root.optJSONObject("days") ?: JSONObject().also { root.put("days", it) }

        val live = JSONObject(liveText)
        val records = live.optJSONObject("records") ?: return root.toString(2)
        val zone = ZoneId.systemDefault()
        val touched = linkedSetOf<String>()

        fun dayOf(iso: String): String = Instant.parse(iso).atZone(zone).toLocalDate().toString()
        fun dayObj(day: String): JSONObject {
            touched += day
            return days.optJSONObject(day) ?: JSONObject().also { d ->
                d.put("date", day)
                days.put(day, d)
            }
        }
        fun metadataOrigin(item: JSONObject): String = item.optJSONObject("metadata")?.optString("originPackage").orEmpty().ifBlank { "unknown" }

        // Sommeil : conserve la session principale terminant ce jour.
        records.optJSONObject("sleep")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val end = item.optString("endTime")
                if (end.isBlank()) continue
                val day = dayOf(end)
                val d = dayObj(day)
                val duration = item.optLong("durationMinutes", 0)
                val current = d.optJSONObject("sleep")
                if (current == null || duration > current.optLong("durationMinutes", 0)) {
                    d.put("sleep", JSONObject().apply {
                        put("startTime", item.optString("startTime"))
                        put("endTime", end)
                        put("durationMinutes", duration)
                        put("originPackage", metadataOrigin(item))
                        put("stages", item.optJSONArray("stages") ?: JSONArray())
                    })
                }
            }
        }

        fun instantStats(section: String, timeKey: String, valueKey: String, outKey: String) {
            records.optJSONObject(section)?.optJSONArray("items")?.let { arr ->
                val byDay = linkedMapOf<String, MutableList<Double>>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val t = item.optString(timeKey)
                    if (t.isBlank() || !item.has(valueKey)) continue
                    byDay.getOrPut(dayOf(t)) { mutableListOf() }.add(item.optDouble(valueKey))
                }
                byDay.forEach { (day, values) ->
                    if (values.isNotEmpty()) dayObj(day).put(outKey, JSONObject().apply {
                        put("mean", values.average())
                        put("min", values.minOrNull())
                        put("max", values.maxOrNull())
                        put("samples", values.size)
                    })
                }
            }
        }
        instantStats("restingHeartRate", "time", "bpm", "restingHeartRate")
        instantStats("hrvRmssd", "time", "rmssdMs", "hrvRmssd")
        instantStats("respiratoryRate", "time", "breathsPerMinute", "respiratoryRate")
        instantStats("oxygenSaturation", "time", "percentage", "oxygenSaturation")
        instantStats("vo2Max", "time", "mlPerMinPerKg", "vo2Max")
        instantStats("weight", "time", "kg", "weight")
        instantStats("bodyFat", "time", "percentage", "bodyFat")

        fun intervalTotals(section: String, valueKey: String, outKey: String) {
            records.optJSONObject(section)?.optJSONArray("items")?.let { arr ->
                val byDaySource = linkedMapOf<String, MutableMap<String, Double>>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val start = item.optString("startTime")
                    if (start.isBlank() || !item.has(valueKey)) continue
                    val day = dayOf(start)
                    val source = metadataOrigin(item)
                    val sourceMap = byDaySource.getOrPut(day) { linkedMapOf() }
                    sourceMap[source] = (sourceMap[source] ?: 0.0) + item.optDouble(valueKey)
                }
                byDaySource.forEach { (day, sources) ->
                    val preferred = when {
                        sources.containsKey("com.fitbit.FitbitMobile") -> "com.fitbit.FitbitMobile"
                        else -> sources.maxByOrNull { it.value }?.key ?: "unknown"
                    }
                    dayObj(day).put(outKey, JSONObject().apply {
                        put("value", sources[preferred] ?: 0.0)
                        put("originPackage", preferred)
                        put("sources", JSONObject(sources as Map<*, *>))
                    })
                }
            }
        }
        intervalTotals("steps", "count", "steps")
        intervalTotals("distance", "meters", "distanceMeters")
        intervalTotals("activeCalories", "kcal", "activeCaloriesKcal")
        intervalTotals("totalCalories", "kcal", "totalCaloriesKcal")
        intervalTotals("elevationGained", "meters", "elevationMeters")
        intervalTotals("floorsClimbed", "floors", "floorsClimbed")
        intervalTotals("hydration", "liters", "hydrationLiters")

        records.optJSONObject("exerciseSessions")?.optJSONArray("items")?.let { arr ->
            val byDay = linkedMapOf<String, JSONArray>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val start = item.optString("startTime")
                if (start.isBlank()) continue
                val day = dayOf(start)
                byDay.getOrPut(day) { JSONArray() }.put(JSONObject().apply {
                    put("startTime", start)
                    put("endTime", item.optString("endTime"))
                    put("durationMinutes", item.optLong("durationMinutes", 0))
                    put("exerciseType", item.optInt("exerciseType", 0))
                    put("title", item.opt("title") ?: JSONObject.NULL)
                    put("originPackage", metadataOrigin(item))
                })
            }
            byDay.forEach { (day, sessions) -> dayObj(day).put("exerciseSessions", sessions) }
        }

        touched.forEach { day -> days.optJSONObject(day)?.put("lastUpdatedAt", Instant.now().toString()) }
        root.put("dayCount", days.length())
        return root.toString(2)
    }
}
