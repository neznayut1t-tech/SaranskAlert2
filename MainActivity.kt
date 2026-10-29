package com.saransk.alert

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
    private lateinit var tvNearestShelter: TextView
    private lateinit var btnStopAlarm: Button
    private lateinit var btnSettings: Button

    private var userMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private val httpClient = OkHttpClient()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentLat = 54.1838
    private var currentLon = 45.1749

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        tvStatus = findViewById(R.id.tvStatus)
        tvNearestShelter = findViewById(R.id.tvNearestShelter)
        btnStopAlarm = findViewById(R.id.btnStopAlarm)
        btnSettings = findViewById(R.id.btnSettings)

        setupMap()
        setupButtons()
        requestRequiredPermissions()

        val serviceIntent = Intent(this, MaxChannelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        updateAlarmUi(AlarmController.isAlarmPlaying)
        AlarmController.onAlarmStateChanged = { playing ->
            runOnUiThread { updateAlarmUi(playing) }
        }
    }

    private fun setupMap() {
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.5)
        mapView.controller.setCenter(GeoPoint(currentLat, currentLon))

        for (shelter in ShelterRepository.shelters) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(shelter.lat, shelter.lon)
                title = "СЭП №${shelter.id}: ${shelter.name}"
                snippet = "Адрес: г. Саранск, ${shelter.address}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }

        updateUserLocationAndRoute(currentLat, currentLon)
    }

    private fun setupButtons() {
        btnStopAlarm.setOnClickListener {
            AlarmController.stopAlarm()
            updateAlarmUi(false)
        }
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun updateAlarmUi(isPlaying: Boolean) {
        if (isPlaying) {
            btnStopAlarm.visibility = View.VISIBLE
            tvStatus.text = "ВНИМАНИЕ! В МОРДОВИИ РАКЕТНАЯ ОПАСНОСТЬ!"
            tvStatus.setTextColor(Color.parseColor("#FF5252"))
        } else {
            btnStopAlarm.visibility = View.GONE
            tvStatus.text = "Мониторинг max.ru/tyrbazaa активен"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        }
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val switchMuteAll = Switch(this).apply {
            text = "Отключить весь звук приложения"
            textSize = 16f
            isChecked = AlarmController.isSoundMutedGlobally(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                AlarmController.setSoundMutedGlobally(this@MainActivity, checked)
            }
        }

        val btnTestAlarm = Button(this).apply {
            text = "Проверить сирену и маршрут (Тест)"
            setOnClickListener {
                AlarmController.triggerAlarm(this@MainActivity)
                updateAlarmUi(true)
            }
        }

        val btnDndAccess = Button(this).apply {
            text = "Разрешить обход «Не беспокоить»"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }

        container.addView(switchMuteAll)
        container.addView(btnTestAlarm)
        container.addView(btnDndAccess)

        AlertDialog.Builder(this)
            .setTitle("Настройки оповещения")
            .setView(container)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun updateUserLocationAndRoute(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon

        val userPoint = GeoPoint(lat, lon)
        if (userMarker == null) {
            userMarker = Marker(mapView).apply {
                title = "Ваше местоположение"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(userMarker)
        }
        userMarker?.position = userPoint

        val (nearest, distMeters) = ShelterRepository.findNearest(lat, lon)
        val distRounded = distMeters.roundToInt()

        tvNearestShelter.text = "Ближайшее укрытие (СЭП №${nearest.id}):\n" +
                "${nearest.name} (${nearest.address}) — $distRounded м"

        drawRouteToShelter(userPoint, GeoPoint(nearest.lat, nearest.lon))
    }

    private fun drawRouteToShelter(start: GeoPoint, end: GeoPoint) {
        uiScope.launch {
            val routePoints = withContext(Dispatchers.IO) {
                fetchOsrmWalkingRoute(start, end)
            }

            routePolyline?.let { mapView.overlays.remove(it) }
            routePolyline = Polyline().apply {
                outlinePaint.color = Color.parseColor("#FF1744")
                outlinePaint.strokeWidth = 12f
                setPoints(routePoints)
            }
            mapView.overlays.add(routePolyline)
            mapView.invalidate()
        }
    }

    private fun fetchOsrmWalkingRoute(start: GeoPoint, end: GeoPoint): List<GeoPoint> {
        return try {
            val url = "https://router.project-osrm.org/route/v1/foot/" +
                    "${start.longitude},${start.latitude};${end.longitude},${end.latitude}" +
                    "?overview=full&geometries=geojson"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use listOf(start, end)
                val json = JSONObject(body)
                val coords = json.getJSONArray("routes")
                    .getJSONObject(0)
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates")

                val points = mutableListOf<GeoPoint>()
                for (i in 0 until coords.length()) {
                    val pt = coords.getJSONArray(i)
                    points.add(GeoPoint(pt.getDouble(1), pt.getDouble(0)))
                }
                points
            }
        } catch (_: Exception) {
            listOf(start, end)
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startLocationUpdates()
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            AlertDialog.Builder(this)
                .setTitle("Разрешение на работу в беззвучном режиме")
                .setMessage("Чтобы сирена сработала даже на беззвучном режиме и «Не беспокоить», предоставьте приложению доступ на следующем экране.")
                .setPositiveButton("Выдать доступ") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, this)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, this)
                val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastKnown?.let { onLocationChanged(it) }
            } catch (_: Exception) {}
        }
    }

    override fun onLocationChanged(location: Location) {
        updateUserLocationAndRoute(location.latitude, location.longitude)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
