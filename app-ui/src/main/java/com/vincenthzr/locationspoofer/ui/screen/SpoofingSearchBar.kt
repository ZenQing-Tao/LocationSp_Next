package com.vincenthzr.locationspoofer.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiSearch
import androidx.compose.ui.res.stringResource
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.GithubRelease
import com.vincenthzr.locationspoofer.data.model.SavedLocation
import com.vincenthzr.locationspoofer.data.model.WifiLoadStatus
import com.vincenthzr.locationspoofer.ui.components.AppMapView
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.data.model.AppMapType
import com.vincenthzr.locationspoofer.ui.components.MapTypeDialog
import com.vincenthzr.locationspoofer.ui.components.map.wgs84ToGcj02
import androidx.compose.material.icons.rounded.Layers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.ui.BuildConfig
import androidx.compose.runtime.Composable
import com.amap.api.maps.AMapException
import com.amap.api.services.poisearch.PoiResult
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiDetailResult
import com.baidu.mapapi.search.poi.PoiDetailSearchResult
import com.baidu.mapapi.search.poi.PoiIndoorResult
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.ui.theme.*

@Composable
fun HomeSearchBar(
    query: String,
    searchMode: com.vincenthzr.locationspoofer.data.model.SearchMode,
    onSearchModeChange: (com.vincenthzr.locationspoofer.data.model.SearchMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.1f)
            )
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(26.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 20.dp, end = 8.dp)
                    .then(
                        focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                    )
                    .onFocusChanged { if (it.isFocused) onFocus() },
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_place_building_coord_hint),
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    fontWeight = FontWeight.Normal
                                )
                            }
                            innerTextField()
                        }
                    }
                }
            )

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentBlue)
                    .clickable { onSearch() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Search,
                    stringResource(R.string.search),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private var cachedPlacesClient: com.google.android.libraries.places.api.net.PlacesClient? = null

// ---- OSM / Nominatim 搜索 ----
// Nominatim 使用政策要求：必须带可识别的 User-Agent，且调用频率 ≤ 1 req/s。
// 这里用进程内时间戳做节流，宁可稍等也不要打爆公共实例。

private const val NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search"
private const val PHOTON_ENDPOINT = "https://photon.komoot.io/api/"
private const val NOMINATIM_USER_AGENT =
    "LocationSp_Next/3.1 (Android; +https://github.com/ZenQing-Tao/LocationSp_Next)"
private const val NOMINATIM_MIN_INTERVAL_MS = 1100L

/** 普通用户每日免费搜索次数上限（仅限内置 OSM 服务；自备 Key 的高德 / 百度 / Google 不受此限制）。 */
private const val DAILY_SEARCH_LIMIT = 8

// 搜索结果本地缓存：仅存关键词与坐标，7 天后自动失效
private const val SEARCH_CACHE_PREFS = "osm_search_cache"
private const val SEARCH_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
private const val SEARCH_CACHE_MAX = 200

private val nominatimThrottleLock = Any()
private var lastNominatimRequestAt: Long = 0L

private val nominatimClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/**
 * 今日剩余免费搜索次数检查：允许时顺便计数。
 */
private fun consumeDailyQuota(context: Context): Boolean {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val key = "search_quota_$day"
    val used = prefs.getInt(key, 0)
    if (used >= DAILY_SEARCH_LIMIT) return false
    prefs.edit().putInt(key, used + 1).apply()
    return true
}

private fun readSearchCache(context: Context, q: String): List<AppPoiItem> {
    val prefs = context.getSharedPreferences(SEARCH_CACHE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(q.lowercase(java.util.Locale.ROOT), null) ?: return emptyList()
    return runCatching {
        val obj = org.json.JSONObject(raw)
        if (System.currentTimeMillis() - obj.optLong("ts") > SEARCH_CACHE_TTL_MS) return emptyList()
        val arr = obj.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<AppPoiItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(AppPoiItem(o.optString("t"), o.optString("s"), o.optDouble("lat"), o.optDouble("lng")))
        }
        out
    }.getOrDefault(emptyList())
}

private fun writeSearchCache(context: Context, q: String, items: List<AppPoiItem>) {
    if (items.isEmpty()) return
    val prefs = context.getSharedPreferences(SEARCH_CACHE_PREFS, Context.MODE_PRIVATE)
    if (prefs.all.size >= SEARCH_CACHE_MAX) prefs.edit().clear().apply()
    val arr = org.json.JSONArray()
    items.forEach { item ->
        arr.put(org.json.JSONObject().apply {
            put("t", item.title)
            put("s", item.snippet)
            put("lat", item.lat)
            put("lng", item.lng)
        })
    }
    val obj = org.json.JSONObject().apply {
        put("ts", System.currentTimeMillis())
        put("items", arr)
    }
    prefs.edit().putString(q.lowercase(java.util.Locale.ROOT), obj.toString()).apply()
}

/**
 * OSM 地点搜索：本地缓存 → 每日限额 → Photon（国内可直连）→ Nominatim（兜底）。
 * 若用户在设置里填了「自定义搜索地址」，则只用该地址（不占用免费限额）。
 */
private fun searchNominatim(
    context: Context,
    keyword: String,
    onResult: (List<AppPoiItem>) -> Unit
) {
    val q = keyword.trim()
    if (q.isEmpty()) {
        onResult(emptyList())
        return
    }

    // 1) 本地缓存命中：不计次数、不发请求
    val cached = readSearchCache(context, q)
    if (cached.isNotEmpty()) {
        onResult(cached)
        return
    }

    // 2) 自定义搜索地址：用户自己的服务，不占免费限额
    val custom = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getString("custom_search_url", "")?.trim().orEmpty()
    val customMode = custom.isNotEmpty()

    // 3) 免费限额（仅内置服务）
    if (!customMode && !consumeDailyQuota(context)) {
        context.mainExecutor.execute {
            Toast.makeText(
                context,
                context.getString(R.string.osm_search_daily_limit, DAILY_SEARCH_LIMIT),
                Toast.LENGTH_LONG
            ).show()
        }
        onResult(emptyList())
        return
    }

    // 节流：两个公共实例都不要高频调用
    val waitMs = synchronized(nominatimThrottleLock) {
        val elapsed = System.currentTimeMillis() - lastNominatimRequestAt
        if (lastNominatimRequestAt != 0L && elapsed < NOMINATIM_MIN_INTERVAL_MS) {
            NOMINATIM_MIN_INTERVAL_MS - elapsed
        } else {
            0L
        }
    }

    Thread {
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
            }
        }
        synchronized(nominatimThrottleLock) {
            lastNominatimRequestAt = System.currentTimeMillis()
        }

        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        var lastError = ""

        fun tryEndpoint(url: String, parser: (String, String) -> List<AppPoiItem>): Boolean {
            return try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", NOMINATIM_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                nominatimClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = if (response.code == 429) "RATE_LIMITED" else "HTTP_${response.code}"
                        return false
                    }
                    val items = parser(response.body?.string().orEmpty(), q)
                    if (items.isEmpty()) {
                        lastError = "NO_RESULT"
                        return false
                    }
                    writeSearchCache(context, q, items)
                    onResult(items)
                    true
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                false
            }
        }

        val ok: Boolean = if (customMode) {
            tryEndpoint("$custom?q=$enc&format=jsonv2&limit=10", ::parseNominatimResults)
        } else {
            tryEndpoint("$PHOTON_ENDPOINT?q=$enc&limit=10", ::parsePhotonResults) ||
                tryEndpoint("$NOMINATIM_ENDPOINT?q=$enc&format=jsonv2&limit=10&addressdetails=0", ::parseNominatimResults)
        }

        if (!ok) {
            val text = when {
                lastError == "RATE_LIMITED" -> context.getString(R.string.osm_search_rate_limited)
                lastError == "NO_RESULT" -> context.getString(R.string.osm_search_failed_format, lastError)
                lastError.contains("timeout", true) ||
                    lastError.contains("connect", true) ||
                    lastError.contains("Failed", true) ||
                    lastError.contains("ENET", true) ->
                    context.getString(R.string.osm_search_offline_hint)
                else -> context.getString(R.string.osm_search_failed_format, lastError)
            }
            context.mainExecutor.execute {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
            onResult(emptyList())
        }
    }.start()
}

