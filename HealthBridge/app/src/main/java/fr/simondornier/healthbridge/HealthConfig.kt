package fr.simondornier.healthbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import kotlin.reflect.KClass

object HealthConfig {
    val recordTypes: List<KClass<out Record>> = listOf(
        SleepSessionRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        RespiratoryRateRecord::class,
        OxygenSaturationRecord::class,
        StepsRecord::class,
        DistanceRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class,
        ElevationGainedRecord::class,
        FloorsClimbedRecord::class,
        WeightRecord::class,
        BodyFatRecord::class,
        Vo2MaxRecord::class,
        HydrationRecord::class,
    )

    fun requestedPermissions(client: HealthConnectClient): Set<String> {
        val result = recordTypes.mapTo(mutableSetOf()) { HealthPermission.getReadPermission(it) }

        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            result += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        }
        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            result += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        }
        return result
    }
}
