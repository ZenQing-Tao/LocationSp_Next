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
import org.maplibre.android.MapView
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.MapStyleOptions
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
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
 * 因此进入 SDK 前 gcj02 → WGS-84，cameraTarget* 返回时 WGS-84 → GCJ-02。
 *
 * 深色模式不改 style（setStyle 会清空全部图层与标注），
 * 而是改写 raster 图层的 paint 属性实现变暗。
 *
 * 地图类型（NORMAL / SATELLITE）切换确实需要换数据源，
 * 因此这里记录下所有叠加物，setStyle 之后重新绘制，避免标注丢失。
 */
class MapLibreControllerImpl(
    private val map: MapLibreMap,
    private val context: Context
) : AppMapController {

    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    // MapLibre 没有全局 clear()，自行持有图层 id 与 marker 以便逐个移除。
    private val overlayLayerIds = mutableSetOf<String>()
    private val markerList = mutableListOf<Marker>()
    private var idSeed = 0L

    /** 已绘制的叠加物快照，用于 setStyle 之后原样重建。 */
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
            val t = map.cameraPosition.target
            val gcj = wgs84ToGcj02(t.latitude, t.longitude)
            cameraMoveListener?.invoke(gcj.first, gcj.second)
        }
        map.addOnCameraIdleListener {
            val t = map.cameraPosition.target
            val gcj = wgs84ToGcj02(t.latitude, t.longitude)
            cameraFinishListener?.invoke(gcj.first, gcj.second)
        }
    }

    // ---------------- 叠加物 ----------------

    override fun clear() {
        markerList.forEach { runCatching { it.remove() } }
        markerList.clear()
        markerSpecs.clear()
        overlayLayerIds.forEach { removeOverlayLayer(it) }
        overlayLayerIds.clear()
        polylines.clear()
        circles.clear()
    }

    override fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        polylines.add(PolylineSpec(points, colorInt, width))
        drawPolyline(points, colorInt, width)
    }

    private fun drawPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        if (points.size < 2) return
        val id = nextId("line")
        val coords = points.map { p ->
            val wgs = gcj02ToWgs84(p.first, p.second)
            listOf(wgs.first, wgs.second)
        }
        map.style.addSource(
            id,
            GeoJsonSource(
                id,
                FeatureCollection.fromFeatures(
                    listOf(Feature.fromGeometry(LineString.fromLngLats(coords)))
                )
            )
        )
        map.style.addLayer(
            LineLayer(id, id).withProperties(
                Property.lineColor(colorInt),
                Property.lineWidth(width),
                Property.lineCap(Property.LINE_CAP_ROUND),
                Property.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        overlayLayerIds.add(id)
    }

    override fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    ) {
        circles.add(CircleSpec(lat, lng, radius, fillColorInt, strokeColorInt, strokeWidth))
        drawCircle(CircleSpec(lat, lng, radius, fillColorInt, strokeColorInt, strokeWidth))
    }

    private fun drawCircle(spec: CircleSpec) {
        val id = nextId("circle")
        val wgs = gcj02ToWgs84(spec.lat, spec.lng)
        // 环需要闭合（首尾点相同），因此最后显式补回首点
        val ring = circleRing(wgs.first, wgs.second, spec.radius)
            .map { Point.fromLngLat(it[0], it[1]) }
        map.style.addSource(
            id,
            GeoJsonSource(
                id,
                FeatureCollection.fromFeatures(
                    listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
                )
            )
        )
        map.style.addLayer(
            FillLayer(id, id).withProperties(
                Property.fillColor(spec.fill),
                Property.fillOutlineColor(spec.stroke)
            )
        )
        overlayLayerIds.add(id)
    }

    /** 以 (lat,lng) 为中心生成近似圆形多边形顶点（WGS-84，radiusMeters 为半径）。 */
    private fun circleRing(centerLat: Double, centerLng: Double, radiusMeters: Double): List<List<Double>> {
        val segments = 64
        val metersPerDegLat = 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(centerLat))
        // 低纬度处经度跨度会非常大，做下限保护避免除零/数值爆炸
        val safeCos = if (kotlin.math.abs(cosLat) < 0.01) 0.01 else cosLat
        val metersPerDegLng = metersPerDegLat * safeCos

        val ring = ArrayList<List<Double>>(segments + 1)
        for (i in 0..segments) {
            val angle = 2.0 * Math.PI * i / segments
            val lat = centerLat + (radiusMeters / metersPerDegLat) * kotlin.math.cos(angle)
            val lng = centerLng + (radiusMeters / metersPerDegLng) * kotlin.math.sin(angle)
            ring.add(listOf(lng, lat))
        }
        // 显式闭合，确保首尾点一致（i == segments 时已回到起点，这里再补一次保险）
        ring.add(ring.first())
        return ring
    }

    override fun addMarker(lat: Double, lng: Double, title: String, type: MarkerType): AppMapMarker {
        val spec = MarkerSpec(lat, lng, title, type)
        markerSpecs.add(spec)
        val marker = drawMarker(spec)
        markerList.add(marker)
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                val p = gcj02ToWgs84(lat, lng)
                marker.position = LatLng(p.first, p.second)
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
                .anchor(0.5f, 1.0f)
                .title(spec.title)
        )
    }

    private fun removeOverlayLayer(id: String) {
        val style = map.style ?: return
        runCatching { style.removeLayer(id) }
        runCatching { style.removeSource(id) }
    }

    private fun nextId(prefix: String): String = "$prefix-${++idSeed}"

    // ---------------- 相机 ----------------

    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        val wgs = gcj02ToWgs84(lat, lng)
        if (zoom != null) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(wgs.first, wgs.second), zoom)
            )
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(wgs.first, wgs.second)))
        }
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float?) {
        val wgs = gcj02ToWgs84(lat, lng)
        if (zoom != null) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(wgs.first, wgs.second), zoom)
            )
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(wgs.first, wgs.second)))
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
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 0), 400)
        }.onFailure {
            runCatching {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        builder.build(),
                        maxOf(paddingLeft, paddingRight, paddingTop, paddingBottom)
                    )
                )
            }.onFailure { it.printStackTrace() }
        }
    }

    override val cameraTargetLat: Double?
        get() {
            val t = map.cameraPosition.target
            return wgs84ToGcj02(t.latitude, t.longitude).first
        }

    override val cameraTargetLng: Double?
        get() {
            val t = map.cameraPosition.target
            return wgs84ToGcj02(t.latitude, t.longitude).second
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
                // 保留 attribution（OSM 使用条款要求署名可见）
                isLogoEnabled = false
                setAllGesturesEnabled(true)
            }
        }
    }

    // ---------------- 样式 ----------------

    override fun setMapType(type: AppMapType) {
        // 已经是目标类型且样式已就绪则无需重建（setStyle 会清空所有图层与标注）
        if (type == currentMapType && map.style?.isFullyLoaded == true) return
        currentMapType = type
        val satellite = type == AppMapType.SATELLITE
        runCatching {
            map.setStyle(MapStyleOptions(buildStyleJson(satellite)))
            map.style.addLayer(
                RasterLayer(RASTER_LAYER_ID, if (satellite) SATELLITE_SOURCE_ID else OSM_SOURCE_ID)
            )
            // setStyle 清空了一切，重绘所有叠加物与标注
            redrawAll()
            applyDarkPaint()
        }.onFailure { it.printStackTrace() }
    }

    override fun setDarkMode(isDark: Boolean, context: Context) {
        isDarkMode = isDark
        applyDarkPaint()
    }

    /** 深色：只改 raster paint 属性，不动 style，因此标注不会丢失。 */
    private fun applyDarkPaint() {
        val layer = runCatching { map.style?.getLayer(RASTER_LAYER_ID) as? RasterLayer }.getOrNull() ?: return
        runCatching {
            if (isDarkMode) {
                layer.setProperties(
                    Property.rasterBrightnessMax(0.45f),
                    Property.rasterBrightnessMin(0.0f),
                    Property.rasterSaturation(-0.6f),
                    Property.rasterContrast(0.15f)
                )
            } else {
                layer.setProperties(
                    Property.rasterBrightnessMax(1.0f),
                    Property.rasterBrightnessMin(0.0f),
                    Property.rasterSaturation(0f),
                    Property.rasterContrast(0f)
                )
            }
        }
    }

    /** setStyle 之后把之前的叠加物与标注全部画回来。 */
    private fun redrawAll() {
        overlayLayerIds.forEach { removeOverlayLayer(it) }
        overlayLayerIds.clear()
        idSeed = 0L

        polylines.forEach { drawPolyline(it.points, it.color, it.width) }
        circles.forEach { drawCircle(it) }

        markerList.forEach { runCatching { it.remove() } }
        markerList.clear()
        markerSpecs.forEach { markerList.add(drawMarker(it)) }
    }

    companion object {
        private const val OSM_SOURCE_ID = "osm-raster-source"
        private const val SATELLITE_SOURCE_ID = "satellite-raster-source"
        private const val RASTER_LAYER_ID = "osm-raster-layer"

        private fun buildStyleJson(satellite: Boolean): String {
            val sourceId = if (satellite) SATELLITE_SOURCE_ID else OSM_SOURCE_ID
            val tiles = if (satellite) SATELLITE_TILES else OSM_TILES
            val attribution = if (satellite) SATELLITE_ATTRIBUTION else OSM_ATTRIBUTION
            return """
                {
                  "version": 8,
                  "sources": {
                    "$sourceId": {
                      "type": "raster",
                      "tiles": ["$tiles"],
                      "tileSize": 256,
                      "maxzoom": 19,
                      "attribution": "$attribution"
                    }
                  },
                  "layers": [
                    {
                      "id": "$RASTER_LAYER_ID",
                      "type": "raster",
                      "source": "$sourceId"
                    }
                  ]
                }
            """.trimIndent()
        }

        // OpenStreetMap 官方公共瓦片
        private const val OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        private const val OSM_ATTRIBUTION = "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"

        // Esri World Imagery 卫星影像（按其条款需署名）
        private const val SATELLITE_TILES =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        private const val SATELLITE_ATTRIBUTION =
            "Tiles &copy; <a href=\"https://www.esri.com/\">Esri</a>"
    }
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
                    runCatching { map.uiSettings.isAttributionEnabled = true }
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