/** 解析 Photon 返回的 GeoJSON FeatureCollection。 */
private fun parsePhotonResults(body: String, keyword: String): List<AppPoiItem> {
    val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return emptyList()
    val features = root.optJSONArray("features") ?: return emptyList()
    val items = ArrayList<AppPoiItem>(features.length())
    for (i in 0 until features.length()) {
        val feature = features.optJSONObject(i) ?: continue
        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
        val lon = coords.optDouble(0, Double.NaN)
        val lat = coords.optDouble(1, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) continue
        val props = feature.optJSONObject("properties")
        val name = props?.optString("name").orEmpty().ifBlank { keyword }
        val area = listOfNotNull(
            props?.optString("district")?.takeIf { it.isNotBlank() },
            props?.optString("city")?.takeIf { it.isNotBlank() },
            props?.optString("country")?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        val gcj = wgs84ToGcj02(lat, lon)
        items.add(AppPoiItem(name, area, gcj.first, gcj.second))
    }
    return items
}

/** 解析 Nominatim 返回的 JSON 数组。 */
private fun parseNominatimResults(body: String, keyword: String): List<AppPoiItem> {
    val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return emptyList()
    val items = ArrayList<AppPoiItem>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val lat = obj.optString("lat").toDoubleOrNull() ?: continue
        val lon = obj.optString("lon").toDoubleOrNull() ?: continue
        val gcj = wgs84ToGcj02(lat, lon)
        items.add(
            AppPoiItem(
                title = obj.optString("display_name").substringBefore(",").ifBlank { keyword },
                snippet = obj.optString("display_name"),
                lat = gcj.first,
                lng = gcj.second
            )
        )
    }
    return items
}

