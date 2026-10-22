@file:Suppress("DEPRECATION")

package com.vincenthzr.locationspoofer.ui.components.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.vincenthzr.locationspoofer.data.model.AppMapType
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.components.AppMapMarker
import com.vincenthzr.locationspoofer.ui.components.MarkerType
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * OSM（MapLibre）地图引擎 —— 无需任何 Key / 账号 / 计费，是本项目的默认底图。
 *
 * 坐标系：上层统一存 GCJ-02，OSM 瓦片是 WGS-84，
 * 因此进入 SDK 前 gcj02 → WGS-84，cameraTarget* / 相机回调返回时 WGS-84 → GCJ-02。
 *
 * 深色模式不改 style（setStyle 会清空全部图层与标注），
 * 而是改写 raster 图层的 paint 属性实现变暗。
 *
 * 地图类型（NORMAL / SATELLITE）切换需要换数据源，
 * 因此记录所有叠加物快照，setStyle 完成后原样重建，避免标注丢失。
 *
 * MapLibre API 约定（容易写错的地方）：
 *  - Style.addSource / addLayer 都是单参数，id 写在构造里；
 *  - 图层属性统一用 `withProperties(PropertyFactory.xxx(...))`，没有 Kotlin setter；
 *  - map.style 与 cameraPosition.target 都是可空类型，必须先判空；
 *  - setStyle 是异步的，叠加物必须在回调里重建。
 */
