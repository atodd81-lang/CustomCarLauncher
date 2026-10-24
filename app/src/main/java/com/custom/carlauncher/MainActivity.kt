package com.custom.carlauncher

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var mediaHelper: MediaControlHelper
    private lateinit var locationManager: LocationManager
    private lateinit var dashboardView: AgamaDashboardView

    // Captures track metadata from standard Android & car music players without needing Notification Access
    private val mediaMetadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val track = intent.getStringExtra("track")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("music_title")
            val artist = intent.getStringExtra("artist")
                ?: intent.getStringExtra("author")
                ?: intent.getStringExtra("music_artist")
            val playing = if (intent.hasExtra("playing")) {
                intent.getBooleanExtra("playing", true)
            } else if (intent.hasExtra("playstate")) {
                intent.getBooleanExtra("playstate", true)
            } else {
                true
            }
            dashboardView.updateMediaInfo(artist, track, playing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaHelper = MediaControlHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        dashboardView = AgamaDashboardView(this, mediaHelper)
        setContentView(dashboardView)

        registerMediaBroadcasts()
        startGpsUpdates()
    }

    private fun registerMediaBroadcasts() {
        val filter = IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.android.music.playbackcomplete")
            addAction("com.spotify.music.metadatachanged")
            addAction("com.spotify.music.playbackstatechanged")
            addAction("com.maxmpz.audioplayer.TRACK_CHANGED")
            addAction("com.maxmpz.audioplayer.STATUS_CHANGED")
            addAction("com.amazon.mp3.metachanged")
            addAction("fm.last.android.metachanged")
            addAction("com.nullsoft.winamp.metachanged")
            addAction("com.miui.player.metachanged")
            addAction("com.andrew.apollo.metachanged")
            addAction("com.microntek.music.toast")
            addAction("com.xyauto.music.metachanged")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaMetadataReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mediaMetadataReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mediaMetadataReceiver)
        } catch (_: Exception) {}
    }

    private fun startGpsUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0.5f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val mph = if (location.hasSpeed()) (location.speed * 2.23694f).toInt() else 0
                        val bearing = if (location.hasBearing()) location.bearing else null
                        dashboardView.updateGpsData(mph, bearing)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            )
        } catch (_: SecurityException) {
            // Standard fallback if GPS permission is not yet toggled
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                mediaHelper.playPause()
                dashboardView.togglePlayState()
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
    context: Context,
    private val mediaHelper: MediaControlHelper
) : View(context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agama_subaru_prefs", Context.MODE_PRIVATE)
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Colors: True Crimson Red (#E6192E), Blue (#4A90E2)
    // #2: Inner icon ring 35% lighter gray (#5E6578)
    // #4: Outer word wing 5% lighter gray (#2D323E)
    private var primaryColor = prefs.getInt("primary_color_v3", Color.parseColor("#E6192E"))
    private var secondaryColor = prefs.getInt("secondary_color_v3", Color.parseColor("#4A90E2"))
    private val bgDark = Color.parseColor("#111318")
    private val podColor = Color.parseColor("#2D323E")
    private val podPressedColor = Color.parseColor("#3E4556")
    private val innerRingColor = Color.parseColor("#5E6578")
    private val hubBgColor = Color.parseColor("#16181F")

    // State Variables
    private var centerMode = prefs.getInt("center_mode", 0)
    private var currentMph = 0
    private var compassDir = prefs.getString("last_compass", "N") ?: "N"
    private var temperatureF = prefs.getInt("temp_f", 59)
    private var isPlayingState = false
    private var currentSongInfo: String = ""
    private var scrollXOffset = 0f
    private var pressedZone: String? = null

    private val wingKeys = listOf("RADIO", "CARPLAY", "MUSIC", "PHONE", "INTERNET", "APPS")

    // Standard Selectable Icons (#3)
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

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        color = Color.parseColor("#111318")
        strokeCap = Paint.Cap.BUTT
    }

    // #8: Slightly thicker stitching-style dotted lines
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

    // #12: Slightly thinner white border on text and icons
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

    // Animation ticker for scrolling song info and clock updates
    private val tickerRunnable = object : Runnable {
        override fun run() {
            val activeAudio = audioManager.isMusicActive || isPlayingState
            if (activeAudio) {
                scrollXOffset += 3.2f
                invalidate()
                postDelayed(this, 40L)
            } else {
                invalidate()
                postDelayed(this, 5000L)
            }
        }
    }

    init {
        post(tickerRunnable)
    }

    fun updateGpsData(mph: Int, bearing: Float?) {
        currentMph = mph
        if (bearing != null) {
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val norm = ((bearing % 360f) + 360f) % 360f
            val idx = ((norm + 22.5f) / 45f).toInt() % 8
            compassDir = dirs[idx]
            prefs.edit().putString("last_compass", compassDir).apply()
        }
        invalidate()
    }

    fun updateMediaInfo(artist: String?, track: String?, playing: Boolean) {
        isPlayingState = playing
        val cleanTrack = track?.trim().orEmpty()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTrack.isNotEmpty() || cleanArtist.isNotEmpty()) {
            currentSongInfo = if (cleanArtist.isNotEmpty() && cleanTrack.isNotEmpty()) {
                "$cleanArtist — $cleanTrack"
            } else {
                cleanTrack.ifEmpty { cleanArtist }
            }
        }
        invalidate()
    }

    fun togglePlayState() {
        isPlayingState = !isPlayingState
        invalidate()
    }

    private fun drawRedOutlinedText(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
        textWhiteOutlinePaint.textSize = size
        textPaint.textSize = size
        textPaint.color = primaryColor
        canvas.drawText(text, x, y, textWhiteOutlinePaint)
        canvas.drawText(text, x, y, textPaint)
    }

    // Exact horizontal distance from center (cx) to the inner dotted circle at height y
    private fun getDxInner(y: Float, cy: Float, rInner: Float): Float {
        val dy = abs(y - cy)
        return if (dy < rInner) sqrt(rInner * rInner - dy * dy) else 0f
    }

    // Exact horizontal distance from center (cx) to the outer curved dotted boundary at height y
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

        canvas.drawColor(bgDark)

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
        fillPaint.color = podColor
        canvas.drawOval(wingRect, fillPaint)

        val y1 = h * 0.19f
        val y2 = h * 0.39f
        val y3 = h * 0.61f
        val y4 = h * 0.81f
        val rowCenters = floatArrayOf(h * 0.095f, h * 0.29f, h * 0.50f, h * 0.71f, h * 0.905f)

        // Highlight pressed outer wing button
        pressedZone?.let { zone ->
            if (zone.startsWith("WING_") || zone in listOf("RECENTS_TOP_LEFT", "TEMP_TOP_RIGHT", "VOICE", "GEAR_SKIN", "GEAR_HOME")) {
                fillPaint.color = podPressedColor
                val rowIdx = when (zone) {
                    "RECENTS_TOP_LEFT", "TEMP_TOP_RIGHT" -> 0
                    "WING_RADIO", "WING_PHONE" -> 1
                    "WING_CARPLAY", "WING_INTERNET" -> 2
                    "WING_MUSIC", "WING_APPS" -> 3
                    else -> 4
                }
                val topY = when (rowIdx) { 0 -> 0f; 1 -> y1; 2 -> y2; 3 -> y3; else -> y4 }
                val botY = when (rowIdx) { 0 -> y1; 1 -> y2; 2 -> y3; 3 -> y4; else -> h }
                val isLeft = zone in listOf("RECENTS_TOP_LEFT", "WING_RADIO", "WING_CARPLAY", "WING_MUSIC", "VOICE")
                canvas.save()
                canvas.clipRect(if (isLeft) 0f else cx, topY, if (isLeft) cx else w, botY)
                canvas.drawOval(wingRect, fillPaint)
                canvas.restore()
            }
        }

        // #1: Horizontal lines strictly start at outside of inside dotted curve and end at outside dotted curve
        val rInnerStitch = rInner + 4f
        for (y in floatArrayOf(y1, y2, y3, y4)) {
            val dxIn = getDxInner(y, cy, rInnerStitch)
            val dxOut = getDxOuter(y, cy, rxOuter, ryOuter)
            if (dxOut > dxIn) {
                // Left horizontal stitch line
                canvas.drawLine(cx - dxOut, y, cx - dxIn, y, groovePaint)
                canvas.drawLine(cx - dxOut + 3f, y, cx - dxIn - 3f, y, stitchPaint)
                // Right horizontal stitch line
                canvas.drawLine(cx + dxIn, y, cx + dxOut, y, groovePaint)
                canvas.drawLine(cx + dxIn + 3f, y, cx + dxOut - 3f, y, stitchPaint)
            }
        }

        // Outer Left & Right Curved Stitching Borders (exact match to getDxOuter)
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

        // Wing Text Labels (Centered between inner and outer dotted lines at each row's height)
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

        // #7: Four Corner Blue Icons placed strictly between the inner and outer dotted lines
        val topCornerMidOffset = (getDxInner(rowCenters[0], cy, rInnerStitch) + getDxOuter(rowCenters[0], cy, rxOuter, ryOuter)) / 2f
        val botCornerMidOffset = (getDxInner(rowCenters[4], cy, rInnerStitch) + getDxOuter(rowCenters[4], cy, rxOuter, ryOuter)) / 2f

        // #9: Top-Left Two-Screen Recent Apps Icon
        drawTwoScreensIcon(canvas, cx - topCornerMidOffset, rowCenters[0], h * 0.030f)

        // #10 & #11: Top-Right Compass Heading + Thermometer Icon + Clean Temperature
        drawTopRightCompassAndTemp(canvas, cx + topCornerMidOffset, rowCenters[0], h)

        // Bottom-Left Voice Microphone Icon
        drawVoiceIcon(canvas, cx - botCornerMidOffset, rowCenters[4], h * 0.032f)

        // Bottom-Right Dual Settings Gears
        val gearSpacing = h * 0.045f
        drawGearIcon(canvas, cx + botCornerMidOffset - gearSpacing, rowCenters[4], h * 0.029f, secondaryColor)
        drawGearIcon(canvas, cx + botCornerMidOffset + gearSpacing, rowCenters[4], h * 0.029f, secondaryColor)
    }

    private fun drawInnerRing(canvas: Canvas, cx: Float, cy: Float, h: Float, rHub: Float, rInner: Float) {
        // Dark separator groove around inner ring
        groovePaint.strokeWidth = 12f
        canvas.drawCircle(cx, cy, rInner, groovePaint)

        // #2: 35% Lighter Gray Inner Ring Fill
        fillPaint.color = innerRingColor
        canvas.drawCircle(cx, cy, rInner - 4f, fillPaint)

        // Stitching circle around inner ring
        canvas.drawCircle(cx, cy, rInner + 4f, stitchPaint)

        // Diagonal radial dividers separating Top Arc, Bottom Arc, Left Icons, Right Icons
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

        // Horizontal stitch dividers inside the Left & Right inner icon rings
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

        // TOP ARC: Date, Day of Week, and Volume - / +
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

        // BOTTOM ARC: < (Prev), Music Player / Scrolling Song Info, > (Next)
        drawRedOutlinedText(canvas, "Music Player", cx, cy + rMid - h * 0.006f, h * 0.031f)

        // Scrolling Song Title / Artist Ticker when playing
        drawBottomMediaSubtext(canvas, cx, cy + rMid + h * 0.036f, rHub * 0.62f, h * 0.028f)

        val mediaAngleLeft = Math.toRadians(118.0)
        val mediaAngleRight = Math.toRadians(62.0)
        drawRedOutlinedText(canvas, "<", cx + rMid * cos(mediaAngleLeft).toFloat(), cy + rMid * sin(mediaAngleLeft).toFloat() + 12f, h * 0.050f)
        drawRedOutlinedText(canvas, ">", cx + rMid * cos(mediaAngleRight).toFloat(), cy + rMid * sin(mediaAngleRight).toFloat() + 12f, h * 0.050f)

        // LEFT & RIGHT INNER RING ICONS (Customizable via Long-Press #3)
        val iconSize = h * 0.031f
        drawCustomSlotIcon(canvas, "RADIO", 0, cx - rMid * 0.92f, h * 0.29f, iconSize)
        drawCustomSlotIcon(canvas, "CARPLAY", 1, cx - rMid, h * 0.50f, iconSize)
        drawCustomSlotIcon(canvas, "MUSIC", 2, cx - rMid * 0.92f, h * 0.71f, iconSize)

        drawCustomSlotIcon(canvas, "PHONE", 3, cx + rMid * 0.92f, h * 0.29f, iconSize)
        drawCustomSlotIcon(canvas, "INTERNET", 4, cx + rMid, h * 0.50f, iconSize)
        drawCustomSlotIcon(canvas, "APPS", 5, cx + rMid * 0.92f, h * 0.71f, iconSize)
    }

    private fun drawBottomMediaSubtext(canvas: Canvas, cx: Float, y: Float, halfWidth: Float, textSize: Float) {
        textPaint.textSize = textSize
        textPaint.color = secondaryColor

        val active = audioManager.isMusicActive || isPlayingState
        if (!active) {
            canvas.drawText("Play", cx, y, textPaint)
            return
        }

        val tickerText = if (currentSongInfo.isNotEmpty()) {
            "$currentSongInfo     •     "
        } else {
            "Playing Audio Stream     •     "
        }

        val textWidth = textPaint.measureText(tickerText)
        if (textWidth <= 0f) {
            canvas.drawText("Playing", cx, y, textPaint)
            return
        }

        canvas.save()
        canvas.clipRect(cx - halfWidth, y - textSize * 1.2f, cx + halfWidth, y + textSize * 0.4f)
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
        fillPaint.color = Color.parseColor("#0B0C0E")
        canvas.drawCircle(cx, cy, rHub + 6f, fillPaint)

        fillPaint.color = hubBgColor
        canvas.drawCircle(cx, cy, rHub, fillPaint)

        canvas.drawCircle(cx, cy, rHub + 2f, stitchPaint)

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

    // --- Vector Icon Helpers (With Thinner White Outlines #12) ---
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

    // #9: Two-Screen Overlapping Rectangles Icon (Recent Apps)
    private fun drawTwoScreensIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.color = secondaryColor
        // Front screen rectangle
        val front = RectF(cx - s * 0.95f, cy - s * 0.45f, cx + s * 0.45f, cy + s * 0.75f)
        canvas.drawRoundRect(front, 6f, 6f, iconPaint)
        // Rear overlapping screen
        val backPath = Path().apply {
            moveTo(cx - s * 0.35f, cy - s * 0.45f)
            lineTo(cx - s * 0.35f, cy - s * 0.85f)
            lineTo(cx + s * 0.95f, cy - s * 0.85f)
            lineTo(cx + s * 0.95f, cy + s * 0.35f)
            lineTo(cx + s * 0.45f, cy + s * 0.35f)
        }
        canvas.drawPath(backPath, iconPaint)
    }

    // #10 & #11: Compass Direction + Thermometer Icon + Clean Temperature without "+"
    private fun drawTopRightCompassAndTemp(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        // 1. Compass Badge with N, NE, E, SE, S, SW, W, NW
        val compX = cx - h * 0.088f
        val compRadius = h * 0.032f
        iconPaint.color = secondaryColor
        iconPaint.strokeWidth = 3f
        canvas.drawCircle(compX, cy, compRadius, iconPaint)
        textPaint.textSize = h * 0.025f
        textPaint.color = secondaryColor
        canvas.drawText(compassDir, compX, cy + h * 0.009f, textPaint)

        // 2. Vector Thermometer Icon in front of temperature
        val thermX = cx - h * 0.022f
        val rBulb = h * 0.013f
        fillPaint.color = secondaryColor
        canvas.drawCircle(thermX, cy + h * 0.014f, rBulb, fillPaint)
        iconPaint.strokeWidth = 4.2f
        canvas.drawLine(thermX, cy + h * 0.010f, thermX, cy - h * 0.024f, iconPaint)
        // Thermometer degree tick marks
        canvas.drawLine(thermX + 4f, cy - h * 0.018f, thermX + 10f, cy - h * 0.018f, iconPaint)
        canvas.drawLine(thermX + 4f, cy - h * 0.006f, thermX + 10f, cy - h * 0.006f, iconPaint)

        // 3. Temperature text (no "+" when above freezing)
        textPaint.textSize = h * 0.040f
        textPaint.color = secondaryColor
        val tempStr = "${temperatureF}°"
        canvas.drawText(tempStr, cx + h * 0.052f, cy + h * 0.015f, textPaint)
    }

    private fun drawVoiceIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.color = secondaryColor
        iconPaint.strokeWidth = 4.2f
        canvas.drawRoundRect(RectF(cx - s * 0.35f, cy - s * 0.8f, cx + s * 0.35f, cy + s * 0.2f), 12f, 12f, iconPaint)
        canvas.drawArc(RectF(cx - s * 0.7f, cy - s * 0.5f, cx + s * 0.7f, cy + s * 0.55f), 0f, 180f, false, iconPaint)
        canvas.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.95f, iconPaint)
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

    // --- Touch & Long-Press Detection ---
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

        // Inner Ring: Distinguishes ICON_* touches (#3) from outer WING_* text touches (#5)
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
                        else -> "MEDIA_PLAY"
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

        // Outer Curved Wings
        val isLeft = x < cx
        return when {
            y < h * 0.19f -> if (isLeft) "RECENTS_TOP_LEFT" else "TEMP_TOP_RIGHT"
            y < h * 0.39f -> if (isLeft) "WING_RADIO" else "WING_PHONE"
            y < h * 0.61f -> if (isLeft) "WING_CARPLAY" else "WING_INTERNET"
            y < h * 0.81f -> if (isLeft) "WING_MUSIC" else "WING_APPS"
            else -> {
                if (isLeft) "VOICE"
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
            "MEDIA_PLAY" -> {
                mediaHelper.playPause()
                togglePlayState()
            }
            "RECENTS_TOP_LEFT" -> openRecentApps()
            "TEMP_TOP_RIGHT" -> showTemperatureDialog()

            // #3: Long-press on any red inner-ring icon opens the Standard Icon Picker
            "ICON_RADIO", "ICON_CARPLAY", "ICON_MUSIC", "ICON_PHONE", "ICON_INTERNET", "ICON_APPS" -> {
                val slotKey = zone.removePrefix("ICON_")
                if (isLongPress) {
                    showIconPickerDialog(slotKey)
                } else if (slotKey == "APPS") {
                    showVisualAppDrawer(assignToSlot = null)
                } else {
                    launchAssignedApp(slotKey)
                }
            }

            // #5 & #6: Long-press on any outer red word opens the Keyboard Rename + App Picker Dialog
            "WING_RADIO", "WING_CARPLAY", "WING_MUSIC", "WING_PHONE", "WING_INTERNET", "WING_APPS" -> {
                val slotKey = zone.removePrefix("WING_")
                if (isLongPress) {
                    showRenameAndAssignDialog(slotKey)
                } else if (slotKey == "APPS") {
                    showVisualAppDrawer(assignToSlot = null)
                } else {
                    launchAssignedApp(slotKey)
                }
            }

            "VOICE" -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    showVisualAppDrawer(assignToSlot = "VOICE")
                }
            }
            "GEAR_SKIN" -> showSkinCustomizerDialog()
            "GEAR_HOME" -> showEscapeHatchDialog()
        }
    }

    // #9: Triggers Android's Recent Apps (Multitasking Swipe-to-Close Carousel)
    private fun openRecentApps() {
        // Method 1: SystemUI Recents Toggle Broadcast (Supported on most XYAuto / Android head units)
        try {
            context.sendBroadcast(Intent("com.android.systemui.recents.TOGGLE_RECENTS"))
            context.sendBroadcast(Intent("com.xyauto.broadcast.RECENTS"))
        } catch (_: Exception) {}

        // Method 2: Direct SystemUI RecentsActivity Intent
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

        // Method 3: Dispatch Hardware APP_SWITCH Keycode (Keycode 187)
        try {
            Runtime.getRuntime().exec("input keyevent 187")
        } catch (_: Exception) {}
    }

    // #3: Standard Icon Picker for Inner Red Icons
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

    // #5: Pop-up Keyboard Dialog to Rename Wing Text & Assign App
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

    // #6: Visual App Drawer Grid with Installed Android Application Icons
    private fun showVisualAppDrawer(assignToSlot: String?) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }

        val gridView = GridView(context).apply {
            numColumns = 5
            verticalSpacing = 28
            horizontalSpacing = 20
            setPadding(32, 32, 32, 32)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(Color.parseColor("#181C24"))
        }

        lateinit var alertDialog: AlertDialog

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(12, 16, 12, 16)
                }

                val appInfo = apps[position]
                val iconView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(96, 96)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(appInfo.loadIcon(pm))
                }

                val labelView = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = appInfo.loadLabel(pm).toString()
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, 8, 0, 0)
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

    private fun showTemperatureDialog() {
        val input = EditText(context).apply {
            setText(temperatureF.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSelection(text.length)
            setPadding(45, 35, 45, 35)
        }
        AlertDialog.Builder(context)
            .setTitle("Set Temperature Readout (°F)")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v != null) {
                    temperatureF = v
                    prefs.edit().putInt("temp_f", v).apply()
                    invalidate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSkinCustomizerDialog() {
        val themes = arrayOf(
            "Pure Crimson Red & Blue (Default)",
            "Deep Ruby Red & Blue",
            "Subaru World Rally Blue & Gold",
            "Subaru Wilderness Gold & Blue",
            "Cyber Ice Cyan & White",
            "Emerald Matrix Green"
        )
        AlertDialog.Builder(context)
            .setTitle("Customize Launcher Skin Color")
            .setItems(themes) { _, which ->
                val (pri, sec) = when (which) {
                    0 -> Color.parseColor("#E6192E") to Color.parseColor("#4A90E2")
                    1 -> Color.parseColor("#C8102E") to Color.parseColor("#4A90E2")
                    2 -> Color.parseColor("#2B7DE9") to Color.parseColor("#E5B842")
                    3 -> Color.parseColor("#E09F24") to Color.parseColor("#4A90E2")
                    4 -> Color.parseColor("#00BCD4") to Color.parseColor("#FFFFFF")
                    else -> Color.parseColor("#2ECC71") to Color.parseColor("#4A90E2")
                }
                primaryColor = pri
                secondaryColor = sec
                prefs.edit()
                    .putInt("primary_color_v3", pri)
                    .putInt("secondary_color_v3", sec)
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
            "Reset All Custom Labels, Icons & Shortcuts"
        )
        AlertDialog.Builder(context)
            .setTitle("System & Launcher Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> LauncherUtils.openHomeSettings(context)
                    1 -> context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    2 -> {
                        for (k in wingKeys) {
                            prefs.edit()
                                .remove("pkg_$k")
                                .remove("label_$k")
                                .remove("icon_$k")
                                .apply()
                        }
                        invalidate()
                        Toast.makeText(context, "All customizations reset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
