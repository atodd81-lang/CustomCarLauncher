package com.custom.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var mediaHelper: MediaControlHelper
    private lateinit var locationManager: LocationManager
    lateinit var dashboardView: AgamaDashboardView
    private lateinit var circularMapWebView: WebView
    private var isMapLoaded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var iphoneWeatherServer: ServerSocket? = null
    @Volatile
    private var isServerRunning = false

    private var lastWeatherFetchTime = 0L
    var lastLat: Double? = null
    var lastLon: Double? = null
    private var previousGpsLocation: Location? = null

    var aclBluetoothConnected = false
    var carPlaySessionActive = false

    val recentBroadcastLog = LinkedList<String>()

    private val mediaRadioAndNavReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action.orEmpty()
            val bundle = intent.extras

            if (!action.contains("BATTERY") && !action.contains("TIME_TICK")) {
                val keysSummary = bundle?.keySet()?.joinToString(", ") { k ->
                    @Suppress("DEPRECATION")
                    "$k=${bundle.get(k)}"
                } ?: "no-extras"
                val entry = "[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $action ($keysSummary)"
                recentBroadcastLog.addFirst(entry)
                while (recentBroadcastLog.size > 20) recentBroadcastLog.removeLast()
            }

            // 0. Live Temperature Received Directly from iPhone (via Bluetooth Tag or Local Bridge)
            if (action == "com.custom.carlauncher.IPHONE_TEMP_RECEIVED") {
                val tempVal = intent.getIntExtra("temp_f", -999)
                if (tempVal != -999) {
                    dashboardView.updateLiveTemperature(tempVal)
                }
                return
            }

            // 1. Live Radio Station Text scraped from our RadioScreenReaderService
            if (action == "com.custom.carlauncher.RADIO_SCRAPED") {
                val station = intent.getStringExtra("station_text").orEmpty()
                if (station.isNotEmpty()) {
                    dashboardView.updateRadioInfo(station, true)
                }
                return
            }

            // 2. Live Media Notification Text scraped from our RadioScreenReaderService
            if (action == "com.custom.carlauncher.MEDIA_NOTIFICATION_SCRAPED") {
                val text = intent.getStringExtra("text").orEmpty()
                if (text.isNotEmpty()) {
                    val parts = text.split("—").map { it.trim() }
                    val tTitle = parts.getOrNull(0).orEmpty()
                    val tArtist = parts.getOrNull(1).orEmpty()
                    val tAlbum = parts.getOrNull(2).orEmpty()
                    dashboardView.updateMediaInfo(tTitle, tArtist, tAlbum, true)
                }
                return
            }

            // 3. CarPlay Active Signal
            if (action == "com.custom.carlauncher.CARPLAY_ACTIVE") {
                carPlaySessionActive = intent.getBooleanExtra("connected", true)
                updateCenterMapVisibility()
                dashboardView.invalidate()
                return
            }

            // 4. Live Bluetooth ACL Connect / Disconnect Tracking
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    aclBluetoothConnected = true
                    dashboardView.invalidate()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    aclBluetoothConnected = false
                    carPlaySessionActive = false
                    updateCenterMapVisibility()
                    dashboardView.invalidate()
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, 0)
                    aclBluetoothConnected = (state == BluetoothAdapter.STATE_CONNECTED)
                    if (!aclBluetoothConnected) {
                        carPlaySessionActive = false
                    }
                    updateCenterMapVisibility()
                    dashboardView.invalidate()
                }
            }

            // 5. Check for Zlink / Tlink / CarPlay Connection & Media Broadcasts
            if (action.contains("zlink", ignoreCase = true) ||
                action.contains("tlink", ignoreCase = true) ||
                action.contains("carplay", ignoreCase = true)
            ) {
                val statusStr = extractFirstString(bundle, "status", "state", "connect", "phoneMode")
                val disconnected = action.contains("disconnect", ignoreCase = true) ||
                        action.contains("exit", ignoreCase = true) ||
                        statusStr.equals("0", true) ||
                        statusStr.equals("false", true) ||
                        statusStr.contains("disconnect", true)
                carPlaySessionActive = !disconnected
                updateCenterMapVisibility()
                dashboardView.invalidate()
            }

            // 6. Check if broadcast is from the Head Unit's FM/AM Radio app or MCU
            val isRadioIntent = action.contains("radio", ignoreCase = true) ||
                    action.contains("fm", ignoreCase = true) ||
                    action.contains("mcu", ignoreCase = true) ||
                    action.contains("tw.", ignoreCase = true) ||
                    hasAnyKey(bundle, "freq", "frequency", "radio_freq", "cur_freq", "channel_freq", "fm_freq", "rds", "ps", "pty")

            if (isRadioIntent) {
                if (action.contains("stop", ignoreCase = true) ||
                    action.contains("close", ignoreCase = true) ||
                    action.contains("exit", ignoreCase = true) ||
                    !intent.getBooleanExtra("playing", true) ||
                    intent.getIntExtra("state", 1) == 0
                ) {
                    dashboardView.setRadioInactive()
                    return
                }

                val rawFreq = extractFirstString(
                    bundle,
                    "freq", "frequency", "radio_freq", "cur_freq", "channel_freq", "channel", "fm_freq", "mFreq", "value"
                )
                val band = extractFirstString(bundle, "band", "radio_band", "wave", "type_band", "mBand")
                    .ifEmpty { "FM" }
                val callLetters = extractFirstString(
                    bundle,
                    "ps", "rds_ps", "call", "station", "radio_name", "station_name", "channel_name", "name", "ps_name"
                )
                val stationType = extractFirstString(
                    bundle,
                    "pty", "rds_pty", "genre", "program_type", "station_type", "tp", "pty_name"
                )
                val radioText = extractFirstString(
                    bundle,
                    "rt", "rds_rt", "rds", "radio_text", "infor", "info", "text", "title"
                )

                val formattedFreq = formatRadioFrequency(rawFreq, band)

                val parts = mutableListOf<String>()
                if (formattedFreq.isNotEmpty()) parts.add(formattedFreq)
                if (callLetters.isNotEmpty() && !callLetters.equals(formattedFreq, ignoreCase = true)) {
                    parts.add(callLetters)
                }
                if (stationType.isNotEmpty() && !parts.contains(stationType)) {
                    parts.add(stationType)
                }
                if (radioText.isNotEmpty() && !parts.contains(radioText)) {
                    parts.add(radioText)
                }

                if (parts.isEmpty() && bundle != null) {
                    for (key in bundle.keySet()) {
                        @Suppress("DEPRECATION")
                        val v = bundle.get(key)?.toString()?.trim().orEmpty()
                        if (v.isNotEmpty() && v != "true" && v != "false") {
                            val maybeFreq = formatRadioFrequency(v, band)
                            if (maybeFreq.isNotEmpty()) parts.add(maybeFreq)
                        }
                    }
                }

                if (parts.isNotEmpty()) {
                    val fullRadioString = parts.joinToString("  •  ")
                    dashboardView.updateRadioInfo(fullRadioString, true)
                    return
                }
            }

            // 7. Process Music / CarPlay Spotify / AVRCP Bluetooth Stream Metadata
            val track = extractFirstString(
                bundle,
                "track", "title", "music_title", "song", "episode", "name", "bt_title", "avrcp_title", "android.media.metadata.TITLE"
            )
            val artist = extractFirstString(
                bundle,
                "artist", "author", "music_artist", "writer", "podcast", "show", "bt_artist", "avrcp_artist", "android.media.metadata.ARTIST"
            )
            val album = extractFirstString(
                bundle,
                "album", "music_album", "series", "station", "bt_album", "avrcp_album", "android.media.metadata.ALBUM"
            )

            // Check if the iPhone passed a TEMP:XX tag inside the AVRCP/Bluetooth track title
            val combinedMeta = "$track $artist $album"
            val tempMatcher = Pattern.compile("TEMP:(-?\\d{1,3})", Pattern.CASE_INSENSITIVE).matcher(combinedMeta)
            if (tempMatcher.find()) {
                val parsedTemp = tempMatcher.group(1)?.toIntOrNull()
                if (parsedTemp != null) {
                    dashboardView.updateLiveTemperature(parsedTemp)
                    return
                }
            }

            val isPlaying = when {
                action.contains("playbackcomplete", ignoreCase = true) -> false
                action.contains("pause", ignoreCase = true) -> false
                bundle?.containsKey("playing") == true -> bundle.getBoolean("playing", true)
                bundle?.containsKey("playstate") == true -> {
                    @Suppress("DEPRECATION")
                    val st = bundle.get("playstate")
                    when (st) {
                        is Boolean -> st
                        is Int -> st == 1 || st == 3
                        else -> true
                    }
                }
                bundle?.containsKey("isPlaying") == true -> bundle.getBoolean("isPlaying", true)
                else -> true
            }

            if (track.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty()) {
                dashboardView.updateMediaInfo(track, artist, album, isPlaying)
            }
        }
    }

    /**
     * Starts a tiny local HTTP listener on Port 8765 so an iPhone connected via CarPlay
     * (Wired USB or Wireless CarPlay bridge) can push its live Apple Weather temperature
     * directly to the head unit without needing Wi-Fi internet access!
     *
     * Example URL called by iPhone Shortcut:
     * http://192.168.43.1:8765/weather?temp=64
     */
    private fun startIphoneLocalWeatherServer() {
        if (isServerRunning) return
        isServerRunning = true
        Thread {
            try {
                val server = ServerSocket(8765)
                iphoneWeatherServer = server
                while (isServerRunning) {
                    val client = server.accept()
                    Thread {
                        try {
                            client.soTimeout = 3000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val requestLine = reader.readLine().orEmpty()
                            // e.g. GET /weather?temp=64&compass=NE HTTP/1.1
                            if (requestLine.isNotEmpty()) {
                                val decoded = URLDecoder.decode(requestLine, "UTF-8")
                                val tempMatch = Pattern.compile("temp=([-+]?\\d{1,3})", Pattern.CASE_INSENSITIVE).matcher(decoded)
                                if (tempMatch.find()) {
                                    val tempF = tempMatch.group(1)?.toIntOrNull()
                                    if (tempF != null) {
                                        runOnUiThread {
                                            dashboardView.updateLiveTemperature(tempF)
                                            carPlaySessionActive = true
                                            updateCenterMapVisibility()
                                        }
                                    }
                                }
                            }
                            val out = PrintWriter(client.getOutputStream(), true)
                            out.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                            out.flush()
                            client.close()
                        } catch (_: Exception) {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }.start()
                }
            } catch (_: Exception) {
                isServerRunning = false
            }
        }.start()
    }

    /**
     * Returns all active local IPv4 addresses on the head unit (e.g., CarPlay P2P bridge, USB0, or WLAN0)
     * so the user knows the exact IP address for the iPhone Shortcut.
     */
    fun getLocalBridgeIpAddresses(): String {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add("${nif.name}: http://${addr.hostAddress}:8765/weather?temp=")
                    }
                }
            }
        } catch (_: Exception) {}
        return if (ips.isNotEmpty()) {
            ips.joinToString("\n")
        } else {
            "http://192.168.43.1:8765/weather?temp="
        }
    }

    fun controlCarPlaySpotify(command: String) {
        when (command) {
            "PLAY_PAUSE" -> {
                mediaHelper.playPause()
                try {
                    sendBroadcast(Intent("com.zjinnova.zlink.action.MEDIA_PLAY_PAUSE"))
                    sendBroadcast(Intent("com.xyauto.broadcast.BT_MUSIC_PLAY_PAUSE"))
                } catch (_: Exception) {}
            }
            "NEXT" -> {
                mediaHelper.nextTrack()
                try {
                    sendBroadcast(Intent("com.zjinnova.zlink.action.MEDIA_NEXT"))
                    sendBroadcast(Intent("com.xyauto.broadcast.BT_MUSIC_NEXT"))
                } catch (_: Exception) {}
            }
            "PREV" -> {
                mediaHelper.prevTrack()
                try {
                    sendBroadcast(Intent("com.zjinnova.zlink.action.MEDIA_PREV"))
                    sendBroadcast(Intent("com.xyauto.broadcast.BT_MUSIC_PREV"))
                } catch (_: Exception) {}
            }
        }
        sendBroadcast(Intent("com.custom.carlauncher.FORCE_A11Y_SCAN").putExtra("return_home", false))
        mainHandler.postDelayed({
            sendBroadcast(Intent("com.custom.carlauncher.FORCE_A11Y_SCAN").putExtra("return_home", false))
            dashboardView.syncAudioAndRadioState()
        }, 350L)
        mainHandler.postDelayed({
            dashboardView.syncAudioAndRadioState()
        }, 850L)
    }

    fun stepTrackOrRadioStation(isNext: Boolean, isRadioMode: Boolean, prefs: SharedPreferences) {
        val beforeRadioText = prefs.getString("last_radio_text", "").orEmpty()

        if (isNext) {
            mediaHelper.nextTrack()
        } else {
            mediaHelper.prevTrack()
        }

        if (isRadioMode) {
            try {
                val dir = if (isNext) 1 else -1
                sendBroadcast(Intent("com.xyauto.broadcast.RADIO_CONTROL").putExtra("cmd", if (isNext) "next" else "prev"))
                sendBroadcast(Intent("com.car.ui.RADIO_CONTROL").putExtra("action", if (isNext) "next" else "prev"))
                sendBroadcast(Intent("com.microntek.radio").putExtra("type", if (isNext) "next_channel" else "prev_channel"))
                sendBroadcast(Intent("com.tw.radio.action").putExtra("step", dir))
            } catch (_: Exception) {}

            sendBroadcast(Intent("com.custom.carlauncher.FORCE_A11Y_SCAN").putExtra("return_home", false))

            mainHandler.postDelayed({
                val polled = pollSystemRadioFrequency(prefs)
                if (polled.isNotEmpty() && polled != beforeRadioText) {
                    dashboardView.updateRadioInfo(polled, true)
                } else {
                    val latestSaved = prefs.getString("last_radio_text", "").orEmpty()
                    if (latestSaved.isNotEmpty() && latestSaved != beforeRadioText) {
                        dashboardView.updateRadioInfo(latestSaved, true)
                    } else if (isAccessibilityReaderEnabled()) {
                        performQuickRadioUiSync(prefs)
                    }
                }
            }, 260L)
        } else {
            sendBroadcast(Intent("com.custom.carlauncher.FORCE_A11Y_SCAN").putExtra("return_home", false))
            mainHandler.postDelayed({
                sendBroadcast(Intent("com.custom.carlauncher.FORCE_A11Y_SCAN").putExtra("return_home", false))
                dashboardView.syncAudioAndRadioState()
            }, 350L)
            mainHandler.postDelayed({
                dashboardView.syncAudioAndRadioState()
            }, 850L)
        }
    }

    private fun performQuickRadioUiSync(prefs: SharedPreferences) {
        val radioPkg = prefs.getString("pkg_RADIO", null) ?: return
        val radioCls = prefs.getString("cls_RADIO", null)
        try {
            prefs.edit().putBoolean("pending_radio_peek", true).apply()
            val peekIntent = if (!radioCls.isNullOrBlank()) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(radioPkg, radioCls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            } else {
                packageManager.getLaunchIntentForPackage(radioPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            }

            if (peekIntent != null) {
                startActivity(peekIntent)
                sendBroadcast(Intent("com.custom.carlauncher.FORCE_A11Y_SCAN").putExtra("return_home", true))
                mainHandler.postDelayed({
                    prefs.edit().putBoolean("pending_radio_peek", false).apply()
                    val homeIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(homeIntent)
                    dashboardView.syncAudioAndRadioState()
                }, 320L)
            }
        } catch (_: Exception) {
            prefs.edit().putBoolean("pending_radio_peek", false).apply()
        }
    }

    private fun hasAnyKey(bundle: Bundle?, vararg keys: String): Boolean {
        if (bundle == null) return false
        for (k in keys) {
            for (actual in bundle.keySet()) {
                if (actual.contains(k, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun extractFirstString(bundle: Bundle?, vararg keys: String): String {
        if (bundle == null) return ""
        for (k in keys) {
            for (actualKey in bundle.keySet()) {
                if (actualKey.equals(k, ignoreCase = true)) {
                    @Suppress("DEPRECATION")
                    val v = bundle.get(actualKey)?.toString()?.trim().orEmpty()
                    if (v.isNotEmpty() && v != "null") return v
                }
            }
        }
        return ""
    }

    fun formatRadioFrequency(raw: String?, band: String): String {
        if (raw.isNullOrBlank()) return ""
        val clean = raw.replace("MHz", "", ignoreCase = true)
            .replace("kHz", "", ignoreCase = true)
            .replace("FM1", "", ignoreCase = true)
            .replace("FM2", "", ignoreCase = true)
            .replace("FM3", "", ignoreCase = true)
            .replace("FM", "", ignoreCase = true)
            .replace("AM", "", ignoreCase = true)
            .trim()
        val num = clean.toDoubleOrNull() ?: return ""
        return when {
            num in 8700.0..10850.0 -> String.format(Locale.US, "%.1f %s", num / 100.0, band.ifEmpty { "FM" })
            num in 87000.0..108500.0 -> String.format(Locale.US, "%.1f %s", num / 1000.0, band.ifEmpty { "FM" })
            num in 875.0..1085.0 -> String.format(Locale.US, "%.1f %s", num / 10.0, band.ifEmpty { "FM" })
            num in 87.0..108.5 -> String.format(Locale.US, "%.1f %s", num, band.ifEmpty { "FM" })
            num in 500.0..1750.0 -> String.format(Locale.US, "%.0f AM", num)
            else -> ""
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trustAllSslCertificatesForHeadUnit()
        mediaHelper = MediaControlHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val rootContainer = FrameLayout(this)
        dashboardView = AgamaDashboardView(this, mediaHelper)
        rootContainer.addView(
            dashboardView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        circularMapWebView = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#08090C"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = WebViewClient()

            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true

            setOnTouchListener { _, event ->
                val globalX = x + event.x
                val globalY = y + event.y
                val forwarded = MotionEvent.obtain(
                    event.downTime,
                    event.eventTime,
                    event.action,
                    globalX,
                    globalY,
                    event.metaState
                )
                val handled = dashboardView.onTouchEvent(forwarded)
                forwarded.recycle()
                handled
            }
        }

        rootContainer.addView(circularMapWebView, FrameLayout.LayoutParams(100, 100, Gravity.CENTER))
        setContentView(rootContainer)

        dashboardView.post {
            val diameter = (dashboardView.height * 0.56f).toInt()
            val lp = FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER)
            circularMapWebView.layoutParams = lp
            updateCenterMapVisibility()
        }

        registerMediaRadioAndNavBroadcasts()
        startIphoneLocalWeatherServer()
        checkAndRequestPermissions()
    }

    fun updateCenterMapVisibility() {
        if (!::circularMapWebView.isInitialized || !::dashboardView.isInitialized) return
        val shouldShowLiveCircleMap = (dashboardView.getCenterMode() == 3) && isCarPlayActivelyConnected()

        if (shouldShowLiveCircleMap) {
            if (circularMapWebView.visibility != View.VISIBLE) {
                circularMapWebView.visibility = View.VISIBLE
            }
            val lat = lastLat ?: 41.4993
            val lon = lastLon ?: -81.6944
            if (!isMapLoaded) {
                isMapLoaded = true
                val mapHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                    <style>
                      html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #08090C; overflow: hidden; }
                      .leaflet-control-container { display: none; }
                      .car-dot {
                        width: 16px; height: 16px; border-radius: 50%;
                        background: #E6192E; border: 3px solid #FFFFFF;
                        box-shadow: 0 0 12px #E6192E;
                      }
                    </style>
                    </head>
                    <body>
                    <div id="map"></div>
                    <script>
                      var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([$lat, $lon], 16);
                      L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', { maxZoom: 19 }).addTo(map);
                      var icon = L.divIcon({ className: 'car-dot', iconSize: [16, 16], iconAnchor: [8, 8] });
                      var marker = L.marker([$lat, $lon], { icon: icon }).addTo(map);
                      function updatePos(lat, lon) {
                        marker.setLatLng([lat, lon]);
                        map.panTo([lat, lon], { animate: true });
                      }
                    </script>
                    </body>
                    </html>
                """.trimIndent()
                circularMapWebView.loadDataWithBaseURL("https://unpkg.com", mapHtml, "text/html", "UTF-8", null)
            } else {
                circularMapWebView.evaluateJavascript("if(typeof updatePos==='function') updatePos($lat, $lon);", null)
            }
        } else {
            if (circularMapWebView.visibility != View.GONE) {
                circularMapWebView.visibility = View.GONE
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
        startGpsAndWeatherUpdates()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            startGpsAndWeatherUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        dashboardView.syncAudioAndRadioState()
        updateCenterMapVisibility()
        refreshWeatherIfNeeded(force = false, showToast = false)
    }

    private fun registerMediaRadioAndNavBroadcasts() {
        val filter = IntentFilter().apply {
            addAction("com.custom.carlauncher.IPHONE_TEMP_RECEIVED")
            addAction("com.custom.carlauncher.RADIO_SCRAPED")
            addAction("com.custom.carlauncher.MEDIA_NOTIFICATION_SCRAPED")
            addAction("com.custom.carlauncher.CARPLAY_ACTIVE")

            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.android.music.playbackcomplete")
            addAction("com.android.music.queuechanged")
            addAction("com.spotify.music.metadatachanged")
            addAction("com.spotify.music.playbackstatechanged")
            addAction("com.maxmpz.audioplayer.TRACK_CHANGED")
            addAction("com.maxmpz.audioplayer.STATUS_CHANGED")
            addAction("com.amazon.mp3.metachanged")
            addAction("fm.last.android.metachanged")
            addAction("com.nullsoft.winamp.metachanged")
            addAction("com.miui.player.metachanged")
            addAction("com.andrew.apollo.metachanged")
            addAction("com.sec.android.app.music.metachanged")
            addAction("org.videolan.vlc.player.result")
            addAction("in.krosbits.musicolet.metachanged")
            addAction("in.krosbits.musicolet.playstatechanged")
            addAction("com.bambuna.podcastaddict.service.player.update")
            addAction("de.danoeh.antennapod.intent.PLAYBACK_STATUS")

            addAction("com.microntek.music.toast")
            addAction("com.microntek.music.report")
            addAction("com.xyauto.music.metachanged")
            addAction("com.xyauto.broadcast.MUSIC_INFO")
            addAction("com.xyauto.broadcast.BT_MUSIC_INFO")
            addAction("com.xyauto.broadcast.RADIO_INFO")
            addAction("com.xyauto.radio.metachanged")
            addAction("com.xyauto.broadcast.radio")
            addAction("com.xyauto.action.RADIO_INFO")
            addAction("com.xyauto.Radio.Action")
            addAction("com.SwcApplication.radio")
            addAction("com.tw.radio.info")
            addAction("com.tw.broadcast.RADIO")
            addAction("com.car.ui.radio")
            addAction("com.microntek.radio.report")
            addAction("com.android.fmradio.metachanged")
            addAction("com.android.fmradio.FMRADIO_STATE_CHANGED")
            addAction("com.caf.fmradio.FMRADIO_STATE_CHANGED")
            addAction("com.fyt.radio")
            addAction("com.syu.ms.radio")
            addAction("com.hcn.radio.info")
            addAction("com.glsx.boot.AC_RADIO_INFO")
            addAction("cn.abel.action.broadcast")
            addAction("com.ts.main.radio.info")

            addAction("com.zjinnova.zlink")
            addAction("com.zjinnova.zlink.action.BACK_CAR")
            addAction("com.xyauto.broadcast.NAVI_INFO")
            addAction("AUTONAVI_STANDARD_BROADCAST_SEND")

            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("com.xyauto.broadcast.BT_STATE")
            addAction("com.microntek.bt.report")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaRadioAndNavReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mediaRadioAndNavReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        try { iphoneWeatherServer?.close() } catch (_: Exception) {}
        try { unregisterReceiver(mediaRadioAndNavReceiver) } catch (_: Exception) {}
    }

    private val sharedLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLat = location.latitude
            lastLon = location.longitude

            var mph = if (location.hasSpeed()) (location.speed * 2.23694f).toInt() else 0
            var computedBearing: Float? = if (location.hasBearing() && location.bearing != 0f) {
                location.bearing
            } else {
                null
            }

            val prev = previousGpsLocation
            if (prev != null) {
                val distMeters = prev.distanceTo(location)
                val timeDeltaSec = ((location.time - prev.time) / 1000f).coerceAtLeast(0.5f)

                if (mph == 0 && distMeters > 1.5f && timeDeltaSec < 15f) {
                    mph = ((distMeters / timeDeltaSec) * 2.23694f).toInt()
                }

                if (distMeters >= 2.5f) {
                    val rawBearing = prev.bearingTo(location)
                    computedBearing = ((rawBearing % 360f) + 360f) % 360f
                    previousGpsLocation = location
                }
            } else {
                previousGpsLocation = location
            }

            dashboardView.updateGpsData(mph, computedBearing)
            updateCenterMapVisibility()
            refreshWeatherIfNeeded(force = false, showToast = false)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startGpsAndWeatherUpdates() {
        refreshWeatherIfNeeded(force = true, showToast = false)

        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastGps != null) {
                lastLat = lastGps.latitude
                lastLon = lastGps.longitude
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    400L,
                    0f,
                    sharedLocationListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    sharedLocationListener
                )
            }
        } catch (_: SecurityException) {}
    }

    private fun trustAllSslCertificatesForHeadUnit() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (_: Exception) {}
    }

    private fun httpGetText(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 CarLauncher")
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun refreshWeatherIfNeeded(force: Boolean, showToast: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastWeatherFetchTime) < 10 * 60 * 1000L) return
        lastWeatherFetchTime = now

        Thread {
            try {
                var lat = lastLat
                var lon = lastLon

                if (lat == null || lon == null) {
                    val ipResp = httpGetText("http://ip-api.com/json/")
                    if (ipResp != null) {
                        val j = JSONObject(ipResp)
                        if (j.has("lat") && j.has("lon")) {
                            lat = j.getDouble("lat")
                            lon = j.getDouble("lon")
                        }
                    }
                }

                if (lat == null || lon == null) {
                    val ipResp2 = httpGetText("https://freeipapi.com/api/json")
                    if (ipResp2 != null) {
                        val j = JSONObject(ipResp2)
                        if (j.has("latitude") && j.has("longitude")) {
                            lat = j.getDouble("latitude")
                            lon = j.getDouble("longitude")
                        }
                    }
                }

                if (lat != null && lon != null) {
                    val weatherUrl =
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&temperature_unit=fahrenheit"
                    val weatherResp = httpGetText(weatherUrl)
                    if (weatherResp != null) {
                        val json = JSONObject(weatherResp)
                        val current = json.getJSONObject("current_weather")
                        val tempF = current.getDouble("temperature").roundToInt()
                        runOnUiThread {
                            dashboardView.updateLiveTemperature(tempF)
                            if (showToast) {
                                Toast.makeText(this, "Live Temp Updated: ${tempF}°F", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@Thread
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun isBluetoothDeviceConnected(): Boolean {
        if (aclBluetoothConnected) return true
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            if (am.isBluetoothA2dpOn || am.isBluetoothScoOn) return true

            val btSettingKeys = arrayOf("bt_state", "bt_connected", "bluetooth_connected", "xy_bt_state", "mcu_bt_state")
            for (k in btSettingKeys) {
                val v = Settings.System.getInt(contentResolver, k, -1)
                if (v == 1 || v == 2) return true
            }

            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
                val headset = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                if (a2dp == BluetoothProfile.STATE_CONNECTED || headset == BluetoothProfile.STATE_CONNECTED) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun isCarPlayActivelyConnected(): Boolean {
        if (carPlaySessionActive) return true
        try {
            val propKeys = arrayOf(
                "sys.zlink.connected", "persist.sys.zlink.connected",
                "sys.carplay.connected", "xy.carplay.state", "sys.tlink.connected"
            )
            for (prop in propKeys) {
                val proc = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                val line = BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()
                if (line == "1" || line.equals("true", ignoreCase = true) || line.equals("connected", ignoreCase = true)) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun pollSystemRadioFrequency(prefs: SharedPreferences): String {
        val candidateRadioPkgs = mutableSetOf(
            prefs.getString("pkg_RADIO", null),
            "com.car.ui",
            "com.xyauto.radio",
            "com.android.fmradio",
            "com.tw.radio",
            "com.microntek.radio",
            "com.syu.radio",
            "com.fyt.radio"
        ).filterNotNull()

        for (pkg in candidateRadioPkgs) {
            try {
                val radioCtx = createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                val sharedPrefsDir = File(radioCtx.applicationInfo.dataDir, "shared_prefs")
                val xmlFiles = sharedPrefsDir.listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
                for (file in xmlFiles) {
                    val prefName = file.name.removeSuffix(".xml")
                    val sp = radioCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    for ((key, value) in sp.all) {
                        val kLower = key.lowercase(Locale.US)
                        if (kLower.contains("freq") || kLower.contains("channel") || kLower.contains("cur") || kLower.contains("fm")) {
                            val formatted = formatRadioFrequency(value?.toString(), "FM")
                            if (formatted.isNotEmpty()) return formatted
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val sysKeys = arrayOf(
            "radio_freq", "cur_freq", "fm_freq", "xy_radio_freq",
            "radio_frequency", "RadioFreq", "mcu_radio_freq", "rds_ps"
        )
        for (k in sysKeys) {
            try {
                val v = Settings.System.getString(contentResolver, k)
                if (!v.isNullOrBlank()) {
                    val formatted = formatRadioFrequency(v, "FM")
                    if (formatted.isNotEmpty()) return formatted
                }
                val intVal = Settings.System.getInt(contentResolver, k, -1)
                if (intVal > 500) {
                    val formatted = formatRadioFrequency(intVal.toString(), "FM")
                    if (formatted.isNotEmpty()) return formatted
                }
            } catch (_: Exception) {}
        }

        val propKeys = arrayOf(
            "sys.radio.freq", "persist.sys.radio.freq", "sys.xyauto.radio.freq",
            "persist.radio.freq", "hw.radio.freq", "sys.fm.freq"
        )
        for (prop in propKeys) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                val line = BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()
                if (!line.isNullOrEmpty()) {
                    val formatted = formatRadioFrequency(line, "FM")
                    if (formatted.isNotEmpty()) return formatted
                }
            } catch (_: Exception) {}
        }

        return ""
    }

    fun isAccessibilityReaderEnabled(): Boolean {
        val expected = "$packageName/${RadioScreenReaderService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.contains(expected, ignoreCase = true) ||
                enabledServices.contains(packageName, ignoreCase = true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                mediaHelper.playPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                mediaHelper.nextTrack()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                mediaHelper.prevTrack()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

class AgamaDashboardView(
    private val hostActivity: MainActivity,
    private val mediaHelper: MediaControlHelper
) : View(hostActivity) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agama_subaru_prefs", Context.MODE_PRIVATE)
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bgMidnightBlack = Color.parseColor("#000000")

    private val defaultPrimaryRed = Color.parseColor("#E6192E")
    private val defaultSecondaryBlue = Color.parseColor("#4A90E2")
    private val defaultInnerRingGray = Color.parseColor("#5E6578")
    private val defaultWingBaseGray = Color.parseColor("#14171E")
    private val spotifyGreen = Color.parseColor("#1DB954")

    private var primaryColor = prefs.getInt("primary_color_v3", defaultPrimaryRed)
    private var secondaryColor = prefs.getInt("secondary_color_v3", defaultSecondaryBlue)
    private var innerRingColor = prefs.getInt("inner_ring_color_v3", defaultInnerRingGray)
    private var wingBaseColor = prefs.getInt("wing_base_color_v3", defaultWingBaseGray)

    private var centerMode = prefs.getInt("center_mode", 0)
    private var currentMph = 0
    private var compassDir = prefs.getString("last_compass", "N") ?: "N"
    private var temperatureF = prefs.getInt("temp_f", 59)

    private var isMediaBroadcastPlaying = false
    private var isRadioActive = prefs.getBoolean("is_radio_active", false)
    private var currentMediaText: String = prefs.getString("last_media_text", "") ?: ""
    private var currentTrackTitle: String = prefs.getString("last_track_title", "CarPlay Spotify") ?: "CarPlay Spotify"
    private var currentTrackArtist: String = prefs.getString("last_track_artist", "Connected via iPhone") ?: "Connected via iPhone"
    private var currentRadioText: String = prefs.getString("last_radio_text", "") ?: ""
    private var scrollXOffset = 0f
    private var pressedZone: String? = null

    private val wingKeys = listOf("RADIO", "CARPLAY", "MUSIC", "PHONE", "INTERNET", "APPS")

    private val iconTypeNames = arrayOf(
        "Radio Receiver",
        "CarPlay / Media Screen",
        "Music Note",
        "Phone Handset",
        "Internet Globe",
        "Apps 9-Dot Grid",
        "Navigation Arrow",
        "Bluetooth Symbol",
        "Equalizer / DSP",
        "Camera / Dashcam",
        "Car / Gauge",
        "Folder / USB"
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val basketballDimpleCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = createBasketballDimpleShader(
            Color.parseColor("#08090C"),
            Color.parseColor("#181B22"),
            Color.parseColor("#030405")
        )
    }
    private val basketballDimpleWingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = createBasketballDimpleShader(
            wingBaseColor,
            Color.parseColor("#1F242E"),
            Color.parseColor("#080A0E")
        )
    }
    private val podPressedColor = Color.parseColor("#252A36")

    private val solidInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#1E222A")
    }
    private val solidInnerRingEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#353B48")
    }

    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        color = Color.parseColor("#000000")
        strokeCap = Paint.Cap.BUTT
    }

    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        color = Color.parseColor("#E4E8F0")
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(11f, 9.5f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
    }

    private val textWhiteOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconWhiteOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val thinWhiteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        color = Color.WHITE
    }

    fun getCenterMode(): Int = centerMode

    private fun createBasketballDimpleShader(baseColor: Int, highlightColor: Int, pitColor: Int): BitmapShader {
        val size = 18
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        c.drawColor(baseColor)

        val rimHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = highlightColor
        }
        val dimplePit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = pitColor
        }

        val coords = arrayOf(4.5f to 4.5f, 13.5f to 13.5f)
        for ((dx, dy) in coords) {
            c.drawCircle(dx + 0.8f, dy + 0.8f, 3.3f, rimHighlight)
            c.drawCircle(dx, dy, 3.1f, dimplePit)
        }

        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private var pollCounter = 0

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val systemMusicPlaying = audioManager.isMusicActive

            pollCounter++
            if (isRadioActive && pollCounter % 35 == 0) {
                val polledFreq = hostActivity.pollSystemRadioFrequency(prefs)
                if (polledFreq.isNotEmpty()) {
                    currentRadioText = polledFreq
                    prefs.edit().putString("last_radio_text", currentRadioText).apply()
                } else {
                    val cached = prefs.getString("last_radio_text", "").orEmpty()
                    if (cached.isNotEmpty()) currentRadioText = cached
                }
            }

            if (systemMusicPlaying && isRadioActive && isMediaBroadcastPlaying) {
                isRadioActive = false
                prefs.edit().putBoolean("is_radio_active", false).apply()
            }

            val shouldScroll = systemMusicPlaying || isMediaBroadcastPlaying || isRadioActive
            if (shouldScroll) {
                scrollXOffset += 3.0f
                invalidate()
                postDelayed(this, 40L)
            } else {
                invalidate()
                postDelayed(this, 1000L)
            }
        }
    }

    init {
        post(tickerRunnable)
    }

    fun syncAudioAndRadioState() {
        if (audioManager.isMusicActive) {
            isMediaBroadcastPlaying = true
        }
        val scrapedOrSaved = prefs.getString("last_radio_text", "").orEmpty()
        if (scrapedOrSaved.isNotEmpty()) {
            currentRadioText = scrapedOrSaved
        }
        val savedMedia = prefs.getString("last_media_text", "").orEmpty()
        if (savedMedia.isNotEmpty() && currentMediaText.isEmpty()) {
            currentMediaText = savedMedia
        }
        val sysFreq = hostActivity.pollSystemRadioFrequency(prefs)
        if (sysFreq.isNotEmpty()) {
            currentRadioText = sysFreq
            prefs.edit().putString("last_radio_text", sysFreq).apply()
        }
        invalidate()
    }

    fun updateLiveTemperature(tempF: Int) {
        temperatureF = tempF
        prefs.edit().putInt("temp_f", tempF).apply()
        invalidate()
    }

    fun updateGpsData(mph: Int, forwardBearing: Float?) {
        currentMph = mph
        if (forwardBearing != null) {
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val norm = ((forwardBearing % 360f) + 360f) % 360f
            val idx = ((norm + 22.5f) / 45f).toInt() % 8
            compassDir = dirs[idx]
            prefs.edit().putString("last_compass", compassDir).apply()
        }
        invalidate()
    }

    fun updateCarPlayNavData(connected: Boolean, road: String, dist: String) {
        hostActivity.carPlaySessionActive = connected
        hostActivity.updateCenterMapVisibility()
        invalidate()
    }

    fun updateMediaInfo(track: String, artist: String, album: String, playing: Boolean) {
        isMediaBroadcastPlaying = playing
        if (playing) {
            isRadioActive = false
            prefs.edit().putBoolean("is_radio_active", false).apply()
        }

        if (track.isNotEmpty()) {
            currentTrackTitle = track
            prefs.edit().putString("last_track_title", track).apply()
        }
        val cleanArtist = if (!artist.equals("<unknown>", ignoreCase = true)) artist else ""
        val cleanAlbum = if (!album.equals("<unknown>", ignoreCase = true) && !album.equals(track, ignoreCase = true)) album else ""

        val artistLine = listOf(cleanArtist, cleanAlbum).filter { it.isNotEmpty() }.joinToString(" • ")
        if (artistLine.isNotEmpty()) {
            currentTrackArtist = artistLine
            prefs.edit().putString("last_track_artist", artistLine).apply()
        }

        val parts = mutableListOf<String>()
        if (track.isNotEmpty()) parts.add(track)
        if (cleanArtist.isNotEmpty()) parts.add(cleanArtist)
        if (cleanAlbum.isNotEmpty()) parts.add(cleanAlbum)

        if (parts.isNotEmpty()) {
            currentMediaText = parts.joinToString("  —  ")
            prefs.edit().putString("last_media_text", currentMediaText).apply()
        }
        invalidate()
    }

    fun updateRadioInfo(stationText: String, active: Boolean) {
        isRadioActive = active
        prefs.edit().putBoolean("is_radio_active", active).apply()
        if (active) {
            isMediaBroadcastPlaying = false
        }
        if (stationText.isNotBlank()) {
            currentRadioText = stationText.trim()
            prefs.edit().putString("last_radio_text", currentRadioText).apply()
        }
        invalidate()
    }

    fun setRadioInactive() {
        isRadioActive = false
        prefs.edit().putBoolean("is_radio_active", false).apply()
        invalidate()
    }

    private fun drawRedOutlinedText(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
        textWhiteOutlinePaint.textSize = size
        textPaint.textSize = size
        textPaint.color = primaryColor
        canvas.drawText(text, x, y, textWhiteOutlinePaint)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun getDxInner(y: Float, cy: Float, rInner: Float): Float {
        val dy = abs(y - cy)
        return if (dy < rInner) sqrt(rInner * rInner - dy * dy) else 0f
    }

    private fun getDxOuter(y: Float, cy: Float, rxOuter: Float, ryOuter: Float): Float {
        val dy = y - cy
        val ratio = (dy * dy) / (ryOuter * ryOuter)
        return if (ratio < 1f) rxOuter * sqrt(1f - ratio) else 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        canvas.drawColor(bgMidnightBlack)

        val rHub = h * 0.29f
        val rInner = h * 0.465f
        val rxOuter = w * 0.47f
        val ryOuter = h * 0.58f

        drawOuterWings(canvas, cx, cy, w, h, rInner, rxOuter, ryOuter)
        drawInnerRing(canvas, cx, cy, h, rHub, rInner)
        drawCentralHub(canvas, cx, cy, rHub)
    }

    private fun drawOuterWings(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rInner: Float,
        rxOuter: Float,
        ryOuter: Float
    ) {
        val wingRect = RectF(cx - rxOuter, cy - ryOuter, cx + rxOuter, cy + ryOuter)
        canvas.drawOval(wingRect, basketballDimpleWingPaint)

        val y1 = h * 0.19f
        val y2 = h * 0.39f
        val y3 = h * 0.61f
        val y4 = h * 0.81f
        val rowCenters = floatArrayOf(h * 0.095f, h * 0.29f, h * 0.50f, h * 0.71f, h * 0.905f)

        pressedZone?.let { zone ->
            if (zone.startsWith("WING_") || zone in listOf("CLOCK_TOP_LEFT", "TEMP_TOP_RIGHT", "WIFI_BOTTOM_LEFT", "BT_BOTTOM_LEFT", "GEAR_SKIN", "GEAR_HOME")) {
                fillPaint.color = podPressedColor
                val rowIdx = when (zone) {
                    "CLOCK_TOP_LEFT", "TEMP_TOP_RIGHT" -> 0
                    "WING_RADIO", "WING_PHONE" -> 1
                    "WING_CARPLAY", "WING_INTERNET" -> 2
                    "WING_MUSIC", "WING_APPS" -> 3
                    else -> 4
                }
                val topY = when (rowIdx) { 0 -> 0f; 1 -> y1; 2 -> y2; 3 -> y3; else -> y4 }
                val botY = when (rowIdx) { 0 -> y1; 1 -> y2; 2 -> y3; 3 -> y4; else -> h }
                val isLeft = zone in listOf("CLOCK_TOP_LEFT", "WING_RADIO", "WING_CARPLAY", "WING_MUSIC", "WIFI_BOTTOM_LEFT", "BT_BOTTOM_LEFT")
                canvas.save()
                canvas.clipRect(if (isLeft) 0f else cx, topY, if (isLeft) cx else w, botY)
                canvas.drawOval(wingRect, fillPaint)
                canvas.restore()
            }
        }

        val rInnerStitch = rInner + 4f
        for (y in floatArrayOf(y1, y2, y3, y4)) {
            val dxIn = getDxInner(y, cy, rInnerStitch)
            val dxOut = getDxOuter(y, cy, rxOuter, ryOuter)
            if (dxOut > dxIn) {
                canvas.drawLine(cx - dxOut, y, cx - dxIn, y, groovePaint)
                canvas.drawLine(cx - dxOut + 3f, y, cx - dxIn - 3f, y, stitchPaint)
                canvas.drawLine(cx + dxIn, y, cx + dxOut, y, groovePaint)
                canvas.drawLine(cx + dxIn + 3f, y, cx + dxOut - 3f, y, stitchPaint)
            }
        }

        val leftOuterPath = Path()
        val rightOuterPath = Path()
        val steps = 60
        for (i in 0..steps) {
            val py = (i.toFloat() / steps) * h
            val dxOut = getDxOuter(py, cy, rxOuter, ryOuter)
            if (i == 0) {
                leftOuterPath.moveTo(cx - dxOut, py)
                rightOuterPath.moveTo(cx + dxOut, py)
            } else {
                leftOuterPath.lineTo(cx - dxOut, py)
                rightOuterPath.lineTo(cx + dxOut, py)
            }
        }
        canvas.drawPath(leftOuterPath, groovePaint)
        canvas.drawPath(leftOuterPath, stitchPaint)
        canvas.drawPath(rightOuterPath, groovePaint)
        canvas.drawPath(rightOuterPath, stitchPaint)

        val labelSize = h * 0.038f
        val leftSlots = arrayOf("RADIO" to rowCenters[1], "CARPLAY" to rowCenters[2], "MUSIC" to rowCenters[3])
        for ((key, ry) in leftSlots) {
            val midX = cx - (getDxInner(ry, cy, rInnerStitch) + getDxOuter(ry, cy, rxOuter, ryOuter)) / 2f
            val label = prefs.getString("label_$key", key) ?: key
            drawRedOutlinedText(canvas, label, midX, ry + labelSize * 0.35f, labelSize)
        }

        val rightSlots = arrayOf("PHONE" to rowCenters[1], "INTERNET" to rowCenters[2], "APPS" to rowCenters[3])
        for ((key, ry) in rightSlots) {
            val midX = cx + (getDxInner(ry, cy, rInnerStitch) + getDxOuter(ry, cy, rxOuter, ryOuter)) / 2f
            val label = prefs.getString("label_$key", key) ?: key
            drawRedOutlinedText(canvas, label, midX, ry + labelSize * 0.35f, labelSize)
        }

        val topCornerMidOffset = (getDxInner(rowCenters[0], cy, rInnerStitch) + getDxOuter(rowCenters[0], cy, rxOuter, ryOuter)) / 2f
        val botCornerMidOffset = (getDxInner(rowCenters[4], cy, rInnerStitch) + getDxOuter(rowCenters[4], cy, rxOuter, ryOuter)) / 2f

        drawTopLeftClock(canvas, cx - topCornerMidOffset, rowCenters[0], h)
        drawTopRightCompassAndTemp(canvas, cx + topCornerMidOffset, rowCenters[0], h)

        val bottomLeftSpacing = h * 0.046f
        val bottomLeftCenter = cx - botCornerMidOffset
        drawWifiSignalIcon(canvas, bottomLeftCenter - bottomLeftSpacing, rowCenters[4], h * 0.032f)
        drawBluetoothStatusIcon(canvas, bottomLeftCenter + bottomLeftSpacing, rowCenters[4], h * 0.031f)

        val gearSpacing = h * 0.045f
        drawGearIcon(canvas, cx + botCornerMidOffset - gearSpacing, rowCenters[4], h * 0.029f, secondaryColor)
        drawGearIcon(canvas, cx + botCornerMidOffset + gearSpacing, rowCenters[4], h * 0.029f, secondaryColor)
    }

    private fun drawInnerRing(canvas: Canvas, cx: Float, cy: Float, h: Float, rHub: Float, rInner: Float) {
        groovePaint.strokeWidth = 12f
        canvas.drawCircle(cx, cy, rInner, groovePaint)

        fillPaint.color = innerRingColor
        canvas.drawCircle(cx, cy, rInner - 4f, fillPaint)

        canvas.drawCircle(cx, cy, rInner + 4f, stitchPaint)

        groovePaint.strokeWidth = 10f
        val angles = floatArrayOf(42f, 138f, 222f, 318f)
        for (deg in angles) {
            val rad = Math.toRadians(deg.toDouble())
            val xStart = cx + rHub * cos(rad).toFloat()
            val yStart = cy + rHub * sin(rad).toFloat()
            val xEnd = cx + rInner * cos(rad).toFloat()
            val yEnd = cy + rInner * sin(rad).toFloat()
            canvas.drawLine(xStart, yStart, xEnd, yEnd, groovePaint)
            canvas.drawLine(xStart, yStart, xEnd, yEnd, stitchPaint)
        }

        for (y in floatArrayOf(h * 0.39f, h * 0.61f)) {
            val dy = abs(y - cy)
            if (dy < rInner) {
                val dxOuter = sqrt(rInner * rInner - dy * dy)
                val dxInner = if (dy < rHub) sqrt(rHub * rHub - dy * dy) else 0f
                canvas.drawLine(cx - dxOuter, y, cx - dxInner, y, groovePaint)
                canvas.drawLine(cx - dxOuter + 3f, y, cx - dxInner - 3f, y, stitchPaint)
                canvas.drawLine(cx + dxInner, y, cx + dxOuter, y, groovePaint)
                canvas.drawLine(cx + dxInner + 3f, y, cx + dxOuter - 3f, y, stitchPaint)
            }
        }

        val rMid = (rHub + rInner) / 2f

        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val dayFormat = SimpleDateFormat("EEEE", Locale.US)
        val now = Date()

        drawRedOutlinedText(canvas, dateFormat.format(now), cx, cy - rMid - h * 0.01f, h * 0.032f)

        textPaint.textSize = h * 0.026f
        textPaint.color = secondaryColor
        canvas.drawText(dayFormat.format(now), cx, cy - rMid + h * 0.03f, textPaint)

        val volAngleLeft = Math.toRadians(242.0)
        val volAngleRight = Math.toRadians(298.0)
        drawRedOutlinedText(canvas, "-", cx + rMid * cos(volAngleLeft).toFloat(), cy + rMid * sin(volAngleLeft).toFloat() + 14f, h * 0.054f)
        drawRedOutlinedText(canvas, "+", cx + rMid * cos(volAngleRight).toFloat(), cy + rMid * sin(volAngleRight).toFloat() + 14f, h * 0.054f)

        val bottomHeader = if (isRadioActive && !audioManager.isMusicActive) "Radio Tuner" else "Music Player"
        drawRedOutlinedText(canvas, bottomHeader, cx, cy + rMid - h * 0.006f, h * 0.031f)

        drawBottomMediaAndRadioTicker(canvas, cx, cy + rMid + h * 0.036f, rHub * 0.64f, h * 0.028f)

        val mediaAngleLeft = Math.toRadians(118.0)
        val mediaAngleRight = Math.toRadians(62.0)
        drawRedOutlinedText(canvas, "<", cx + rMid * cos(mediaAngleLeft).toFloat(), cy + rMid * sin(mediaAngleLeft).toFloat() + 12f, h * 0.050f)
        drawRedOutlinedText(canvas, ">", cx + rMid * cos(mediaAngleRight).toFloat(), cy + rMid * sin(mediaAngleRight).toFloat() + 12f, h * 0.050f)

        val iconSize = h * 0.031f
        drawCustomSlotIcon(canvas, "RADIO", 0, cx - rMid * 0.92f, h * 0.29f, iconSize)
        drawCustomSlotIcon(canvas, "CARPLAY", 1, cx - rMid, h * 0.50f, iconSize)
        drawCustomSlotIcon(canvas, "MUSIC", 2, cx - rMid * 0.92f, h * 0.71f, iconSize)

        drawCustomSlotIcon(canvas, "PHONE", 3, cx + rMid * 0.92f, h * 0.29f, iconSize)
        drawCustomSlotIcon(canvas, "INTERNET", 4, cx + rMid, h * 0.50f, iconSize)
        drawCustomSlotIcon(canvas, "APPS", 5, cx + rMid * 0.92f, h * 0.71f, iconSize)
    }

    private fun drawBottomMediaAndRadioTicker(canvas: Canvas, cx: Float, y: Float, halfWidth: Float, textSize: Float) {
        val systemMusicActive = audioManager.isMusicActive
        val mediaPlaying = systemMusicActive || isMediaBroadcastPlaying
        val radioPlaying = isRadioActive && !systemMusicActive

        if (!mediaPlaying && !radioPlaying) {
            return
        }

        val displayString = when {
            radioPlaying -> currentRadioText
            mediaPlaying -> currentMediaText
            else -> ""
        }

        if (displayString.isBlank()) return

        textPaint.textSize = textSize
        textPaint.color = secondaryColor

        val tickerText = "$displayString       •       "
        val textWidth = textPaint.measureText(tickerText)
        if (textWidth <= 0f) return

        canvas.save()
        canvas.clipRect(cx - halfWidth, y - textSize * 1.25f, cx + halfWidth, y + textSize * 0.45f)
        textPaint.textAlign = Paint.Align.LEFT
        val offset = scrollXOffset % textWidth
        val startX = (cx - halfWidth) - offset
        canvas.drawText(tickerText, startX, y, textPaint)
        canvas.drawText(tickerText, startX + textWidth, y, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.restore()
    }

    private fun drawCustomSlotIcon(canvas: Canvas, slotKey: String, defaultType: Int, cx: Float, cy: Float, s: Float) {
        val type = prefs.getInt("icon_$slotKey", defaultType)
        when (type) {
            0 -> drawRadioIcon(canvas, cx, cy, s, primaryColor)
            1 -> drawCarPlayIcon(canvas, cx, cy, s, primaryColor)
            2 -> drawMusicIcon(canvas, cx, cy, s, primaryColor)
            3 -> drawPhoneIcon(canvas, cx, cy, s, primaryColor)
            4 -> drawGlobeIcon(canvas, cx, cy, s, primaryColor)
            5 -> drawAppsGridIcon(canvas, cx, cy, s, primaryColor)
            6 -> drawNavArrowIcon(canvas, cx, cy, s, primaryColor)
            7 -> drawBluetoothIcon(canvas, cx, cy, s, primaryColor)
            8 -> drawEqualizerIcon(canvas, cx, cy, s, primaryColor)
            9 -> drawCameraIcon(canvas, cx, cy, s, primaryColor)
            10 -> drawGaugeIcon(canvas, cx, cy, s, primaryColor)
            else -> drawFolderIcon(canvas, cx, cy, s, primaryColor)
        }
    }

    private fun drawCentralHub(canvas: Canvas, cx: Float, cy: Float, rHub: Float) {
        fillPaint.color = Color.parseColor("#000000")
        canvas.drawCircle(cx, cy, rHub + 7f, fillPaint)

        canvas.drawCircle(cx, cy, rHub, basketballDimpleCenterPaint)

        val vignette = RadialGradient(
            cx, cy, rHub,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = vignette
        canvas.drawCircle(cx, cy, rHub, fillPaint)
        fillPaint.shader = null

        canvas.drawCircle(cx, cy, rHub + 2f, solidInnerRingPaint)
        canvas.drawCircle(cx, cy, rHub - 3f, solidInnerRingEdgePaint)
        canvas.drawCircle(cx, cy, rHub + 7f, solidInnerRingEdgePaint)

        when (centerMode) {
            0 -> drawGlossySubaruBadge(canvas, cx, cy, rHub * 0.80f, rHub * 0.44f)
            1 -> {
                textPaint.color = Color.WHITE
                textPaint.textSize = rHub * 0.65f
                canvas.drawText(currentMph.toString(), cx, cy + rHub * 0.15f, textPaint)
                drawRedOutlinedText(canvas, "MPH", cx, cy + rHub * 0.45f, rHub * 0.16f)
            }
            2 -> {
                val timeFmt = SimpleDateFormat("h:mm", Locale.US)
                textPaint.color = Color.WHITE
                textPaint.textSize = rHub * 0.48f
                canvas.drawText(timeFmt.format(Date()), cx, cy + rHub * 0.08f, textPaint)
                textPaint.color = secondaryColor
                textPaint.textSize = rHub * 0.16f
                canvas.drawText("$currentMph MPH • $compassDir", cx, cy + rHub * 0.42f, textPaint)
            }
            3 -> {
                if (!hostActivity.isCarPlayActivelyConnected()) {
                    drawGlossySubaruBadge(canvas, cx, cy - rHub * 0.06f, rHub * 0.72f, rHub * 0.39f)
                    textPaint.textSize = rHub * 0.11f
                    textPaint.color = secondaryColor
                    canvas.drawText("CARPLAY NAV STANDBY", cx, cy + rHub * 0.62f, textPaint)
                }
            }
            4 -> {
                drawCarPlaySpotifyHub(canvas, cx, cy, rHub)
            }
        }
    }

    private fun drawCarPlaySpotifyHub(canvas: Canvas, cx: Float, cy: Float, rHub: Float) {
        if (!hostActivity.isCarPlayActivelyConnected()) {
            drawGlossySubaruBadge(canvas, cx, cy - rHub * 0.06f, rHub * 0.72f, rHub * 0.39f)
            textPaint.textSize = rHub * 0.105f
            textPaint.color = spotifyGreen
            canvas.drawText("CARPLAY SPOTIFY STANDBY", cx, cy + rHub * 0.62f, textPaint)
            return
        }

        canvas.save()
        val clipPath = Path().apply { addCircle(cx, cy, rHub - 4f, Path.Direction.CW) }
        canvas.clipPath(clipPath)

        val spotGlow = RadialGradient(
            cx, cy - rHub * 0.35f, rHub * 0.95f,
            intArrayOf(Color.argb(65, 29, 185, 84), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = spotGlow
        canvas.drawCircle(cx, cy, rHub, fillPaint)
        fillPaint.shader = null

        val logoY = cy - rHub * 0.58f
        val logoR = rHub * 0.14f
        fillPaint.color = spotifyGreen
        canvas.drawCircle(cx, logoY, logoR, fillPaint)

        iconPaint.color = Color.parseColor("#08090C")
        iconPaint.strokeWidth = rHub * 0.024f
        canvas.drawArc(RectF(cx - logoR * 0.68f, logoY - logoR * 0.45f, cx + logoR * 0.68f, logoY + logoR * 0.35f), 205f, 130f, false, iconPaint)
        iconPaint.strokeWidth = rHub * 0.020f
        canvas.drawArc(RectF(cx - logoR * 0.56f, logoY - logoR * 0.15f, cx + logoR * 0.56f, logoY + logoR * 0.55f), 208f, 124f, false, iconPaint)
        iconPaint.strokeWidth = rHub * 0.017f
        canvas.drawArc(RectF(cx - logoR * 0.44f, logoY + logoR * 0.12f, cx + logoR * 0.44f, logoY + logoR * 0.72f), 212f, 116f, false, iconPaint)

        textPaint.color = spotifyGreen
        textPaint.textSize = rHub * 0.105f
        canvas.drawText("CARPLAY SPOTIFY", cx, cy - rHub * 0.32f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = rHub * 0.14f
        val displayTitle = currentTrackTitle.ifBlank { "Spotify Active" }.take(20)
        canvas.drawText(displayTitle, cx, cy - rHub * 0.10f, textPaint)

        textPaint.color = secondaryColor
        textPaint.textSize = rHub * 0.105f
        val displayArtist = currentTrackArtist.ifBlank { "iPhone CarPlay Audio" }.take(24)
        canvas.drawText(displayArtist, cx, cy + rHub * 0.08f, textPaint)

        val ctrlY = cy + rHub * 0.44f
        val btnSpacing = rHub * 0.46f
        val playBtnRadius = rHub * 0.17f
        val sideBtnRadius = rHub * 0.13f

        fillPaint.color = if (pressedZone == "SPOTIFY_PREV") podPressedColor else Color.parseColor("#1E242E")
        canvas.drawCircle(cx - btnSpacing, ctrlY, sideBtnRadius, fillPaint)
        canvas.drawCircle(cx - btnSpacing, ctrlY, sideBtnRadius, thinWhiteStrokePaint)
        drawRedOutlinedText(canvas, "|<", cx - btnSpacing, ctrlY + rHub * 0.045f, rHub * 0.12f)

        fillPaint.color = if (pressedZone == "SPOTIFY_PLAY_PAUSE") Color.parseColor("#25E065") else spotifyGreen
        canvas.drawCircle(cx, ctrlY, playBtnRadius, fillPaint)
        canvas.drawCircle(cx, ctrlY, playBtnRadius, thinWhiteStrokePaint)

        val isPlayingNow = audioManager.isMusicActive || isMediaBroadcastPlaying
        if (isPlayingNow) {
            fillPaint.color = Color.parseColor("#08090C")
            val barW = playBtnRadius * 0.22f
            val barH = playBtnRadius * 0.46f
            canvas.drawRoundRect(RectF(cx - barW * 1.6f, ctrlY - barH, cx - barW * 0.4f, ctrlY + barH), 3f, 3f, fillPaint)
            canvas.drawRoundRect(RectF(cx + barW * 0.4f, ctrlY - barH, cx + barW * 1.6f, ctrlY + barH), 3f, 3f, fillPaint)
        } else {
            val playTri = Path().apply {
                moveTo(cx - playBtnRadius * 0.25f, ctrlY - playBtnRadius * 0.42f)
                lineTo(cx + playBtnRadius * 0.45f, ctrlY)
                lineTo(cx - playBtnRadius * 0.25f, ctrlY + playBtnRadius * 0.42f)
                close()
            }
            fillPaint.color = Color.parseColor("#08090C")
            canvas.drawPath(playTri, fillPaint)
        }

        fillPaint.color = if (pressedZone == "SPOTIFY_NEXT") podPressedColor else Color.parseColor("#1E242E")
        canvas.drawCircle(cx + btnSpacing, ctrlY, sideBtnRadius, fillPaint)
        canvas.drawCircle(cx + btnSpacing, ctrlY, sideBtnRadius, thinWhiteStrokePaint)
        drawRedOutlinedText(canvas, ">|", cx + btnSpacing, ctrlY + rHub * 0.045f, rHub * 0.12f)

        canvas.restore()
    }

    private fun drawGlossySubaruBadge(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        val chromeRect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        val chromeGrad = LinearGradient(
            cx - rx, cy - ry, cx + rx, cy + ry,
            intArrayOf(
                Color.parseColor("#F0F4F8"),
                Color.parseColor("#8C96A4"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#545D6A"),
                Color.parseColor("#D8E0E8")
            ),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.shader = chromeGrad
        canvas.drawOval(chromeRect, fillPaint)
        fillPaint.shader = null

        val innerBezel = RectF(cx - rx * 0.91f, cy - ry * 0.88f, cx + rx * 0.91f, cy + ry * 0.88f)
        fillPaint.color = Color.parseColor("#182232")
        canvas.drawOval(innerBezel, fillPaint)

        val blueRect = RectF(cx - rx * 0.88f, cy - ry * 0.84f, cx + rx * 0.88f, cy + ry * 0.84f)
        val blueGrad = RadialGradient(
            cx - rx * 0.2f, cy - ry * 0.2f, rx * 0.95f,
            intArrayOf(
                Color.parseColor("#1F6AE1"),
                Color.parseColor("#0D3B8C"),
                Color.parseColor("#051942")
            ),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.shader = blueGrad
        canvas.drawOval(blueRect, fillPaint)
        fillPaint.shader = null

        val glossRect = RectF(cx - rx * 0.80f, cy - ry * 0.78f, cx + rx * 0.80f, cy + ry * 0.15f)
        fillPaint.color = Color.argb(38, 255, 255, 255)
        canvas.drawOval(glossRect, fillPaint)

        drawFourPointStar(canvas, cx - rx * 0.35f, cy - ry * 0.04f, rx * 0.42f, ry * 0.68f)
        drawFourPointStar(canvas, cx + rx * 0.06f, cy - ry * 0.22f, rx * 0.16f, ry * 0.28f)
        drawFourPointStar(canvas, cx + rx * 0.38f, cy - ry * 0.12f, rx * 0.16f, ry * 0.28f)
        drawFourPointStar(canvas, cx + rx * 0.56f, cy + ry * 0.15f, rx * 0.15f, ry * 0.26f)
        drawFourPointStar(canvas, cx + rx * 0.28f, cy + ry * 0.28f, rx * 0.16f, ry * 0.28f)
        drawFourPointStar(canvas, cx - rx * 0.02f, cy + ry * 0.34f, rx * 0.16f, ry * 0.28f)
    }

    private fun drawFourPointStar(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        val path = Path().apply {
            moveTo(x, y - ry)
            quadTo(x + rx * 0.16f, y - ry * 0.16f, x + rx, y)
            quadTo(x + rx * 0.16f, y + ry * 0.16f, x, y + ry)
            quadTo(x - rx * 0.16f, y + ry * 0.16f, x - rx, y)
            quadTo(x - rx * 0.16f, y - ry * 0.16f, x, y - ry)
            close()
        }
        fillPaint.color = Color.parseColor("#F4F8FC")
        canvas.drawPath(path, fillPaint)
    }

    // --- Vector Icon Helpers ---
    private fun drawRadioIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.8f)
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawRoundRect(rect, 6f, 6f, p)
            canvas.drawLine(cx - s * 0.7f, cy - s * 0.6f, cx + s * 0.5f, cy - s * 1.2f, p)
            canvas.drawCircle(cx + s * 0.45f, cy + s * 0.1f, s * 0.25f, p)
            canvas.drawLine(cx - s * 0.65f, cy, cx - s * 0.1f, cy, p)
        }
    }

    private fun drawCarPlayIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s, cy - s * 0.75f, cx + s, cy + s * 0.75f)
        fillPaint.color = color
        canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
        canvas.drawRoundRect(rect, 10f, 10f, thinWhiteStrokePaint)

        val playPath = Path().apply {
            moveTo(cx - s * 0.25f, cy - s * 0.35f)
            lineTo(cx + s * 0.4f, cy)
            lineTo(cx - s * 0.25f, cy + s * 0.35f)
            close()
        }
        fillPaint.color = innerRingColor
        canvas.drawPath(playPath, fillPaint)
        canvas.drawPath(playPath, thinWhiteStrokePaint)
    }

    private fun drawMusicIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawLine(cx - s * 0.4f, cy + s * 0.6f, cx - s * 0.4f, cy - s * 0.7f, p)
            canvas.drawLine(cx + s * 0.6f, cy + s * 0.4f, cx + s * 0.6f, cy - s * 0.9f, p)
            canvas.drawLine(cx - s * 0.4f, cy - s * 0.7f, cx + s * 0.6f, cy - s * 0.9f, p)
        }
        fillPaint.color = color
        canvas.drawCircle(cx - s * 0.6f, cy + s * 0.6f, s * 0.28f, fillPaint)
        canvas.drawCircle(cx - s * 0.6f, cy + s * 0.6f, s * 0.28f, thinWhiteStrokePaint)
        canvas.drawCircle(cx + s * 0.4f, cy + s * 0.4f, s * 0.28f, fillPaint)
        canvas.drawCircle(cx + s * 0.4f, cy + s * 0.4f, s * 0.28f, thinWhiteStrokePaint)
    }

    private fun drawPhoneIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f)
        canvas.drawArc(rect, 125f, 95f, false, iconWhiteOutlinePaint)
        iconPaint.color = color
        canvas.drawArc(rect, 125f, 95f, false, iconPaint)
    }

    private fun drawGlobeIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val oval = RectF(cx - s * 0.45f, cy - s * 0.85f, cx + s * 0.45f, cy + s * 0.85f)
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawCircle(cx, cy, s * 0.85f, p)
            canvas.drawLine(cx - s * 0.85f, cy, cx + s * 0.85f, cy, p)
            canvas.drawOval(oval, p)
        }
    }

    private fun drawAppsGridIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        fillPaint.color = color
        val step = s * 0.65f
        for (r in -1..1) {
            for (c in -1..1) {
                val dotX = cx + c * step
                val dotY = cy + r * step
                canvas.drawCircle(dotX, dotY, s * 0.20f, fillPaint)
                canvas.drawCircle(dotX, dotY, s * 0.20f, thinWhiteStrokePaint)
            }
        }
    }

    private fun drawNavArrowIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx, cy - s * 0.9f)
            lineTo(cx + s * 0.75f, cy + s * 0.85f)
            lineTo(cx, cy + s * 0.4f)
            lineTo(cx - s * 0.75f, cy + s * 0.85f)
            close()
        }
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, thinWhiteStrokePaint)
    }

    private fun drawBluetoothIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx - s * 0.45f, cy - s * 0.45f)
            lineTo(cx + s * 0.45f, cy + s * 0.45f)
            lineTo(cx, cy + s * 0.9f)
            lineTo(cx, cy - s * 0.9f)
            lineTo(cx + s * 0.45f, cy - s * 0.45f)
            lineTo(cx - s * 0.45f, cy + s * 0.45f)
        }
        canvas.drawPath(path, iconWhiteOutlinePaint)
        iconPaint.color = color
        canvas.drawPath(path, iconPaint)
    }

    private fun drawEqualizerIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawLine(cx - s * 0.6f, cy - s * 0.8f, cx - s * 0.6f, cy + s * 0.8f, p)
            canvas.drawLine(cx, cy - s * 0.8f, cx, cy + s * 0.8f, p)
            canvas.drawLine(cx + s * 0.6f, cy - s * 0.8f, cx + s * 0.6f, cy + s * 0.8f, p)
        }
        fillPaint.color = color
        canvas.drawCircle(cx - s * 0.6f, cy - s * 0.2f, s * 0.24f, fillPaint)
        canvas.drawCircle(cx, cy + s * 0.3f, s * 0.24f, fillPaint)
        canvas.drawCircle(cx + s * 0.6f, cy - s * 0.4f, s * 0.24f, fillPaint)
    }

    private fun drawCameraIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s * 0.9f, cy - s * 0.55f, cx + s * 0.9f, cy + s * 0.75f)
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawRoundRect(rect, 8f, 8f, p)
            canvas.drawCircle(cx, cy + s * 0.1f, s * 0.35f, p)
        }
    }

    private fun drawGaugeIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s * 0.9f, cy - s * 0.8f, cx + s * 0.9f, cy + s * 0.9f)
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawArc(rect, 150f, 240f, false, p)
            canvas.drawLine(cx, cy + s * 0.1f, cx + s * 0.5f, cy - s * 0.4f, p)
        }
    }

    private fun drawFolderIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s * 0.9f, cy - s * 0.45f, cx + s * 0.9f, cy + s * 0.75f)
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawRoundRect(rect, 6f, 6f, p)
            canvas.drawLine(cx - s * 0.8f, cy - s * 0.65f, cx - s * 0.1f, cy - s * 0.65f, p)
        }
    }

    private fun drawTopLeftClock(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        val timeFmt = SimpleDateFormat("h:mm", Locale.US)
        textPaint.textSize = h * 0.042f
        textPaint.color = secondaryColor
        canvas.drawText(timeFmt.format(Date()), cx, cy + h * 0.015f, textPaint)
    }

    private fun drawTopRightCompassAndTemp(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        val compX = cx - h * 0.088f
        val compRadius = h * 0.032f
        iconPaint.color = secondaryColor
        iconPaint.strokeWidth = 3f
        canvas.drawCircle(compX, cy, compRadius, iconPaint)
        textPaint.textSize = h * 0.025f
        textPaint.color = secondaryColor
        canvas.drawText(compassDir, compX, cy + h * 0.009f, textPaint)

        val thermX = cx - h * 0.022f
        val rBulb = h * 0.013f
        fillPaint.color = secondaryColor
        canvas.drawCircle(thermX, cy + h * 0.014f, rBulb, fillPaint)
        iconPaint.strokeWidth = 4.2f
        canvas.drawLine(thermX, cy + h * 0.010f, thermX, cy - h * 0.024f, iconPaint)
        canvas.drawLine(thermX + 4f, cy - h * 0.018f, thermX + 10f, cy - h * 0.018f, iconPaint)
        canvas.drawLine(thermX + 4f, cy - h * 0.006f, thermX + 10f, cy - h * 0.006f, iconPaint)

        textPaint.textSize = h * 0.040f
        textPaint.color = secondaryColor
        val tempStr = "${temperatureF}°"
        canvas.drawText(tempStr, cx + h * 0.052f, cy + h * 0.015f, textPaint)
    }

    private fun getWifiSignalLevel(): Int {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                if (info != null && info.networkId != -1) {
                    @Suppress("DEPRECATION")
                    WifiManager.calculateSignalLevel(info.rssi, 4)
                } else {
                    0
                }
            } else {
                0
            }
        } catch (_: Exception) {
            3
        }
    }

    private fun drawWifiSignalIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val level = getWifiSignalLevel()
        val dimColor = Color.argb(65, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))
        val baseY = cy + s * 0.55f

        fillPaint.color = if (level >= 1) secondaryColor else dimColor
        canvas.drawCircle(cx, baseY, s * 0.16f, fillPaint)

        iconPaint.strokeWidth = 3.8f
        val radii = floatArrayOf(s * 0.48f, s * 0.85f, s * 1.22f)
        for (i in radii.indices) {
            val r = radii[i]
            iconPaint.color = if (level > i) secondaryColor else dimColor
            val arcRect = RectF(cx - r, baseY - r, cx + r, baseY + r)
            canvas.drawArc(arcRect, 225f, 90f, false, iconPaint)
        }
    }

    private fun drawBluetoothStatusIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val connected = hostActivity.isBluetoothDeviceConnected()
        val dimColor = Color.argb(75, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))
        val activeColor = if (connected) secondaryColor else dimColor

        iconPaint.color = activeColor
        iconPaint.strokeWidth = 3.8f

        val btPath = Path().apply {
            moveTo(cx - s * 0.42f, cy - s * 0.42f)
            lineTo(cx + s * 0.42f, cy + s * 0.42f)
            lineTo(cx, cy + s * 0.86f)
            lineTo(cx, cy - s * 0.86f)
            lineTo(cx + s * 0.42f, cy - s * 0.42f)
            lineTo(cx - s * 0.42f, cy + s * 0.42f)
        }
        canvas.drawPath(btPath, iconPaint)

        if (connected) {
            fillPaint.color = secondaryColor
            canvas.drawCircle(cx - s * 0.65f, cy, s * 0.13f, fillPaint)
            canvas.drawCircle(cx + s * 0.65f, cy, s * 0.13f, fillPaint)
        }
    }

    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        iconPaint.color = color
        iconPaint.strokeWidth = 4.2f
        canvas.drawCircle(cx, cy, r * 0.55f, iconPaint)
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            canvas.drawLine(
                cx + r * 0.65f * cos(a).toFloat(),
                cy + r * 0.65f * sin(a).toFloat(),
                cx + r * 1.05f * cos(a).toFloat(),
                cy + r * 1.05f * sin(a).toFloat(),
                iconPaint
            )
        }
    }

    // --- Touch, Single-Tap & Long-Press Detection ---
    private var downTime = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val zone = detectZone(x, y)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                pressedZone = zone
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val isLongPress = (System.currentTimeMillis() - downTime) > 600L
                pressedZone = null
                invalidate()
                if (zone != null) {
                    handleZoneAction(zone, isLongPress)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedZone = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectZone(x: Float, y: Float): String? {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)

        val rHub = h * 0.29f
        val rInner = h * 0.465f

        if (dist <= rHub) {
            if (centerMode == 4 && hostActivity.isCarPlayActivelyConnected() && dy > rHub * 0.18f) {
                val btnSpacing = rHub * 0.46f
                return when {
                    dx < -btnSpacing * 0.5f -> "SPOTIFY_PREV"
                    dx > btnSpacing * 0.5f -> "SPOTIFY_NEXT"
                    else -> "SPOTIFY_PLAY_PAUSE"
                }
            }
            return "CENTER_HUB"
        }

        if (dist <= rInner) {
            val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360
            return when {
                angle in 222.0..318.0 -> {
                    when {
                        dx < -rHub * 0.35f -> "VOL_DOWN"
                        dx > rHub * 0.35f -> "VOL_UP"
                        else -> "DATE_TOP"
                    }
                }
                angle in 42.0..138.0 -> {
                    when {
                        dx < -rHub * 0.35f -> "MEDIA_PREV"
                        dx > rHub * 0.35f -> "MEDIA_NEXT"
                        else -> "MEDIA_INFO_DISPLAY"
                    }
                }
                dx < 0 -> {
                    when {
                        y < h * 0.39f -> "ICON_RADIO"
                        y < h * 0.61f -> "ICON_CARPLAY"
                        else -> "ICON_MUSIC"
                    }
                }
                else -> {
                    when {
                        y < h * 0.39f -> "ICON_PHONE"
                        y < h * 0.61f -> "ICON_INTERNET"
                        else -> "ICON_APPS"
                    }
                }
            }
        }

        val isLeft = x < cx
        return when {
            y < h * 0.19f -> if (isLeft) "CLOCK_TOP_LEFT" else "TEMP_TOP_RIGHT"
            y < h * 0.39f -> if (isLeft) "WING_RADIO" else "WING_PHONE"
            y < h * 0.61f -> if (isLeft) "WING_CARPLAY" else "WING_INTERNET"
            y < h * 0.81f -> if (isLeft) "WING_MUSIC" else "WING_APPS"
            else -> {
                val midOffset = (getDxInner(h * 0.905f, cy, rInner + 4f) + getDxOuter(h * 0.905f, cy, w * 0.47f, h * 0.58f)) / 2f
                if (isLeft) {
                    val botLeftCenter = cx - midOffset
                    if (x < botLeftCenter) "WIFI_BOTTOM_LEFT" else "BT_BOTTOM_LEFT"
                } else {
                    val botRightCenter = cx + midOffset
                    if (x < botRightCenter) "GEAR_SKIN" else "GEAR_HOME"
                }
            }
        }
    }

    private fun handleZoneAction(zone: String, isLongPress: Boolean) {
        when (zone) {
            "CENTER_HUB" -> {
                if (isLongPress) {
                    showCenterHubDisplayPicker()
                } else {
                    centerMode = (centerMode + 1) % 5
                    prefs.edit().putInt("center_mode", centerMode).apply()
                    hostActivity.updateCenterMapVisibility()
                    invalidate()
                }
            }
            "SPOTIFY_PREV" -> {
                if (isLongPress) showCenterHubDisplayPicker()
                else hostActivity.controlCarPlaySpotify("PREV")
            }
            "SPOTIFY_PLAY_PAUSE" -> {
                if (isLongPress) showCenterHubDisplayPicker()
                else {
                    isMediaBroadcastPlaying = !isMediaBroadcastPlaying
                    hostActivity.controlCarPlaySpotify("PLAY_PAUSE")
                    invalidate()
                }
            }
            "SPOTIFY_NEXT" -> {
                if (isLongPress) showCenterHubDisplayPicker()
                else hostActivity.controlCarPlaySpotify("NEXT")
            }
            "VOL_DOWN" -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            "VOL_UP" -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            "MEDIA_PREV" -> {
                val radioPlaying = isRadioActive && !audioManager.isMusicActive
                hostActivity.stepTrackOrRadioStation(isNext = false, isRadioMode = radioPlaying, prefs = prefs)
            }
            "MEDIA_NEXT" -> {
                val radioPlaying = isRadioActive && !audioManager.isMusicActive
                hostActivity.stepTrackOrRadioStation(isNext = true, isRadioMode = radioPlaying, prefs = prefs)
            }
            "MEDIA_INFO_DISPLAY" -> {}
            "CLOCK_TOP_LEFT" -> openRecentApps()

            "TEMP_TOP_RIGHT" -> {
                if (isLongPress) {
                    showIphoneWeatherBridgeInfoDialog()
                } else {
                    Toast.makeText(context, "Refreshing temperature (Long-press for iPhone Bridge info)...", Toast.LENGTH_SHORT).show()
                    hostActivity.refreshWeatherIfNeeded(force = true, showToast = true)
                }
            }

            "WIFI_BOTTOM_LEFT" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            "BT_BOTTOM_LEFT" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    launchAssignedApp("PHONE")
                }
            }

            "ICON_RADIO", "ICON_CARPLAY", "ICON_MUSIC", "ICON_PHONE", "ICON_INTERNET", "ICON_APPS" -> {
                val slotKey = zone.removePrefix("ICON_")
                if (isLongPress) {
                    showIconPickerDialog(slotKey)
                } else if (slotKey == "APPS") {
                    showVisualAppDrawer(assignToSlot = null)
                } else {
                    handleSlotAudioModeSwitch(slotKey)
                    launchAssignedApp(slotKey)
                }
            }

            "WING_RADIO", "WING_CARPLAY", "WING_MUSIC", "WING_PHONE", "WING_INTERNET", "WING_APPS" -> {
                val slotKey = zone.removePrefix("WING_")
                if (isLongPress) {
                    showRenameAndAssignDialog(slotKey)
                } else if (slotKey == "APPS") {
                    showVisualAppDrawer(assignToSlot = null)
                } else {
                    handleSlotAudioModeSwitch(slotKey)
                    launchAssignedApp(slotKey)
                }
            }

            "GEAR_SKIN" -> showSkinCustomizerDialog()
            "GEAR_HOME" -> showEscapeHatchDialog()
        }
    }

    private fun showIphoneWeatherBridgeInfoDialog() {
        val bridgeUrls = hostActivity.getLocalBridgeIpAddresses()
        val msg = "To feed live iPhone Weather to this head unit without Wi-Fi:\n\n" +
                "1. Open the 'Shortcuts' app on the iPhone -> Automation -> New Automation -> CarPlay -> 'When CarPlay Connects' (Run Immediately).\n\n" +
                "2. Add action: 'Get Current Weather at Current Location'.\n\n" +
                "3. Add action: 'Get Contents of URL' and use this local bridge URL (append the Weather 'Temperature' variable at the end):\n\n" +
                "$bridgeUrls[Temperature]"

        AlertDialog.Builder(context)
            .setTitle("iPhone CarPlay Weather Bridge")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showCenterHubDisplayPicker() {
        val modes = arrayOf(
            "1. Subaru Emblem (Default)",
            "2. Live GPS Speedometer (MPH)",
            "3. Digital Clock & Compass",
            "4. CarPlay Center Circle Map (Subaru Standby when disconnected)",
            "5. CarPlay Spotify Hub (Subaru Standby when disconnected)",
            "Open Stock Launcher / Home Settings"
        )
        AlertDialog.Builder(context)
            .setTitle("Center Circle Display Mode (5 Options)")
            .setItems(modes) { _, which ->
                if (which in 0..4) {
                    centerMode = which
                    prefs.edit().putInt("center_mode", centerMode).apply()
                    hostActivity.updateCenterMapVisibility()
                    invalidate()
                } else {
                    LauncherUtils.openHomeSettings(context)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleSlotAudioModeSwitch(slotKey: String) {
        if (slotKey == "RADIO") {
            isRadioActive = true
            isMediaBroadcastPlaying = false
            prefs.edit().putBoolean("is_radio_active", true).apply()
            val polled = hostActivity.pollSystemRadioFrequency(prefs)
            if (polled.isNotEmpty()) {
                currentRadioText = polled
                prefs.edit().putString("last_radio_text", polled).apply()
            } else {
                val saved = prefs.getString("last_radio_text", "").orEmpty()
                if (saved.isNotEmpty() && saved != "FM Radio Tuner Active") {
                    currentRadioText = saved
                }
            }
            invalidate()

            if (!hostActivity.isAccessibilityReaderEnabled() && !prefs.getBoolean("asked_a11y", false)) {
                prefs.edit().putBoolean("asked_a11y", true).apply()
                Toast.makeText(
                    context,
                    "Tip: Enable 'Eye of Sauron' in Accessibility Settings (via Right Gear menu) to auto-read Radio station text!",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (slotKey == "MUSIC" || slotKey == "CARPLAY") {
            isRadioActive = false
            prefs.edit().putBoolean("is_radio_active", false).apply()
            invalidate()
        }
    }

    private fun openRecentApps() {
        try {
            context.sendBroadcast(Intent("com.android.systemui.recents.TOGGLE_RECENTS"))
            context.sendBroadcast(Intent("com.xyauto.broadcast.RECENTS"))
        } catch (_: Exception) {}

        val recentsComponents = listOf(
            ComponentName("com.android.systemui", "com.android.systemui.recents.RecentsActivity"),
            ComponentName("com.android.systemui", "com.android.quickstep.RecentsActivity"),
            ComponentName("com.android.launcher3", "com.android.quickstep.RecentsActivity")
        )
        for (comp in recentsComponents) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = comp
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        try {
            Runtime.getRuntime().exec("input keyevent 187")
        } catch (_: Exception) {}
    }

    private fun showIconPickerDialog(slotKey: String) {
        AlertDialog.Builder(context)
            .setTitle("Select Icon for $slotKey")
            .setItems(iconTypeNames) { _, which ->
                prefs.edit().putInt("icon_$slotKey", which).apply()
                invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameAndAssignDialog(slotKey: String) {
        val currentLabel = prefs.getString("label_$slotKey", slotKey) ?: slotKey
        val input = EditText(context).apply {
            setText(currentLabel)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSelection(text.length)
            setPadding(45, 35, 45, 35)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Customize $slotKey Button")
            .setMessage("Enter custom text label or choose which app this button opens:")
            .setView(input)
            .setPositiveButton("Save Text") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    prefs.edit().putString("label_$slotKey", newText).apply()
                    invalidate()
                }
            }
            .setNeutralButton("Save & Pick App") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    prefs.edit().putString("label_$slotKey", newText).apply()
                    invalidate()
                }
                showVisualAppDrawer(assignToSlot = slotKey)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150L)
    }

    private fun renderUncroppedSquareIcon(drawable: Drawable, boxSizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(boxSizePx, boxSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = (boxSizePx * 0.08f).toInt()
        val avail = boxSizePx - pad * 2

        val intrW = drawable.intrinsicWidth.takeIf { it > 0 } ?: avail
        val intrH = drawable.intrinsicHeight.takeIf { it > 0 } ?: avail
        val scale = min(avail.toFloat() / intrW.toFloat(), avail.toFloat() / intrH.toFloat())

        val drawW = (intrW * scale).toInt()
        val drawH = (intrH * scale).toInt()
        val left = (boxSizePx - drawW) / 2
        val top = (boxSizePx - drawH) / 2

        drawable.setBounds(left, top, left + drawW, top + drawH)
        drawable.draw(canvas)
        return bmp
    }

    private fun showVisualAppDrawer(assignToSlot: String?) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(mainIntent, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }

        val screenW = width.takeIf { it > 0 } ?: 1024
        val screenH = height.takeIf { it > 0 } ?: 600
        val iconBoxSize = (screenH * 0.135f).toInt().coerceIn(72, 110)

        val gridView = GridView(context).apply {
            numColumns = 5
            verticalSpacing = 24
            horizontalSpacing = 24
            setPadding(36, 28, 36, 28)
            clipToPadding = false
            clipChildren = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(Color.parseColor("#141820"))
        }

        lateinit var alertDialog: AlertDialog

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tileBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f
                    setColor(Color.parseColor("#222733"))
                    setStroke(2, Color.parseColor("#353C4B"))
                }

                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(16, 18, 16, 16)
                    clipChildren = false
                    clipToPadding = false
                    background = tileBg
                }

                val appInfo = apps[position]
                val uncroppedBmp = renderUncroppedSquareIcon(appInfo.loadIcon(pm), iconBoxSize)

                val iconView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconBoxSize, iconBoxSize)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageBitmap(uncroppedBmp)
                }

                val labelView = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = appInfo.loadLabel(pm).toString()
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(4, 10, 4, 0)
                }

                itemLayout.addView(iconView)
                itemLayout.addView(labelView)
                return itemLayout
            }
        }

        gridView.adapter = adapter
        gridView.setOnItemClickListener { _, _, position, _ ->
            val actInfo = apps[position].activityInfo
            val chosenPkg = actInfo.packageName
            val chosenCls = actInfo.name
            val chosenName = apps[position].loadLabel(pm).toString()
            alertDialog.dismiss()

            val exactIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(chosenPkg, chosenCls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

            if (assignToSlot != null) {
                prefs.edit()
                    .putString("pkg_$assignToSlot", chosenPkg)
                    .putString("cls_$assignToSlot", chosenCls)
                    .apply()
                Toast.makeText(context, "$assignToSlot assigned to $chosenName", Toast.LENGTH_SHORT).show()
                try {
                    context.startActivity(exactIntent)
                } catch (_: Exception) {
                    pm.getLaunchIntentForPackage(chosenPkg)?.let { context.startActivity(it) }
                }
            } else {
                try {
                    context.startActivity(exactIntent)
                } catch (_: Exception) {
                    pm.getLaunchIntentForPackage(chosenPkg)?.let { context.startActivity(it) }
                }
            }
        }

        val title = if (assignToSlot != null) "Select App for $assignToSlot" else "All Applications"
        alertDialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(gridView)
            .setNegativeButton("Close", null)
            .create()

        alertDialog.show()
        alertDialog.window?.setLayout(
            (screenW * 0.92f).toInt(),
            (screenH * 0.86f).toInt()
        )
    }

    private fun launchAssignedApp(slotKey: String) {
        val pkg = prefs.getString("pkg_$slotKey", null)
        val cls = prefs.getString("cls_$slotKey", null)

        if (!pkg.isNullOrBlank() && !cls.isNullOrBlank()) {
            try {
                val exactIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                context.startActivity(exactIntent)
                return
            } catch (_: Exception) {}
        }

        if (!pkg.isNullOrBlank()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return
            }
        }

        showVisualAppDrawer(assignToSlot = slotKey)
    }

    private fun showSkinCustomizerDialog() {
        val themes = arrayOf(
            "Legacy Default (Original Installation Theme)",
            "Deep Ruby Red & Blue",
            "Subaru World Rally Blue & Gold",
            "Subaru Wilderness Gold & Blue",
            "Cyber Ice Cyan & White",
            "Emerald Matrix Green"
        )
        AlertDialog.Builder(context)
            .setTitle("Customize Launcher Theme")
            .setItems(themes) { _, which ->
                val (pri, sec) = when (which) {
                    0 -> defaultPrimaryRed to defaultSecondaryBlue
                    1 -> Color.parseColor("#C8102E") to Color.parseColor("#4A90E2")
                    2 -> Color.parseColor("#2B7DE9") to Color.parseColor("#E5B842")
                    3 -> Color.parseColor("#E09F24") to Color.parseColor("#4A90E2")
                    4 -> Color.parseColor("#00BCD4") to Color.parseColor("#FFFFFF")
                    else -> Color.parseColor("#2ECC71") to Color.parseColor("#4A90E2")
                }
                primaryColor = pri
                secondaryColor = sec
                innerRingColor = defaultInnerRingGray
                wingBaseColor = defaultWingBaseGray

                prefs.edit()
                    .putInt("primary_color_v3", pri)
                    .putInt("secondary_color_v3", sec)
                    .putInt("inner_ring_color_v3", innerRingColor)
                    .putInt("wing_base_color_v3", wingBaseColor)
                    .apply()
                invalidate()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEscapeHatchDialog() {
        val readerStatus = if (hostActivity.isAccessibilityReaderEnabled()) "ENABLED" else "TAP TO ENABLE"
        val cpSimulated = if (hostActivity.carPlaySessionActive) "ACTIVE (Tap to set Standby)" else "STANDBY (Tap to test Map/Spotify)"
        val options = arrayOf(
            "Enable Radio Screen Reader ($readerStatus)",
            "Center Circle Mode (Subaru / MPH / Clock / Map / Spotify)",
            "Toggle CarPlay Active State ($cpSimulated)",
            "iPhone Weather Bridge Setup Info (No Wi-Fi Needed)",
            "Switch / Revert to Stock XYAuto Launcher",
            "Open Android System Settings",
            "Radio & App Inspector (Diagnostics)",
            "Reset All Custom Labels, Icons & Shortcuts"
        )
        AlertDialog.Builder(context)
            .setTitle("Eye of Sauron Car Launcher Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            Toast.makeText(context, "Turn ON 'Eye of Sauron Car Launcher' to read Radio station numbers automatically!", Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    1 -> showCenterHubDisplayPicker()
                    2 -> {
                        hostActivity.carPlaySessionActive = !hostActivity.carPlaySessionActive
                        hostActivity.updateCenterMapVisibility()
                        invalidate()
                    }
                    3 -> showIphoneWeatherBridgeInfoDialog()
                    4 -> LauncherUtils.openHomeSettings(context)
                    5 -> context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    6 -> showRadioDiagnosticDialog()
                    7 -> {
                        for (k in wingKeys) {
                            prefs.edit()
                                .remove("pkg_$k")
                                .remove("cls_$k")
                                .remove("label_$k")
                                .remove("icon_$k")
                                .apply()
                        }
                        primaryColor = defaultPrimaryRed
                        secondaryColor = defaultSecondaryBlue
                        innerRingColor = defaultInnerRingGray
                        wingBaseColor = defaultWingBaseGray
                        prefs.edit()
                            .putInt("primary_color_v3", defaultPrimaryRed)
                            .putInt("secondary_color_v3", defaultSecondaryBlue)
                            .apply()
                        invalidate()
                        Toast.makeText(context, "All customizations reset to Legacy Default", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRadioDiagnosticDialog() {
        val readerEnabled = hostActivity.isAccessibilityReaderEnabled()
        val mappedRadioPkg = prefs.getString("pkg_RADIO", "Not assigned yet")
        val mappedCarPlayPkg = prefs.getString("pkg_CARPLAY", "Not assigned yet")
        val polled = hostActivity.pollSystemRadioFrequency(prefs).ifEmpty { "None in getprop/SharedPrefs" }
        val logs = if (hostActivity.recentBroadcastLog.isEmpty()) {
            "No broadcasts captured yet."
        } else {
            hostActivity.recentBroadcastLog.joinToString("\n\n")
        }

        val message = "Accessibility Radio Reader: ${if (readerEnabled) "ENABLED" else "DISABLED (Enable in Gear Menu #1)"}\n" +
                "Mapped RADIO App: $mappedRadioPkg\n" +
                "Mapped CARPLAY App: $mappedCarPlayPkg\n" +
                "Polled Frequency: $polled\n" +
                "Active Radio Text: ${currentRadioText.ifEmpty { "Empty" }}\n\n" +
                "Recent Broadcasts:\n$logs"

        AlertDialog.Builder(context)
            .setTitle("Radio & App Inspector")
            .setMessage(message)
            .setPositiveButton("Enable Radio Reader") { _, _ ->
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