class MapLibreControllerImpl(
    private val map: MapLibreMap,
    private val context: Context
) : AppMapController {

    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL
    private var styleApplied: Boolean = false

    // MapLibre 没有全局 clear()，自行持有图层/源 id 与 marker 以便逐个移除。
    private val overlayIds = mutableSetOf<String>()
    private val markerList = mutableListOf<Marker>()
    private var idSeed = 0L

    /** 已绘制内容的快照，用于 setStyle 之后原样重建。 */
    private val polylines = mutableListOf<PolylineSpec>()
    private val circles = mutableListOf<CircleSpec>()
    private val markerSpecs = mutableListOf<MarkerSpec>()

    private data class PolylineSpec(
        val points: List<Pair<Double, Double>>,
        val color: Int,
        val width: Float
    )

    private data class CircleSpec(
        val lat: Double,
        val lng: Double,
        val radius: Double,
        val fill: Int,
        val stroke: Int,
        val strokeWidth: Float
    )

    private data class MarkerSpec(
        val lat: Double,
        val lng: Double,
        val title: String,
        val type: MarkerType
    )

    private var cameraFinishListener: ((Double, Double) -> Unit)? = null
    private var cameraMoveListener: ((Double, Double) -> Unit)? = null

    init {
        map.addOnCameraMoveListener {
            val target = map.cameraPosition.target ?: return@addOnCameraMoveListener
            val gcj = wgs84ToGcj02(target.latitude, target.longitude)
            cameraMoveListener?.invoke(gcj.first, gcj.second)
        }
        map.addOnCameraIdleListener {
            val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
            val gcj = wgs84ToGcj02(target.latitude, target.longitude)
            cameraFinishListener?.invoke(gcj.first, gcj.second)
        }
    }

    // ---------------- 叠加物 ----------------

    override fun clear() {
        markerList.forEach { runCatching { it.remove() } }
        markerList.clear()
        markerSpecs.clear()
        overlayIds.toList().forEach { removeOverlay(it) }
        overlayIds.clear()
        polylines.clear()
        circles.clear()
    }

    override fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        if (points.size < 2) return
        val spec = PolylineSpec(points, colorInt, width)
        polylines.add(spec)
        drawPolyline(spec)
    }

    private fun drawPolyline(spec: PolylineSpec) {
        val style = map.style ?: return
        val id = nextId("line")
        val pts = spec.points.map { p ->
            val wgs = gcj02ToWgs84(p.first, p.second)
            Point.fromLngLat(wgs.second, wgs.first)
        }
        val fc = FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts)))
        style.addSource(GeoJsonSource(id, fc))
        style.addLayer(
            LineLayer(layerIdOf(id), id).withProperties(
                PropertyFactory.lineColor(spec.color),
                PropertyFactory.lineWidth(spec.width),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        overlayIds.add(id)
    }

    override fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    ) {
        val spec = CircleSpec(lat, lng, radius, fillColorInt, strokeColorInt, strokeWidth)
        circles.add(spec)
        drawCircle(spec)
    }

    private fun drawCircle(spec: CircleSpec) {
        val style = map.style ?: return
        val id = nextId("circle")
        val wgs = gcj02ToWgs84(spec.lat, spec.lng)
        val ring = circleRing(wgs.first, wgs.second, spec.radius)
            .map { Point.fromLngLat(it[0], it[1]) }
        val fc = FeatureCollection.fromFeature(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        )
        style.addSource(GeoJsonSource(id, fc))
        style.addLayer(
            FillLayer(layerIdOf(id), id).withProperties(
                PropertyFactory.fillColor(spec.fill),
                PropertyFactory.fillOutlineColor(spec.stroke)
            )
        )
        overlayIds.add(id)
    }

    /** 以 (lat,lng) 为中心生成近似圆形多边形顶点（WGS-84，radiusMeters 为半径）。 */
    private fun circleRing(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Double
    ): List<List<Double>> {
        val segments = 64
        val metersPerDegLat = 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(centerLat))
        // 高纬度处经度跨度会变得极大，做下限保护避免除零/数值爆炸
        val safeCos = if (kotlin.math.abs(cosLat) < 0.01) 0.01 else cosLat
        val metersPerDegLng = metersPerDegLat * safeCos

        val ring = ArrayList<List<Double>>(segments + 1)
        for (i in 0..segments) {
            val angle = 2.0 * Math.PI * i / segments
            val lat = centerLat + (radiusMeters / metersPerDegLat) * kotlin.math.cos(angle)
            val lng = centerLng + (radiusMeters / metersPerDegLng) * kotlin.math.sin(angle)
            ring.add(listOf(lng, lat))
        }
        // 显式闭合，确保首尾点一致
        ring.add(ring.first())
        return ring
    }

    override fun addMarker(
        lat: Double,
        lng: Double,
        title: String,
        type: MarkerType
    ): AppMapMarker {
        val spec = MarkerSpec(lat, lng, title, type)
        markerSpecs.add(spec)
        // 地图 style 尚未就绪时 addMarker 可能抛异常，此时先记录快照，
        // 待 setStyle 回调里的 rebuildOverlays 统一补画。
        val marker = runCatching { drawMarker(spec) }.getOrNull()
        if (marker != null) markerList.add(marker)
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                val p = gcj02ToWgs84(lat, lng)
                runCatching { marker?.position = LatLng(p.first, p.second) }
                val idx = markerSpecs.indexOf(spec)
                if (idx >= 0) markerSpecs[idx] = spec.copy(lat = lat, lng = lng)
            }
        }
    }

    private fun drawMarker(spec: MarkerSpec): Marker {
        val wgs = gcj02ToWgs84(spec.lat, spec.lng)
        val bitmap = GaodeMarkerHelper.getMarkerBitmap(context, spec.title, spec.type)
        return map.addMarker(
            MarkerOptions()
                .position(LatLng(wgs.first, wgs.second))
                .icon(IconFactory.getInstance(context).fromBitmap(bitmap))
                .title(spec.title)
        )
    }

    private fun layerIdOf(sourceId: String): String = "$sourceId-layer"

    private fun removeOverlay(id: String) {
        val style = map.style ?: return
        runCatching { style.removeLayer(layerIdOf(id)) }
        runCatching { style.removeSource(id) }
    }

    private fun nextId(prefix: String): String = "$prefix-${++idSeed}"

    // ---------------- 相机 ----------------

    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        val wgs = gcj02ToWgs84(lat, lng)
        val target = LatLng(wgs.first, wgs.second)
        if (zoom != null) {
            // MapLibre 的 newLatLngZoom 第二参数是 double，而 AppMapController 用 Float，必须显式转换
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom.toDouble()))
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(target))
        }
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float?) {
        val wgs = gcj02ToWgs84(lat, lng)
        val target = LatLng(wgs.first, wgs.second)
        if (zoom != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom.toDouble()))
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLng(target))
        }
    }

    override fun fitBounds(points: List<Pair<Double, Double>>, padding: Int) {
        fitBounds(points, padding, padding, padding, padding)
    }

    override fun fitBounds(
        points: List<Pair<Double, Double>>,
        paddingLeft: Int,
        paddingTop: Int,
        paddingRight: Int,
        paddingBottom: Int
    ) {
        if (points.isEmpty()) return
        val builder = LatLngBounds.Builder()
        points.forEach {
            val wgs = gcj02ToWgs84(it.first, it.second)
            builder.include(LatLng(wgs.first, wgs.second))
        }
        runCatching {
            map.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 50), 400)
        }.onFailure {
            runCatching { map.animateCamera(CameraUpdateFactory.newLatLng(builder.build().center)) }
        }
    }

    override val cameraTargetLat: Double?
        get() {
            val target = map.cameraPosition.target ?: return null
            return wgs84ToGcj02(target.latitude, target.longitude).first
        }

    override val cameraTargetLng: Double?
        get() {
            val target = map.cameraPosition.target ?: return null
            return wgs84ToGcj02(target.latitude, target.longitude).second
        }

    override fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit) {
        cameraFinishListener = onFinish
    }

    override fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit) {
        cameraMoveListener = onMove
    }

    override fun disableUiControls() {
        runCatching {
            map.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                // 保留 attribution / logo：OSM 使用条款要求署名可见
                setAllGesturesEnabled(true)
            }
        }
    }

    // ---------------- 样式 ----------------

    override fun setMapType(type: AppMapType) {
        if (type == currentMapType && styleApplied) return
        currentMapType = type
        styleApplied = true
        applyStyle()
    }

    override fun setDarkMode(isDark: Boolean, context: Context) {
        if (isDarkMode == isDark && styleApplied) {
            applyDarkPaint()
            return
        }
        isDarkMode = isDark
        if (currentMapType == AppMapType.NORMAL) {
            // 矢量底图有独立的深色样式，直接换样式（rebuildOverlays 会重建标注）
            applyStyle()
        } else {
            // 卫星图是栅格，不换样式，只压暗 png
            applyDarkPaint()
        }
    }

    /** 按当前地图类型 + 明暗主题应用样式。setStyle 是异步的，叠加物在回调里重建。 */
    private fun applyStyle() {
        val custom = customTileUrl()
        val builder = when {
            currentMapType == AppMapType.SATELLITE ->
                Style.Builder().fromJson(buildSatelliteStyleJson())

            // 用户自定义：以 .json 结尾视为样式地址，其余视为栅格瓦片模板
            custom.endsWith(".json", ignoreCase = true) ->
                Style.Builder().fromUri(custom)

            custom.isNotEmpty() ->
                Style.Builder().fromJson(buildRasterStyleJson(custom))

            else ->
                Style.Builder().fromUri(if (isDarkMode) VECTOR_DARK_STYLE_URL else VECTOR_LIGHT_STYLE_URL)
        }
        map.setStyle(builder) { style ->
            rebuildOverlays(style)
        }
    }

    /** 读取用户自定义瓦片地址；留空表示使用内置 OpenFreeMap 矢量样式。 */
    private fun customTileUrl(): String = context
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getString("custom_tile_url", "")
        ?.trim()
        .orEmpty()

    /** 深色：只改 raster paint 属性，不动 style，因此标注不会丢失。 */
    private fun applyDarkPaint() {
        val style = map.style ?: return
        val layer = (runCatching { style.getLayer(CUSTOM_LAYER_ID) }.getOrNull()
            ?: runCatching { style.getLayer(RASTER_LAYER_ID) }.getOrNull()) as? RasterLayer
            ?: return
        runCatching {
            if (isDarkMode) {
                layer.setProperties(
                    PropertyFactory.rasterBrightnessMax(0.45f),
                    PropertyFactory.rasterBrightnessMin(0.0f),
                    PropertyFactory.rasterSaturation(-0.6f),
                    PropertyFactory.rasterContrast(0.15f)
                )
            } else {
                layer.setProperties(
                    PropertyFactory.rasterBrightnessMax(1.0f),
                    PropertyFactory.rasterBrightnessMin(0.0f),
                    PropertyFactory.rasterSaturation(0f),
                    PropertyFactory.rasterContrast(0f)
                )
            }
        }
    }

    /** setStyle 之后，把之前的叠加物与标注全部画回来。 */
    private fun rebuildOverlays(style: Style) {
        overlayIds.toList().forEach { id ->
            runCatching { style.removeLayer(layerIdOf(id)) }
            runCatching { style.removeSource(id) }
        }
        overlayIds.clear()
        idSeed = 0L

        // 这里不能复用 addPolyline/addCircle，否则会把快照再追加一遍
        polylines.forEach { drawPolyline(it) }
        circles.forEach { drawCircle(it) }

        markerList.forEach { runCatching { it.remove() } }
        markerList.clear()
        markerSpecs.forEach { markerList.add(drawMarker(it)) }

        applyDarkPaint()
    }

    companion object {
        private const val SATELLITE_SOURCE_ID = "satellite-raster-source"
        private const val RASTER_LAYER_ID = "satellite-raster-layer"
        private const val CUSTOM_LAYER_ID = "custom-raster-layer"
        private const val CUSTOM_ATTRIBUTION = "Tiles © their respective providers"

        // 普通底图：OpenFreeMap 矢量样式。免 Key、免注册、无调用配额，由 Cloudflare 承载。
        // 实测（2026-10）：style JSON 与 .pbf 瓦片在国内网络均可直连。
        // 可选样式：liberty / bright / positron / dark。
        private const val VECTOR_LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val VECTOR_DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

        // 卫星影像：Esri World Imagery（栅格，按其条款需署名）
        private const val SATELLITE_TILES =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        private const val SATELLITE_ATTRIBUTION =
            "Tiles &copy; <a href=\"https://www.esri.com/\">Esri</a>"

        /** 自定义栅格瓦片源的 style JSON。 */
        private fun buildRasterStyleJson(tilesUrl: String): String = """
            {
              "version": 8,
              "sources": {
                "custom-raster-source": {
                  "type": "raster",
                  "tiles": ["$tilesUrl"],
                  "tileSize": 256,
                  "maxzoom": 22,
                  "attribution": "$CUSTOM_ATTRIBUTION"
                }
              },
              "layers": [
                {
                  "id": "$CUSTOM_LAYER_ID",
                  "type": "raster",
                  "source": "custom-raster-source"
                }
              ]
            }
        """.trimIndent()

        private fun buildSatelliteStyleJson(): String = """
            {
              "version": 8,
              "sources": {
                "$SATELLITE_SOURCE_ID": {
                  "type": "raster",
                  "tiles": ["$SATELLITE_TILES"],
                  "tileSize": 256,
                  "maxzoom": 19,
                  "attribution": "$SATELLITE_ATTRIBUTION"
                }
              },
              "layers": [
                {
                  "id": "$RASTER_LAYER_ID",
                  "type": "raster",
                  "source": "$SATELLITE_SOURCE_ID"
                }
              ]
            }
        """.trimIndent()
    }
}

/** MapLibre 需要在创建 MapView 之前完成一次全局初始化（无需 token）。 */
private fun ensureMapLibreInitialized(context: Context) {
    runCatching { org.maplibre.android.MapLibre.getInstance(context.applicationContext) }
}

@Composable
fun OsmMapViewContainer(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onMapReady: (AppMapController) -> Unit,
    onControllerCreated: (AppMapController) -> Unit
) {
    val mapView = remember {
        ensureMapLibreInitialized(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onStart()
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    val controller = MapLibreControllerImpl(map, context)
                    controller.setMapType(AppMapType.NORMAL)
                    controller.setDarkMode(isDark, context)
                    onControllerCreated(controller)
                    onMapReady(controller)
                }
            }
        },
        modifier = modifier
    )
}