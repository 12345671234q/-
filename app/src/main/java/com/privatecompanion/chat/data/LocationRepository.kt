package com.privatecompanion.chat.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.resume

/** Requests a fresh location and, when configured, resolves it through AMap's reverse geocoder. */
class LocationRepository(private val context: Context) {
    private val manager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val httpClient = OkHttpClient()

    suspend fun getCurrentLocation(amapWebServiceKey: String = ""): LocationSnapshot {
        if (!hasFineLocationPermission()) {
            return LocationSnapshot(
                error = if (hasCoarseLocationPermission()) {
                    "目前只有大致位置权限。请在系统权限中允许“精确位置”后再刷新。"
                } else {
                    "需要位置权限。请在授权弹窗中选择“仅在使用时允许”，并开启精确位置。"
                },
            )
        }

        val snapshot = requestFusedFreshLocation()?.let { toSnapshot(it, "融合高精度定位") }
            ?: run {
                val gps = requestFreshProviderLocation(LocationManager.GPS_PROVIDER, 12_000L)
                val network = requestFreshProviderLocation(LocationManager.NETWORK_PROVIDER, 8_000L)
                chooseMostAccurate(gps, network)?.let(::toSnapshot)
            }
            ?: chooseMostAccurate(
                freshLastKnownLocation(LocationManager.GPS_PROVIDER),
                freshLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            )?.let { toSnapshot(it, "最近定位缓存") }
            ?: return LocationSnapshot(error = "未能获得新的精确位置。请打开定位服务，到室外稍候后再刷新。")

        return enrichWithAddress(snapshot, amapWebServiceKey)
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun requestFusedFreshLocation(): Location? = withTimeoutOrNull(FUSED_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            var finished = false
            fun finish(location: Location?) {
                if (!finished) {
                    finished = true
                    continuation.resume(location)
                }
            }
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setMaxUpdateAgeMillis(0L)
                .setDurationMillis(FUSED_TIMEOUT_MS)
                .build()
            fusedClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { finish(it?.takeIf(::isFresh)) }
                .addOnFailureListener { finish(null) }
                .addOnCanceledListener { finish(null) }
            continuation.invokeOnCancellation { cancellation.cancel() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshProviderLocation(provider: String, timeoutMs: Long): Location? {
        if (!manager.isProviderEnabled(provider)) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var finished = false
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!finished) {
                            finished = true
                            manager.removeUpdates(this)
                            continuation.resume(location.takeIf(::isFresh))
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                continuation.invokeOnCancellation {
                    if (!finished) manager.removeUpdates(listener)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun freshLastKnownLocation(provider: String): Location? = runCatching {
        manager.getLastKnownLocation(provider)
    }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time <= MAX_CACHE_AGE_MS }

    private fun chooseMostAccurate(vararg candidates: Location?): Location? =
        candidates.filterNotNull().minByOrNull { it.accuracy }

    private fun isFresh(location: Location): Boolean =
        System.currentTimeMillis() - location.time <= MAX_FRESH_AGE_MS

    private fun toSnapshot(location: Location, sourceLabel: String? = null) = LocationSnapshot(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracy = location.accuracy.toDouble(),
        provider = sourceLabel ?: providerLabel(location.provider),
        timestamp = location.time,
    )

    private fun providerLabel(provider: String?): String = when (provider) {
        LocationManager.GPS_PROVIDER -> "GPS"
        LocationManager.NETWORK_PROVIDER -> "网络定位"
        LocationManager.PASSIVE_PROVIDER -> "被动定位"
        else -> provider ?: "系统定位"
    }

    private suspend fun enrichWithAddress(snapshot: LocationSnapshot, apiKey: String): LocationSnapshot {
        if (apiKey.isBlank()) {
            return snapshot.copy(addressError = "未配置位置名称服务：请在“设定”中填写高德 Web 服务 Key。")
        }
        return runCatching {
            snapshot.copy(address = resolveWithAmap(snapshot, apiKey), addressError = null)
        }.getOrElse { error ->
            snapshot.copy(addressError = "位置名称解析失败：${error.message ?: "请检查高德 Key 和网络"}")
        }
    }

    private suspend fun resolveWithAmap(snapshot: LocationSnapshot, apiKey: String): ReverseGeocodedAddress =
        withContext(Dispatchers.IO) {
            val latitude = requireNotNull(snapshot.latitude)
            val longitude = requireNotNull(snapshot.longitude)
            // Android's location APIs return WGS-84. AMap's mainland map data uses GCJ-02.
            val (gcjLatitude, gcjLongitude) = wgs84ToGcj02(latitude, longitude)
            val url = AMAP_REVERSE_GEOCODE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("location", "%.6f,%.6f".format(Locale.US, gcjLongitude, gcjLatitude))
                .addQueryParameter("radius", "100")
                .addQueryParameter("extensions", "all")
                .addQueryParameter("output", "JSON")
                .build()
            val responseText = httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "服务返回 ${response.code}" }
                body
            }
            val root = JSONObject(responseText)
            check(root.optString("status") == "1") { root.optString("info", "高德服务未返回地址") }
            val regeo = root.getJSONObject("regeocode")
            val component = regeo.optJSONObject("addressComponent") ?: JSONObject()
            val neighborhood = component.optJSONObject("neighborhood")?.stringOrNull("name")
            val building = component.optJSONObject("building")?.stringOrNull("name")
            val streetNumber = component.optJSONObject("streetNumber")
            val street = streetNumber?.stringOrNull("street")
            val number = streetNumber?.stringOrNull("number")
            val aoi = regeo.optJSONArray("aois")?.optJSONObject(0)?.stringOrNull("name")
            val poi = regeo.optJSONArray("pois")?.optJSONObject(0)
            ReverseGeocodedAddress(
                formattedAddress = regeo.stringOrNull("formatted_address"),
                neighborhood = neighborhood,
                building = building,
                streetAddress = listOfNotNull(street, number).joinToString("").ifBlank { null },
                aoi = aoi,
                nearestPoi = poi?.stringOrNull("name"),
                nearestPoiDistanceMeters = poi?.stringOrNull("distance")?.toIntOrNull(),
            ).also { check(it.hasUsableName) { "服务没有返回可用的位置名称" } }
        }

    private fun JSONObject.stringOrNull(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "[]" }

    private fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271) return latitude to longitude
        var dLat = transformLatitude(longitude - 105.0, latitude - 35.0)
        var dLon = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = dLon * 180.0 / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return latitude + dLat to longitude + dLon
    }

