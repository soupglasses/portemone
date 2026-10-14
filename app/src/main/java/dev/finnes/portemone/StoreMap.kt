// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.StoreCircleGeometry
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.delay
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.sources.GeoJsonSource

private object WalletMapRuntime {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        // Identify this app and honor cache headers; never prefetch or create offline tile packs.
        // https://operations.osmfoundation.org/policies/tiles/
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "map-http"), 32L * 1024 * 1024))
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", "Portemone/1.0 (Android; dev.finnes.portemone)")
                            .build()
                    )
                }.build()
        )
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        initialized = true
    }
}

/** Only store coordinates enter the map; membership values and live location never do. */
@Composable
fun StoreMap(
    stores: List<StoreLocation>,
    selectedId: String?,
    brandColor: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onSelect: (String) -> Unit = {},
    onPlace: ((Double, Double) -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    val resources = LocalResources.current
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(attempt) {
        delay(15_000)
        if (!loaded) failed = true
    }
    Box(modifier) {
        key(attempt) {
            MapSurface(
                stores, selectedId, brandColor, interactive, onSelect, onPlace,
                onLoaded = { loaded = true; failed = false },
                onFailure = { failed = true }, modifier = Modifier.fillMaxSize()
            )
        }
        if (!interactive && onOpen != null) {
            Box(
                Modifier.matchParentSize().clickable(
                    role = Role.Button,
                    onClickLabel = resources.getString(R.string.open_store_map), onClick = onOpen
                )
                    .semantics { contentDescription = resources.getString(R.string.open_store_map) })
        }
        if (!loaded && !failed) CircularProgressIndicator(
            Modifier.align(Alignment.Center).size(28.dp)
        )
        if (failed) Surface(
            Modifier.align(Alignment.Center).padding(24.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.map_unavailable), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.map_unavailable_detail),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    failed = false; loaded = false; attempt++
                }) { Text(stringResource(R.string.retry_map)) }
            }
        }
        // Keep the visible credit compact while its transparent touch area remains 48 dp.
        // It is above the preview action, so opening attribution never opens the map too.
        Box(
            Modifier.align(Alignment.BottomEnd).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = resources.getString(R.string.open_map_attribution)) {
                    uriHandler.openUri("https://www.openstreetmap.org/copyright")
                }, contentAlignment = Alignment.BottomEnd
        ) {
            Text(
                "© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .95f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MapSurface(
    stores: List<StoreLocation>, selectedId: String?, brandColor: Int, interactive: Boolean,
    onSelect: (String) -> Unit, onPlace: ((Double, Double) -> Unit)?,
    onLoaded: () -> Unit, onFailure: () -> Unit, modifier: Modifier,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestStores by rememberUpdatedState(stores)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPlace by rememberUpdatedState(onPlace)
    val latestLoaded by rememberUpdatedState(onLoaded)
    val latestFailure by rememberUpdatedState(onFailure)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val view = remember {
        WalletMapRuntime.initialize(context)
        MapView(
            context, MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true).attributionEnabled(false).logoEnabled(false)
                .compassEnabled(false)
        ).apply {
            onCreate(null)
            contentDescription =
                if (interactive) resources.getString(R.string.interactive_store_map) else resources.getString(R.string.saved_store_area_map)
            if (interactive) setOnTouchListener { touched, event ->
                touched.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
        }
    }
    DisposableEffect(view, lifecycle) {
        var disposed = false
        var started = false
        var resumed = false
        fun synchronize() {
            val shouldResume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val shouldStart = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!shouldResume && resumed) {
                view.onPause(); resumed = false
            }
            if (!shouldStart && started) {
                view.onStop(); started = false
            }
            if (shouldStart && !started) {
                view.onStart(); started = true
            }
            if (shouldResume && !resumed) {
                view.onResume()
                resumed = true
                // An unchanged map has no new frame queued when its TextureView
                // becomes visible again after unlock. Redraw without resetting the camera.
                view.post {
                    if (!disposed && resumed) map?.let {
                        it.triggerRepaint()
                        AppDiagnostics.event("Map", "Renderer resumed; redraw requested")
                    }
                }
            }
        }

        val observer = LifecycleEventObserver { _, _ -> synchronize() }
        lifecycle.addObserver(observer)
        synchronize()
        view.addOnDidFailLoadingMapListener { if (!disposed) latestFailure() }
        view.addOnDidFinishRenderingMapListener { fully ->
            if (!disposed && styleReady && fully) latestLoaded()
        }
        view.getMapAsync { ready ->
            if (!disposed) {
                map = ready
                ready.setPrefetchZoomDelta(0)
                ready.uiSettings.setAllGesturesEnabled(interactive)
                ready.uiSettings.isRotateGesturesEnabled = false
                ready.uiSettings.isTiltGesturesEnabled = false
                ready.addOnMapClickListener { point ->
                    if (!interactive) false else {
                        val place = latestPlace
                        if (place != null) place(point.latitude, point.longitude)
                        else latestStores.minByOrNull { store ->
                            LatLng(
                                store.latitude,
                                store.longitude
                            ).distanceTo(point) / store.radiusMeters
                        }?.takeIf {
                            LatLng(
                                it.latitude,
                                it.longitude
                            ).distanceTo(point) <= it.radiusMeters * 1.35
                        }
                            ?.let { latestSelect(it.id) }
                        true
                    }
                }
                ready.setStyle(Style.Builder().fromJson(mapStyle(brandColor))) {
                    if (!disposed) {
                        styleReady = true
                        it.getSourceAs<GeoJsonSource>("stores")
                            ?.setGeoJson(storeGeoJson(latestStores, latestSelected))
                    }
                }
            }
        }
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(stores, selectedId, styleReady) {
        if (styleReady) map?.style?.getSourceAs<GeoJsonSource>("stores")
            ?.setGeoJson(storeGeoJson(stores, selectedId))
    }
    val selected = stores.firstOrNull { it.id == selectedId }
    // Fit the full geodesic circle after the actual map viewport is measured. Changing a
    // radius or selecting a store refits it; placing a draft pin never fights a pan gesture.
    LaunchedEffect(
        selectedId, styleReady, viewport, selected?.radiusMeters, density,
        if (interactive) null else selected
    ) {
        if (styleReady && viewport.width > 0 && viewport.height > 0) selected?.let { store ->
            val side = (24 * density).roundToInt().coerceAtMost(viewport.width / 4)
            val top = ((if (interactive) 64 else 24) * density).roundToInt()
                .coerceAtMost(viewport.height / 3)
            val bottom = (40 * density).roundToInt().coerceAtMost(viewport.height / 3)
            val update = run {
                val bounds = LatLngBounds.Builder().includes(
                    StoreCircleGeometry.ring(
                        store.latitude,
                        store.longitude,
                        store.radiusMeters.toDouble()
                    )
                        .map { LatLng(it.first, it.second) }).build()
                CameraUpdateFactory.newLatLngBounds(bounds, side, top, side, bottom)
            }
            map?.animateCamera(update, 250)
        }
    }
    AndroidView(factory = { view }, modifier = modifier.onSizeChanged { viewport = it })
}

private fun mapStyle(brandColor: Int): String {
    val color = "#%06X".format(brandColor and 0xFFFFFF)
    return """{
      "version":8,"sources":{
        "osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"},
        "stores":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}
      },"layers":[
        {"id":"tiles","type":"raster","source":"osm"},
        {"id":"areas","type":"fill","source":"stores","filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":"$color","fill-opacity":["case",["get","selected"],0.2,0.08]}},
        {"id":"outlines","type":"line","source":"stores","filter":["==",["geometry-type"],"Polygon"],"paint":{"line-color":"$color","line-width":["case",["get","selected"],3,1.5]}},
        {"id":"centers","type":"circle","source":"stores","filter":["==",["geometry-type"],"Point"],"paint":{"circle-color":"$color","circle-radius":6,"circle-stroke-color":"#ffffff","circle-stroke-width":3}}
      ]}
    """.trimIndent()
}

private fun storeGeoJson(stores: List<StoreLocation>, selectedId: String?): String {
    val features = JSONArray()
    stores.forEach { store ->
        val properties = JSONObject().put("selected", store.id == selectedId)
        val ring = JSONArray()
        StoreCircleGeometry.ring(store.latitude, store.longitude, store.radiusMeters.toDouble())
            .forEach {
                ring.put(JSONArray().put(it.second).put(it.first))
            }
        features.put(
            JSONObject().put("type", "Feature").put("properties", properties)
                .put(
                    "geometry",
                    JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
                )
        )
        features.put(
            JSONObject().put("type", "Feature").put("properties", properties)
                .put(
                    "geometry", JSONObject().put("type", "Point")
                        .put("coordinates", JSONArray().put(store.longitude).put(store.latitude))
                )
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}
