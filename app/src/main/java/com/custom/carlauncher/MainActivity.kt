package com.custom.carlauncher

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var mediaHelper: MediaControlHelper
    private lateinit var locationManager: LocationManager
    private lateinit var dashboardView: AgamaDashboardView

    private var lastWeatherFetchTime = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // Diagnostic log of the last incoming broadcasts to help identify proprietary XYAuto radio intents
    val recentBroadcastLog = LinkedList<String>()

    private val mediaAndRadioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action.orEmpty()
            val bundle = intent.extras

            // Record in diagnostic sniffer log (ignore standard battery/time ticks)
            if (!action.contains("BATTERY") && !action.contains("TIME_TICK")) {
                val keysSummary = bundle?.keySet()?.joinToString(", ") { k -> "$k=${bundle.get(k)}" } ?: "no-extras"
                val entry = "[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $action ($keysSummary)"
                recentBroadcastLog.addFirst(entry)
                while (recentBroadcastLog.size > 18) recentBroadcastLog.removeLast()
            }

            // 1. Check if the broadcast is a Radio power-off / stop signal
            if (action.contains("radio", ignoreCase = true) &&
                (action.contains("stop", ignoreCase = true) ||
                        action.contains("close", ignoreCase = true) ||
                        action.contains("exit", ignoreCase = true) ||
                        intent.getBooleanExtra("playing", true) == false ||
                        intent.getIntExtra("state", 1) == 0)
            ) {
                dashboardView.setRadioInactive()
                return
            }

            // 2. Check if broadcast is from the Head Unit's FM/AM Radio app or MCU
            val isRadioIntent = action.contains("radio", ignoreCase = true) ||
                    action.contains("fm", ignoreCase = true) ||
                    action.contains("mcu", ignoreCase = true) ||
                    action.contains("tw.", ignoreCase = true) ||
                    hasAnyKey(bundle, "freq", "frequency", "radio_freq", "cur_freq", "channel_freq", "fm_freq", "rds", "ps", "pty")

            if (isRadioIntent) {
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

                // Fallback: scan any numeric/frequency-looking value inside the bundle
                if (parts.isEmpty() && bundle != null) {
                    for (key in bundle.keySet()) {
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

            // 3. Otherwise process as Music / Podcast / Media App Metadata
            val track = extractFirstString(
                bundle,
                "track", "title", "music_title", "song", "episode", "name", "android.media.metadata.TITLE"
            )
            val artist = extractFirstString(
                bundle,
                "artist", "author", "music_artist", "writer", "podcast", "show", "android.media.metadata.ARTIST"
            )
            val album = extractFirstString(
                bundle,
                "album", "music_album", "series", "station", "android.media.metadata.ALBUM"
            )

            val isPlaying = when {
                action.contains("playbackcomplete", ignoreCase = true) -> false
                action.contains("pause", ignoreCase = true) -> false
                bundle?.containsKey("playing") == true -> bundle.getBoolean("playing", true)
                bundle?.containsKey("playstate") == true -> {
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
            // e.g. 9230 -> 92.3 FM
            num in 8700.0..10850.0 -> String.format(Locale.US, "%.1f %s", num / 100.0, band.ifEmpty { "FM" })
            // e.g. 92300 -> 92.3 FM
            num in 87000.0..108500.0 -> String.format(Locale.US, "%.1f %s", num / 1000.0, band.ifEmpty { "FM" })
            // e.g. 923 -> 92.3 FM
            num in 875.0..1085.0 -> String.format(Locale.US, "%.1f %s", num / 10.0, band.ifEmpty { "FM" })
            // e.g. 92.3 -> 92.3 FM
            num in 87.0..108.5 -> String.format(Locale.US, "%.1f %s", num, band.ifEmpty { "FM" })
            // AM Band
            num in 500.0..1750.0 -> String.format(Locale.US, "%.0f AM", num)
            else -> ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaHelper = MediaControlHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        dashboardView = AgamaDashboardView(this, mediaHelper)
        setContentView(dashboardView)

        registerMediaAndRadioBroadcasts()
        startGpsAndWeatherUpdates()
    }

    override fun onResume() {
        super.onResume()
        dashboardView.syncAudioAndRadioState()
        refreshWeatherIfNeeded(force = false)
    }

    private fun registerMediaAndRadioBroadcasts() {
        val filter = IntentFilter().apply {
            // Standard Android, Music, and Podcast Player Broadcasts
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

            // XYAuto / Allwinner / Topway / FYT / Microntek Radio & MCU Broadcasts
            addAction("com.microntek.music.toast")
            addAction("com.microntek.music.report")
            addAction("com.xyauto.music.metachanged")
            addAction("com.xyauto.broadcast.MUSIC_INFO")
            addAction("com.xyauto.broadcast.RADIO_INFO")
            addAction("com.xyauto.radio.metachanged")
            addAction("com.xyauto.broadcast.radio")
            addAction("com.xyauto.action.RADIO_INFO")
            addAction("com.xyauto.Radio.Action")
            addAction("com.SwcApplication.radio")
            addAction("com.tw.radio.info")
            addAction("com.tw.broadcast.RADIO")
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
            addAction("com. ATC.radio.info")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaAndRadioReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mediaAndRadioReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mediaAndRadioReceiver)
        } catch (_: Exception) {}
    }

    private fun startGpsAndWeatherUpdates() {
        try {
            // Check last known GPS location immediately so temperature can fetch right away at startup
            val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                lastLat = lastLoc.latitude
                lastLon = lastLoc.longitude
                refreshWeatherIfNeeded(force = true)
            }

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0.5f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lastLat = location.latitude
                        lastLon = location.longitude

                        val mph = if (location.hasSpeed()) (location.speed * 2.23694f).toInt() else 0

                        // #3: Only update forward-facing car direction when moving (> 2 MPH)
                        // so stationary GPS jitter while parked doesn't spin the compass
                        val forwardBearing = if (location.hasBearing() && mph >= 2) {
                            location.bearing
                        } else {
                            null
                        }

                        dashboardView.updateGpsData(mph, forwardBearing)
                        refreshWeatherIfNeeded(force = false)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            )
        } catch (_: SecurityException) {}
    }

    /**
     * #2: Automatically fetches real-time local outdoor temperature (°F) from Open-Meteo API
     * using the car's live GPS coordinates (or IP geolocation fallback if GPS isn't locked yet).
     */
    fun refreshWeatherIfNeeded(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastWeatherFetchTime) < 15 * 60 * 1000L) return
        lastWeatherFetchTime = now

        Thread {
            try {
                var lat = lastLat
                var lon = lastLon

                // If GPS hasn't locked onto satellites yet, fall back to Wi-Fi IP coordinates
                if (lat == null || lon == null) {
                    val ipConn = URL("https://ipapi.co/json/").openConnection() as HttpURLConnection
                    ipConn.connectTimeout = 4000
                    ipConn.readTimeout = 4000
                    if (ipConn.responseCode == 200) {
                        val ipJson = JSONObject(BufferedReader(InputStreamReader(ipConn.inputStream)).readText())
                        if (ipJson.has("latitude") && ipJson.has("longitude")) {
                            lat = ipJson.getDouble("latitude")
                            lon = ipJson.getDouble("longitude")
                        }
                    }
                }

                if (lat != null && lon != null) {
                    val weatherUrl =
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&temperature_unit=fahrenheit"
                    val conn = URL(weatherUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val json = JSONObject(response)
                        val current = json.getJSONObject("current_weather")
                        val tempF = current.getDouble("temperature").roundToInt()
                        runOnUiThread {
                            dashboardView.updateLiveTemperature(tempF)
                        }
                    }
                }
            } catch (_: Exception) {
                // Keeps last cached temperature if Wi-Fi/hotspot is offline
            }
        }.start()
    }

    /**
     * #4: Polls XYAuto System Properties, Settings.System, and SharedPreferences for active Radio Frequency
     * in case the XYAuto Radio app writes frequency to system memory instead of standard broadcasts.
     */
    fun pollSystemRadioFrequency(): String {
        // 1. Check Android Settings.System keys commonly written by XYAuto / Allwinner / MTC firmwares
        val sysKeys = arrayOf(
            "radio_freq", "cur_freq", "fm_freq", "xy_radio_freq",
            " radio_frequency", "RadioFreq", "mcu_radio_freq", "rds_ps"
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

        // 2. Check Android OS SystemProperties (getprop) for MCU radio frequency variables
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

        // 3. Check if a known Radio package is running in the background
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val procs = am.runningAppProcesses.orEmpty()
            for (p in procs) {
                val pkg = p.processName.lowercase()
                if (pkg.contains("radio") || pkg.contains("fmradio")) {
                    // Try reading shared preferences of the radio package if accessible
                    val otherCtx = createPackageContext(p.processName, Context.CONTEXT_IGNORE_SECURITY)
                    val prefNames = arrayOf("radio", "fmradio", "config", "settings", "${p.processName}_preferences")
                    for (pf in prefNames) {
                        val sp = otherCtx.getSharedPreferences(pf, Context.MODE_PRIVATE)
                        for ((_, value) in sp.all) {
                            val str = value?.toString().orEmpty()
                            val formatted = formatRadioFrequency(str, "FM")
                            if (formatted.isNotEmpty()) return formatted
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return ""
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
    private val mainActivity: MainActivity,
    private val mediaHelper: MediaControlHelper
) : View(mainActivity) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agama_subaru_prefs", Context.MODE_PRIVATE)
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // #1: Area outside the outermost curved dashes is pure Midnight Black (#000000)
    private val bgMidnightBlack = Color.parseColor("#000000")

    // #5: Legacy Default Installation Theme Colors
    private val defaultPrimaryRed = Color.parseColor("#E6192E")
    private val defaultSecondaryBlue = Color.parseColor("#4A90E2")
    private val defaultInnerRingGray = Color.parseColor("#5E6578")
    private val defaultWingBaseGray = Color.parseColor("#14171E")

    private var primaryColor = prefs.getInt("primary_color_v3", defaultPrimaryRed)
    private var secondaryColor = prefs.getInt("secondary_color_v3", defaultSecondaryBlue)
    private var innerRingColor = prefs.getInt("inner_ring_color_v3", defaultInnerRingGray)
    private var wingBaseColor = prefs.getInt("wing_base_color_v3", defaultWingBaseGray)

    private var centerMode = prefs.getInt("center_mode", 0)
    private var currentMph = 0
    private var compassDir = prefs.getString("last_compass", "N") ?: "N"
    private var temperatureF = prefs.getInt("temp_f", 59)

    // Audio & Radio State Tracking
    private var isMediaBroadcastPlaying = false
    private var isRadioActive = prefs.getBoolean("is_radio_active", false)
    private var currentMediaText: String = prefs.getString("last_media_text", "") ?: ""
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

            // Periodically poll system radio properties every ~2 seconds when radio is active
            pollCounter++
            if (isRadioActive && pollCounter % 45 == 0) {
                val polledFreq = mainActivity.pollSystemRadioFrequency()
                if (polledFreq.isNotEmpty()) {
                    currentRadioText = polledFreq
                    prefs.edit().putString("last_radio_text", currentRadioText).apply()
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
        val sysFreq = mainActivity.pollSystemRadioFrequency()
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

    // #3: Updates forward-facing car direction from GPS movement vector
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

    fun updateMediaInfo(track: String, artist: String, album: String, playing: Boolean) {
        isMediaBroadcastPlaying = playing
        if (playing) {
            isRadioActive = false
            prefs.edit().putBoolean("is_radio_active", false).apply()
        }

        val parts = mutableListOf<String>()
        if (track.isNotEmpty()) parts.add(track)
        if (artist.isNotEmpty() && !artist.equals("<unknown>", ignoreCase = true)) parts.add(artist)
        if (album.isNotEmpty() && !album.equals("<unknown>", ignoreCase = true) && !album.equals(track, ignoreCase = true)) {
            parts.add(album)
        }

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

        // #1: Entire screen outside the outermost curved dashes is pure Midnight Black (#000000)
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
            if (zone.startsWith("WING_") || zone in listOf("CLOCK_TOP_LEFT", "TEMP_TOP_RIGHT", "WIFI_BOTTOM_LEFT", "GEAR_SKIN", "GEAR_HOME")) {
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
                val isLeft = zone in listOf("CLOCK_TOP_LEFT", "WING_RADIO", "WING_CARPLAY", "WING_MUSIC", "WIFI_BOTTOM_LEFT")
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
        drawWifiSignalIcon(canvas, cx - botCornerMidOffset, rowCenters[4], h * 0.035f)

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

        // Strictly blank when neither radio nor any media app is playing
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
        }
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
                val info = wm.connectionInfo
                if (info != null && info.networkId != -1) {
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

        iconPaint.strokeWidth = 4.0f
        val radii = floatArrayOf(s * 0.50f, s * 0.88f, s * 1.26f)
        for (i in radii.indices) {
            val r = radii[i]
            iconPaint.color = if (level > i) secondaryColor else dimColor
            val arcRect = RectF(cx - r, baseY - r, cx + r, baseY + r)
            canvas.drawArc(arcRect, 225f, 90f, false, iconPaint)
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

        if (dist <= rHub) return "CENTER_HUB"

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
                if (isLeft) "WIFI_BOTTOM_LEFT"
                else {
                    val botCornerMid = cx + (getDxInner(h * 0.905f, cy, rInner + 4f) + getDxOuter(h * 0.905f, cy, w * 0.47f, h * 0.58f)) / 2f
                    if (x < botCornerMid) "GEAR_SKIN" else "GEAR_HOME"
                }
            }
        }
    }

    private fun handleZoneAction(zone: String, isLongPress: Boolean) {
        when (zone) {
            "CENTER_HUB" -> {
                if (isLongPress) {
                    LauncherUtils.openHomeSettings(context)
                } else {
                    centerMode = (centerMode + 1) % 3
                    prefs.edit().putInt("center_mode", centerMode).apply()
                    invalidate()
                }
            }
            "VOL_DOWN" -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            "VOL_UP" -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            "MEDIA_PREV" -> mediaHelper.prevTrack()
            "MEDIA_NEXT" -> mediaHelper.nextTrack()

            // Strictly read-only display so audio/radio is never paused on tap
            "MEDIA_INFO_DISPLAY" -> {}

            "CLOCK_TOP_LEFT" -> openRecentApps()

            // #2: Tapping temperature triggers an immediate live GPS weather refresh (no manual popup)
            "TEMP_TOP_RIGHT" -> {
                Toast.makeText(context, "Refreshing live outdoor temperature...", Toast.LENGTH_SHORT).show()
                mainActivity.refreshWeatherIfNeeded(force = true)
            }

            "WIFI_BOTTOM_LEFT" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun handleSlotAudioModeSwitch(slotKey: String) {
        if (slotKey == "RADIO") {
            isRadioActive = true
            isMediaBroadcastPlaying = false
            prefs.edit().putBoolean("is_radio_active", true).apply()
            val polled = mainActivity.pollSystemRadioFrequency()
            if (polled.isNotEmpty()) {
                currentRadioText = polled
                prefs.edit().putString("last_radio_text", polled).apply()
            } else if (currentRadioText.isBlank()) {
                currentRadioText = "FM Radio Tuner Active"
            }
            invalidate()
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
            val chosenPkg = apps[position].activityInfo.packageName
            val chosenName = apps[position].loadLabel(pm).toString()
            alertDialog.dismiss()

            if (assignToSlot != null) {
                prefs.edit().putString("pkg_$assignToSlot", chosenPkg).apply()
                Toast.makeText(context, "$assignToSlot assigned to $chosenName", Toast.LENGTH_SHORT).show()
                pm.getLaunchIntentForPackage(chosenPkg)?.let { context.startActivity(it) }
            } else {
                pm.getLaunchIntentForPackage(chosenPkg)?.let { context.startActivity(it) }
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
        if (pkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return
            }
        }
        showVisualAppDrawer(assignToSlot = slotKey)
    }

    // #5: Added "Legacy Default (Original Installation Theme)" at the top of the Theme Picker
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
        val options = arrayOf(
            "Switch / Revert to Stock XYAuto Launcher",
            "Open Android System Settings",
            "Radio Broadcast Sniffer (Diagnostics)",
            "Reset All Custom Labels, Icons & Shortcuts"
        )
        AlertDialog.Builder(context)
            .setTitle("System & Launcher Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> LauncherUtils.openHomeSettings(context)
                    1 -> context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    2 -> showRadioDiagnosticDialog()
                    3 -> {
                        for (k in wingKeys) {
                            prefs.edit()
                                .remove("pkg_$k")
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
        val polled = mainActivity.pollSystemRadioFrequency().ifEmpty { "None detected in getprop/Settings" }
        val logs = if (mainActivity.recentBroadcastLog.isEmpty()) {
            "No media/radio broadcasts captured yet."
        } else {
            mainActivity.recentBroadcastLog.joinToString("\n\n")
        }

        val message = "Polled System Frequency: $polled\n" +
                "Active Radio Text: ${currentRadioText.ifEmpty { "Empty" }}\n\n" +
                "Recent Broadcasts:\n$logs"

        AlertDialog.Builder(context)
            .setTitle("Radio Broadcast Sniffer")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }
}