    private fun transformLatitude(x: Double, y: Double): Double =
        -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x)) +
            (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0 +
            (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0 +
            (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0

    private fun transformLongitude(x: Double, y: Double): Double =
        300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x)) +
            (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0 +
            (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0 +
            (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0

    private companion object {
        const val AMAP_REVERSE_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo"
        const val FUSED_TIMEOUT_MS = 12_000L
        const val MAX_FRESH_AGE_MS = 20_000L
        const val MAX_CACHE_AGE_MS = 120_000L
        const val GCJ_A = 6378245.0
        const val GCJ_EE = 0.00669342162296594323
    }
}

data class ReverseGeocodedAddress(
    val formattedAddress: String?,
    val neighborhood: String?,
    val building: String?,
    val streetAddress: String?,
    val aoi: String?,
    val nearestPoi: String?,
    val nearestPoiDistanceMeters: Int?,
) {
    val hasUsableName: Boolean get() = listOf(formattedAddress, neighborhood, building, aoi, nearestPoi).any { !it.isNullOrBlank() }

    fun summaryForPrompt(): String = buildList {
        formattedAddress?.let { add("完整地址：$it") }
        neighborhood?.let { add("小区/社区：$it") }
        building?.let { add("建筑：$it") }
        streetAddress?.let { add("道路门牌：$it") }
        aoi?.let { add("所属区域：$it") }
        nearestPoi?.let { name ->
            add("最近 POI：$name${nearestPoiDistanceMeters?.let { "（约${it}米）" }.orEmpty()}")
        }
    }.joinToString("；")
}

data class LocationSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val provider: String? = null,
    val timestamp: Long? = null,
    val address: ReverseGeocodedAddress? = null,
    val addressError: String? = null,
    val error: String? = null,
) {
    val isAvailable: Boolean get() = latitude != null && longitude != null

    fun summaryForPrompt(): String? {
        if (!isAvailable) return null
        return buildString {
            append("已核验定位坐标：纬度${formatCoordinate(latitude)}，经度${formatCoordinate(longitude)}")
            accuracy?.let { append("，精度约${"%.0f".format(Locale.US, it)}米") }
            provider?.let { append("，来源：$it") }
            timestamp?.let { append("，获取时间：${formatTime(it)}") }
            address?.let { append("。已核验逆地理编码结果（高德）：${it.summaryForPrompt()}") }
        }
    }

    private fun formatCoordinate(value: Double?): String = "%.6f".format(Locale.US, value ?: 0.0)
    private fun formatTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
}