fun performPoiSearch(
    context: Context,
    mapEngine: MapEngine,
    keyword: String,
    isDomestic: Boolean = true,
    onResult: (List<AppPoiItem>) -> Unit
) {
    if (mapEngine == MapEngine.OSM) {
        searchNominatim(context, keyword, onResult)
        return
    }
    if (mapEngine == MapEngine.BAIDU) {
        try {
            val mPoiSearch = com.baidu.mapapi.search.poi.PoiSearch.newInstance()
            mPoiSearch.setOnGetPoiSearchResultListener(object :
                OnGetPoiSearchResultListener {
                override fun onGetPoiResult(result: com.baidu.mapapi.search.poi.PoiResult?) {
                    if (result == null || result.error != com.baidu.mapapi.search.core.SearchResult.ERRORNO.NO_ERROR) {
                        onResult(emptyList())
                        mPoiSearch.destroy()
                        return
                    }
                    val items = result.allPoi?.map {
                        AppPoiItem(
                            it.name ?: "",
                            it.address ?: "",
                            it.location.latitude,
                            it.location.longitude
                        )
                    } ?: emptyList()
                    onResult(items)
                    mPoiSearch.destroy()
                }

                override fun onGetPoiDetailResult(p0: PoiDetailResult?) {}
                override fun onGetPoiDetailResult(p0: PoiDetailSearchResult?) {}
                override fun onGetPoiIndoorResult(p0: PoiIndoorResult?) {}
            })
            val option = PoiCitySearchOption()
                .city("全国")
                .keyword(keyword)
                .pageNum(0)
                .pageCapacity(20)
            mPoiSearch.searchInCity(option)
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(emptyList())
        }
    } else if (mapEngine == MapEngine.GOOGLE) {
        try {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val key = prefs.getString("google_api_key", "")
            if (key.isNullOrBlank()) {
                Toast.makeText(context, context.getString(R.string.google_key_required_hint), Toast.LENGTH_LONG).show()
                onResult(emptyList())
                return
            }
            if (!com.google.android.libraries.places.api.Places.isInitialized()) {
                com.google.android.libraries.places.api.Places.initialize(context.applicationContext, key)
            }
            val placesClient = cachedPlacesClient ?: com.google.android.libraries.places.api.Places.createClient(context.applicationContext).also { cachedPlacesClient = it }
            val sessionToken = com.google.android.libraries.places.api.model.AutocompleteSessionToken.newInstance()
            val worldBounds = com.google.android.libraries.places.api.model.RectangularBounds.newInstance(
                com.google.android.gms.maps.model.LatLng(-90.0, -180.0),
                com.google.android.gms.maps.model.LatLng(90.0, 180.0)
            )
            val autocompleteRequest = com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest.builder()
                .setQuery(keyword)
                .setLocationBias(worldBounds)
                .setSessionToken(sessionToken)
                .build()

            placesClient.findAutocompletePredictions(autocompleteRequest)
                .addOnSuccessListener { autocompleteResponse ->
                    val predictions = autocompleteResponse.autocompletePredictions
                    if (predictions.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.no_search_results), Toast.LENGTH_SHORT).show()
                        onResult(emptyList())
                        return@addOnSuccessListener
                    }
                    val fetchFields = listOf(
                        com.google.android.libraries.places.api.model.Place.Field.ID,
                        com.google.android.libraries.places.api.model.Place.Field.NAME,
                        com.google.android.libraries.places.api.model.Place.Field.LAT_LNG,
                        com.google.android.libraries.places.api.model.Place.Field.ADDRESS
                    )
                    val resultList = mutableListOf<AppPoiItem>()
                    val topPredictions = predictions.take(5)
                    var completedCount = 0
                    topPredictions.forEach { prediction ->
                        val fetchRequest = com.google.android.libraries.places.api.net.FetchPlaceRequest.newInstance(prediction.placeId, fetchFields)
                        placesClient.fetchPlace(fetchRequest)
                            .addOnSuccessListener { fetchResponse ->
                                val place = fetchResponse.place
                                val latLng = place.latLng
                                if (latLng != null) {
                                    resultList.add(
                                        AppPoiItem(
                                            title = place.name ?: prediction.getPrimaryText(null).toString(),
                                            snippet = place.address ?: prediction.getSecondaryText(null).toString(),
                                            lat = latLng.latitude,
                                            lng = latLng.longitude
                                        )
                                    )
                                }
                            }
                            .addOnCompleteListener {
                                completedCount++
                                if (completedCount == topPredictions.size) {
                                    onResult(resultList)
                                }
                            }
                    }
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(context, "Google Search Error: ${exception.message}", Toast.LENGTH_LONG).show()
                    onResult(emptyList())
                }
        } catch (e: Exception) {
            Toast.makeText(context, "Google Search Catch Error: ${e.message}", Toast.LENGTH_LONG).show()
            onResult(emptyList())
        }
    } else {
        try {
            val query = PoiSearch.Query(keyword, "", "")
            query.pageSize = 10
            query.pageNum = 0
            val search = PoiSearch(context, query)
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(
                    result: PoiResult?,
                    rCode: Int
                ) {
                    if (rCode == 1000 && result != null) {
                        onResult(result.pois?.map {
                            AppPoiItem(
                                it.title ?: "",
                                it.snippet ?: "",
                                it.latLonPoint.latitude,
                                it.latLonPoint.longitude
                            )
                        } ?: emptyList())
                    } else {
                        if (rCode == 10003 || rCode == 10012 || rCode == 10013 || rCode == 10014 || rCode == 1800 || rCode == 18000) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.amap_search_failed_quota),
                                Toast.LENGTH_LONG
                            ).show()
                        } else if (rCode != 1000) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.amap_search_failed_code_format, rCode),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        onResult(emptyList())
                    }
                }

                override fun onPoiItemSearched(item: PoiItem?, rCode: Int) {}
            })
            search.searchPOIAsyn()
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.message ?: ""
            if (e is AMapException || msg.contains(
                    "limit",
                    ignoreCase = true
                ) || msg.contains("额度")
            ) {
                Toast.makeText(
                    context,
                    context.getString(R.string.amap_search_exception_format, msg),
                    Toast.LENGTH_LONG
                ).show()
            }
            onResult(emptyList())
        }
    }
}
