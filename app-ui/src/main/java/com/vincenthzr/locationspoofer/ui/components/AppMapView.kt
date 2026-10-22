package com.vincenthzr.locationspoofer.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vincenthzr.locationspoofer.data.model.AppMapType
import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.ui.components.map.AMapViewContainer
import com.vincenthzr.locationspoofer.ui.components.map.BaiduMapViewContainer
import com.vincenthzr.locationspoofer.ui.components.map.GoogleMapViewContainer
import com.vincenthzr.locationspoofer.ui.components.map.OsmMapViewContainer

interface AppMapMarker {
    fun setPosition(lat: Double, lng: Double)
}

enum class MarkerType { GREEN, RED, ORANGE, DEFAULT }

interface AppMapController {
    fun clear()
    fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float)
    fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    )

    fun addMarker(lat: Double, lng: Double, title: String, type: MarkerType): AppMapMarker
    fun animateCamera(lat: Double, lng: Double, zoom: Float? = null)
    fun fitBounds(points: List<Pair<Double, Double>>, padding: Int)
    fun fitBounds(
        points: List<Pair<Double, Double>>,
        paddingLeft: Int,
        paddingTop: Int,
        paddingRight: Int,
        paddingBottom: Int
    ) {
        fitBounds(points, maxOf(paddingLeft, paddingRight, paddingTop, paddingBottom))
    }

    fun moveCamera(lat: Double, lng: Double, zoom: Float? = null)
    val cameraTargetLat: Double?
    val cameraTargetLng: Double?
    fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit)
    fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit)
    fun disableUiControls()
    fun setMapType(type: AppMapType)
    fun setDarkMode(isDark: Boolean, context: android.content.Context)
}

// Re-export implementations for backwards compatibility if needed
typealias AMapControllerImpl = com.vincenthzr.locationspoofer.ui.components.map.AMapControllerImpl
typealias BaiduMapControllerImpl = com.vincenthzr.locationspoofer.ui.components.map.BaiduMapControllerImpl
typealias GMapControllerImpl = com.vincenthzr.locationspoofer.ui.components.map.GMapControllerImpl
typealias GaodeMarkerHelper = com.vincenthzr.locationspoofer.ui.components.map.GaodeMarkerHelper

@Composable
fun AppMapView(
    mapEngine: MapEngine,
    isDomestic: Boolean,
    modifier: Modifier = Modifier,
    onMapReady: (AppMapController) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDark = isSystemInDarkTheme()
    var mapController by remember { mutableStateOf<AppMapController?>(null) }

    LaunchedEffect(isDark, mapController) {
        mapController?.setDarkMode(isDark, context)
    }

    // AUTO：用户自备了高德 Key 就沿用高德（保留原有体验），否则回退到无需 Key 的 OSM。
    // 这样全新安装、无任何 Key 时也能直接出图。
    val activeEngine = when {
        mapEngine != MapEngine.AUTO -> mapEngine
        amapKeyAvailable(context) -> MapEngine.AMAP
        else -> MapEngine.OSM
    }

    when (activeEngine) {
        MapEngine.OSM -> {
            OsmMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }

        MapEngine.BAIDU -> {
            BaiduMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }

        MapEngine.GOOGLE -> {
            GoogleMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }

        else -> {
            AMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }
    }
}

/**
 * 是否具备可用的高德 Key。
 *
 * 两个来源任一非空即视为可用：
 * 1. manifest 中的 `com.amap.api.v2.apikey`（构建期由 local.yml / 环境变量注入的占位符最终值）；
 * 2. App 内设置页写入的 `app_settings` → `amap_api_key`（运行时覆盖）。
 *
 * app-ui 是 library 模块，拿不到 :app 的 BuildConfig，因此直接读 meta-data 最稳。
 */
private fun amapKeyAvailable(context: android.content.Context): Boolean {
    val fromManifest = runCatching {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_META_DATA
        )
        appInfo.metaData?.getString("com.amap.api.v2.apikey")?.trim()
    }.getOrNull()
    if (!fromManifest.isNullOrEmpty()) return true

    val fromPrefs = runCatching {
        context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .getString("amap_api_key", "")?.trim()
    }.getOrNull()
    return !fromPrefs.isNullOrEmpty()
}
