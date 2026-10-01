package fr.simondornier.healthbridge

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import org.json.JSONArray
import org.json.JSONObject

object JsonUtil {
    fun metadata(record: Record): JSONObject = metadata(record.metadata)

    fun metadata(m: Metadata): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("lastModifiedTime", m.lastModifiedTime.toString())
        put("originPackage", m.dataOrigin.packageName)
        put("recordingMethod", m.recordingMethod)
        put("clientRecordId", m.clientRecordId ?: JSONObject.NULL)
        put("clientRecordVersion", m.clientRecordVersion)
        put("device", m.device?.let { d ->
            JSONObject().apply {
                put("manufacturer", d.manufacturer ?: JSONObject.NULL)
                put("model", d.model ?: JSONObject.NULL)
                put("type", d.type)
            }
        } ?: JSONObject.NULL)
    }

    fun array(items: Iterable<JSONObject>): JSONArray = JSONArray().apply {
        items.forEach { put(it) }
    }
}
