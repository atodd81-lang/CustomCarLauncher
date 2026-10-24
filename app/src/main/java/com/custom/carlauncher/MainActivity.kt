package com.custom.carlauncher

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var mediaHelper: MediaControlHelper
    private lateinit var locationManager: LocationManager
    private lateinit var dashboardView: AgamaDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaHelper = MediaControlHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        dashboardView = AgamaDashboardView(this, mediaHelper)
        setContentView(dashboardView)

        startGpsUpdates()
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
                        val sats = location.extras?.getInt("satellites", 12) ?: 12
                        val compass = if (location.hasBearing()) {
                            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
                            val idx = (((location.bearing + 22.5f) % 360f) / 45f).toInt() and 7
                            dirs[idx]
                        } else {
                            null
                        }
                        dashboardView.updateGpsData(mph, sats, compass)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            )
        } catch (_: SecurityException) {
            // Standard fallback if GPS permission not yet toggled in Android settings
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                mediaHelper.playPause()
                dashboardView.togglePlayText()
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
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Skin Colors:
    // - Outer word ring (podColor) is 5% lighter gray (#303540)
    // - Inner icon ring (innerRingColor) is 20% lighter gray (#5C6270)
    private var primaryColor = prefs.getInt("primary_color_v2", Color.parseColor("#E6192E"))
    private var secondaryColor = prefs.getInt("secondary_color_v2", Color.parseColor("#4A90E2"))
    private val bgDark = Color.parseColor("#111318")
    private val podColor = Color.parseColor("#303540")
    private val podPressedColor = Color.parseColor("#434A58")
    private val innerRingColor = Color.parseColor("#5C6270")
    private val hubBgColor = Color.parseColor("#16181F")

    // Center Hub Mode: 0 = Subaru Logo, 1 = Speedometer, 2 = Digital Clock
    private var centerMode = prefs.getInt("center_mode", 0)
    private var currentMph = 0
    private var satelliteCount = 12
    private var currentCompass = "N"
    private var isPlayingState = false
    private var pressedZone: String? = null

    // Track apps opened so they can be swiped closed in the Recents screen
    private val openedPackages = LinkedHashSet<String>()

    private val wingKeys = listOf("RADIO", "CARPLAY", "MUSIC", "PHONE", "INTERNET", "APPS")

    private val iconNames = arrayOf(
        "Radio Tuner",
        "CarPlay / Screen",
        "Music Note",
        "Phone Handset",
        "Internet Globe",
        "Apps 9-Dot Grid",
        "Navigation Arrow",
        "Bluetooth Symbol",
        "Equalizer Sliders",
        "Camera / Dashcam",
        "Engine / OBD Gauge",
        "Video / YouTube"
    )

    // Core Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#111318")
    }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#D5DAE2")
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
    }
    // Slightly thinner white border on text (reduced from 3.2f to 1.6f)
    private val textWhiteOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Slightly thinner white border on icons (reduced from 7.8f to 6.0f)
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

    private val tickRunnable = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 10000L)
        }
    }

    init {
        post(tickRunnable)
    }

    fun updateGpsData(mph: Int, sats: Int, compass: String?) {
        currentMph = mph
        satelliteCount = if (sats > 0) sats else 12
        if (compass != null) {
            currentCompass = compass
        }
        invalidate()
    }

    fun togglePlayText() {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        canvas.drawColor(bgDark)

        val rHub = h * 0.28f
        val rInner = h * 0.45f
        val rxOuter = w * 0.41f
        val ryOuter = h * 0.48f

        drawOuterWings(canvas, cx, cy, w, h, rInner, rxOuter, ryOuter)
        drawInnerRing(canvas, cx, cy, rHub, rInner)
        drawCentralHub(canvas, cx, cy, rHub)
        drawCornerPodsOutsideDottedLine(canvas, w, h)
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
        val rowCenters = floatArrayOf(h * 0.10f, h * 0.29f, h * 0.50f, h * 0.71f, h * 0.90f)

        // Highlight pressed outer wing button
        pressedZone?.let { zone ->
            if (zone.startsWith("WING_")) {
                val key = zone.removePrefix("WING_")
                fillPaint.color = podPressedColor
                val idx = when (key) {
                    "RADIO", "PHONE" -> 1
                    "CARPLAY", "INTERNET" -> 2
                    "MUSIC", "APPS" -> 3
                    else -> -1
                }
                if (idx != -1) {
                    val topY = when (idx) { 1 -> y1; 2 -> y2; else -> y3 }
                    val botY = when (idx) { 1 -> y2; 2 -> y3; else -> y4 }
                    val leftX = if (key in listOf("RADIO", "CARPLAY", "MUSIC")) 0f else cx
                    val rightX = if (leftX == 0f) cx else w
                    canvas.save()
                    canvas.clipRect(leftX, topY, rightX, botY)
                    canvas.drawOval(wingRect, fillPaint)
                    canvas.restore()
                }
            }
        }

        // Req 1: Horizontal light-colored dashed lines start strictly at the outside of the inside
        // dotted circle (dxInner) and end strictly at the outside dotted curved line (dxOuter)
        strokePaint.strokeWidth = 9f
        strokePaint.color = bgDark
        for (y in floatArrayOf(y1, y2, y3, y4)) {
            val dy = abs(y - cy)
            if (dy < rInner && dy < ryOuter) {
                val dxInner = sqrt(rInner * rInner - dy * dy) + 3f
                val ratio = dy / ryOuter
                val dxOuter = rxOuter * sqrt(1f - ratio * ratio) - 3f
                if (dxOuter > dxInner) {
                    // Left wing horizontal divider
                    canvas.drawLine(cx - dxOuter, y, cx - dxInner, y, strokePaint)
                    canvas.drawLine(cx - dxOuter, y, cx - dxInner, y, dashedPaint)
                    // Right wing horizontal divider
                    canvas.drawLine(cx + dxInner, y, cx + dxOuter, y, strokePaint)
                    canvas.drawLine(cx + dxInner, y, cx + dxOuter, y, dashedPaint)
                }
            }
        }

        // Outer curved dashed boundary arcs on left and right
        canvas.drawArc(wingRect, 126f, 108f, false, dashedPaint)
        canvas.drawArc(wingRect, -54f, 108f, false, dashedPaint)

        // Left & Right Wing Text Labels centered between inner and outer dotted lines
        val labelSize = h * 0.036f
        val left keysLeft = arrayOf("RADIO", "CARPLAY", "MUSIC")
        val keysRight = arrayOf("PHONE", "INTERNET", "APPS")

        for (i in 0..2) {
            val rowY = rowCenters[i + 1]
            val dy = abs(rowY - cy)
            val dxInner = sqrt(rInner * rInner - dy * dy)
            val dxOuter = rxOuter * sqrt(1f - (dy / ryOuter).pow(2))
            val midOffset = (dxInner + dxOuter) / 2f

            val leftKey = keysLeft[i]
            val rightKey = keysRight[i]
            val leftLabel = prefs.getString("label_$leftKey", leftKey) ?: leftKey
            val rightLabel = prefs.getString("label_$rightKey", rightKey) ?: rightKey

            drawRedOutlinedText(canvas, leftLabel, cx - midOffset, rowY + 10f, labelSize)
            drawRedOutlinedText(canvas, rightLabel, cx + midOffset, rowY + 10f, labelSize)
        }
    }

    private fun drawInnerRing(canvas: Canvas, cx: Float, cy: Float, rHub: Float, rInner: Float) {
        val h = height.toFloat()

        // Dark spacer ring outside inner ring
        strokePaint.strokeWidth = 14f
        strokePaint.color = bgDark
        canvas.drawCircle(cx, cy, rInner, strokePaint)

        // Req 2: Fill inner ring with 20% lighter gray (#5C6270)
        fillPaint.color = innerRingColor
        canvas.drawCircle(cx, cy, rInner - 6f, fillPaint)

        // Inside dotted circle boundary
        canvas.drawCircle(cx, cy, rInner + 2f, dashedPaint)

        // Dark radial dividers separating Top Arc, Bottom Arc, Left Icons, Right Icons
        strokePaint.strokeWidth = 10f
        strokePaint.color = bgDark
        val angles = floatArrayOf(42f, 138f, 222f, 318f)
        for (deg in angles) {
            val rad = Math.toRadians(deg.toDouble())
            val xStart = cx + rHub * cos(rad).toFloat()
            val yStart = cy + rHub * sin(rad).toFloat()
            val xEnd = cx + rInner * cos(rad).toFloat()
            val yEnd = cy + rInner * sin(rad).toFloat()
            canvas.drawLine(xStart, yStart, xEnd, yEnd, strokePaint)
        }

        // Dark-only separators between the 3 icons on left and right of inner ring
        for (y in floatArrayOf(h * 0.39f, h * 0.61f)) {
            val dy = abs(y - cy)
            if (dy < rInner) {
                val dxOuter = sqrt(rInner * rInner - dy * dy)
                val dxInner = if (dy < rHub) sqrt(rHub * rHub - dy * dy) else 0f
                canvas.drawLine(cx - dxOuter, y, cx - dxInner, y, strokePaint)
                canvas.drawLine(cx + dxInner, y, cx + dxOuter, y, strokePaint)
            }
        }

        val rMid = (rHub + rInner) / 2f

        // TOP ARC: Date, Day of Week, and Volume - / +
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val dayFormat = SimpleDateFormat("EEEE", Locale.US)
        val now = Date()

        drawRedOutlinedText(canvas, dateFormat.format(now), cx, cy - rMid - h * 0.01f, h * 0.033f)

        textPaint.textSize = h * 0.026f
        textPaint.color = secondaryColor
        canvas.drawText(dayFormat.format(now), cx, cy - rMid + h * 0.03f, textPaint)

        val volAngleLeft = Math.toRadians(242.0)
        val volAngleRight = Math.toRadians(298.0)
        drawRedOutlinedText(canvas, "-", cx + rMid * cos(volAngleLeft).toFloat(), cy + rMid * sin(volAngleLeft).toFloat() + 14f, h * 0.055f)
        drawRedOutlinedText(canvas, "+", cx + rMid * cos(volAngleRight).toFloat(), cy + rMid * sin(volAngleRight).toFloat() + 14f, h * 0.055f)

        // BOTTOM ARC: < (Prev), Music Player / Play, > (Next)
        drawRedOutlinedText(canvas, "Music Player", cx, cy + rMid - h * 0.005f, h * 0.032f)

        textPaint.textSize = h * 0.030f
        textPaint.color = secondaryColor
        canvas.drawText(if (isPlayingState) "Playing" else "Play", cx, cy + rMid + h * 0.038f, textPaint)

        val mediaAngleLeft = Math.toRadians(118.0)
        val mediaAngleRight = Math.toRadians(62.0)
        drawRedOutlinedText(canvas, "<", cx + rMid * cos(mediaAngleLeft).toFloat(), cy + rMid * sin(mediaAngleLeft).toFloat() + 12f, h * 0.052f)
        drawRedOutlinedText(canvas, ">", cx + rMid * cos(mediaAngleRight).toFloat(), cy + rMid * sin(mediaAngleRight).toFloat() + 12f, h * 0.052f)

        // LEFT & RIGHT INNER RING ICONS (Customizable via Long-Press)
        val iconSize = h * 0.031f
        drawCustomSlotIcon(canvas, "RADIO", 0, cx - rMid * 0.92f, h * 0.29f, iconSize, primaryColor)
        drawCustomSlotIcon(canvas, "CARPLAY", 1, cx - rMid, h * 0.50f, iconSize, primaryColor)
        drawCustomSlotIcon(canvas, "MUSIC", 2, cx - rMid * 0.92f, h * 0.71f, iconSize, primaryColor)

        drawCustomSlotIcon(canvas, "PHONE", 3, cx + rMid * 0.92f, h * 0.29f, iconSize, primaryColor)
        drawCustomSlotIcon(canvas, "INTERNET", 4, cx + rMid, h * 0.50f, iconSize, primaryColor)
        drawCustomSlotIcon(canvas, "APPS", 5, cx + rMid * 0.92f, h * 0.71f, iconSize, primaryColor)
    }

    // Req 7, 8, 9: Draw the 4 blue corner items outside the outer dotted line
    private fun drawCornerPodsOutsideDottedLine(canvas: Canvas, w: Float, h: Float) {
        val leftCornerX = w * 0.105f
        val rightCornerX = w * 0.895f
        val topCornerY = h * 0.095f
        val botCornerY = h * 0.905f

        // Top-Left Corner (Outside Dotted Line): Wi-Fi + GPS Satellite & Count
        drawTopLeftStatus(canvas, leftCornerX, topCornerY, h)

        // Top-Right Corner (Outside Dotted Line): Blue Compass Circle (N, NE, E...) + Temp
        drawTopRightStatus(canvas, rightCornerX, topCornerY, h)

        // Bottom-Left Corner (Outside Dotted Line): Two-Screen Recents Icon
        drawTwoScreensIcon(canvas, leftCornerX, botCornerY, h * 0.034f)

        // Bottom-Right Corner (Outside Dotted Line): Dual Settings Gears
        drawGearIcon(canvas, rightCornerX - h * 0.055f, botCornerY, h * 0.030f, secondaryColor)
        drawGearIcon(canvas, rightCornerX + h * 0.055f, botCornerY, h * 0.030f, secondaryColor)
    }

    private fun drawCentralHub(canvas: Canvas, cx: Float, cy: Float, rHub: Float) {
        fillPaint.color = Color.parseColor("#0B0C0E")
        canvas.drawCircle(cx, cy, rHub + 6f, fillPaint)

        fillPaint.color = hubBgColor
        canvas.drawCircle(cx, cy, rHub, fillPaint)

        strokePaint.strokeWidth = 3f
        strokePaint.color = Color.parseColor("#2E3440")
        canvas.drawCircle(cx, cy, rHub - 4f, strokePaint)

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
                canvas.drawText("$currentMph MPH • $currentCompass", cx, cy + rHub * 0.42f, textPaint)
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

    // --- Customizable Standard Icon Renderer ---
    private fun drawCustomSlotIcon(
        canvas: Canvas,
        slotKey: String,
        defaultType: Int,
        cx: Float,
        cy: Float,
        s: Float,
        color: Int
    ) {
        val type = prefs.getInt("icon_$slotKey", defaultType)
        when (type) {
            0 -> drawRadioIcon(canvas, cx, cy, s, color)
            1 -> drawCarPlayIcon(canvas, cx, cy, s, color)
            2 -> drawMusicIcon(canvas, cx, cy, s, color)
            3 -> drawPhoneIcon(canvas, cx, cy, s, color)
            4 -> drawGlobeIcon(canvas, cx, cy, s, color)
            5 -> drawAppsGridIcon(canvas, cx, cy, s, color)
            6 -> drawNavArrowIcon(canvas, cx, cy, s, color)
            7 -> drawBluetoothIcon(canvas, cx, cy, s, color)
            8 -> drawEqualizerIcon(canvas, cx, cy, s, color)
            9 -> drawCameraIcon(canvas, cx, cy, s, color)
            10 -> drawGaugeIcon(canvas, cx, cy, s, color)
            11 -> drawVideoIcon(canvas, cx, cy, s, color)
            else -> drawRadioIcon(canvas, cx, cy, s, color)
        }
    }

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
        canvas.drawCircle(cx - s * 0.6f, cy - s * 0.25f, s * 0.22f, fillPaint)
        canvas.drawCircle(cx, cy + s * 0.3f, s * 0.22f, fillPaint)
        canvas.drawCircle(cx + s * 0.6f, cy - s * 0.4f, s * 0.22f, fillPaint)
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
            canvas.drawLine(cx, cy + s * 0.1f, cx + s * 0.5f, cy - s * 0.45f, p)
        }
    }

    private fun drawVideoIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val rect = RectF(cx - s * 0.9f, cy - s * 0.65f, cx + s * 0.45f, cy + s * 0.65f)
        for (p in arrayOf(iconWhiteOutlinePaint, iconPaint.apply { this.color = color })) {
            canvas.drawRoundRect(rect, 6f, 6f, p)
            canvas.drawLine(cx + s * 0.45f, cy - s * 0.2f, cx + s * 0.95f, cy - s * 0.55f, p)
            canvas.drawLine(cx + s * 0.95f, cy - s * 0.55f, cx + s * 0.95f, cy + s * 0.55f, p)
            canvas.drawLine(cx + s * 0.95f, cy + s * 0.55f, cx + s * 0.45f, cy + s * 0.2f, p)
        }
    }

    // Req 8: Bottom-left Two-Screen overlapping icon for Recent / Opened Apps
    private fun drawTwoScreensIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.color = secondaryColor
        val backScreen = RectF(cx - s * 0.85f, cy - s * 0.75f, cx + s * 0.35f, cy + s * 0.25f)
        val frontScreen = RectF(cx - s * 0.35f, cy - s * 0.25f, cx + s * 0.85f, cy + s * 0.75f)
        canvas.drawRoundRect(backScreen, 6f, 6f, iconPaint)
        fillPaint.color = bgDark
        canvas.drawRoundRect(frontScreen, 6f, 6f, fillPaint)
        canvas.drawRoundRect(frontScreen, 6f, 6f, iconPaint)
    }

    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        iconPaint.color = color
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

    private fun drawTopLeftStatus(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        iconPaint.color = secondaryColor
        canvas.drawLine(cx - 18f, cy - 12f, cx + 12f, cy + 18f, iconPaint)
        canvas.drawCircle(cx - 3f, cy + 3f, 8f, iconPaint)
        textPaint.textSize = h * 0.028f
        textPaint.color = secondaryColor
        canvas.drawText(satelliteCount.toString(), cx + 32f, cy + 2f, textPaint)
    }

    // Req 9: Solid blue circle displays live compass heading (N, NE, E, SE, S, SW, W, NW)
    private fun drawTopRightStatus(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        val badgeRadius = h * 0.032f
        val badgeX = cx - h * 0.065f
        val badgeY = cy

        fillPaint.color = secondaryColor
        canvas.drawCircle(badgeX, badgeY, badgeRadius, fillPaint)

        // Compass text inside the solid blue circle
        textPaint.textSize = h * 0.024f
        textPaint.color = Color.WHITE
        canvas.drawText(currentCompass, badgeX, badgeY + h * 0.009f, textPaint)

        // Temperature readout to the right of the blue compass dot
        textPaint.textSize = h * 0.038f
        textPaint.color = secondaryColor
        val readout = if (currentMph > 0) "$currentMph MPH" else "+59°"
        canvas.drawText(readout, cx + h * 0.035f, cy + h * 0.013f, textPaint)
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

        val rHub = h * 0.28f
        val rInner = h * 0.45f

        // 1. Center Hub
        if (dist <= rHub) return "CENTER_HUB"

        // 2. Inner Ring (Separates ICON_ slots from WING_ text slots)
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

        // 3. Outer Corners & Wing Text Slots
        val isLeft = x < cx
        return when {
            y < h * 0.19f -> if (isLeft) "STATUS_LEFT" else "STATUS_RIGHT"
            y < h * 0.39f -> if (isLeft) "WING_RADIO" else "WING_PHONE"
            y < h * 0.61f -> if (isLeft) "WING_CARPLAY" else "WING_INTERNET"
            y < h * 0.81f -> if (isLeft) "WING_MUSIC" else "WING_APPS"
            else -> {
                if (isLeft) "RECENTS_TWO_SCREENS"
                else {
                    val rightCornerCenter = w * 0.895f
                    if (x < rightCornerCenter) "GEAR_SKIN" else "GEAR_HOME"
                }
            }
        }
    }

    private fun handleZoneAction(zone: String, isLongPress: Boolean) {
        when {
            zone == "CENTER_HUB" -> {
                if (isLongPress) {
                    LauncherUtils.openHomeSettings(context)
                } else {
                    centerMode = (centerMode + 1) % 3
                    prefs.edit().putInt("center_mode", centerMode).apply()
                    invalidate()
                }
            }
            zone == "VOL_DOWN" -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            zone == "VOL_UP" -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            zone == "MEDIA_PREV" -> mediaHelper.prevTrack()
            zone == "MEDIA_NEXT" -> mediaHelper.nextTrack()
            zone == "MEDIA_PLAY" -> {
                mediaHelper.playPause()
                togglePlayText()
            }
            // Req 3: Long-pressing an inner icon opens the standard icon picker
            zone.startsWith("ICON_") -> {
                val slotKey = zone.removePrefix("ICON_")
                if (isLongPress) {
                    showIconPickerDialog(slotKey)
                } else if (slotKey == "APPS") {
                    showVisualAllAppsGrid()
                } else {
                    launchAssignedApp(slotKey)
                }
            }
            // Req 5: Long-pressing the outer written text opens the label editor & app picker
            zone.startsWith("WING_") -> {
                val slotKey = zone.removePrefix("WING_")
                if (isLongPress) {
                    showEditTextAndAppDialog(slotKey)
                } else if (slotKey == "APPS") {
                    showVisualAllAppsGrid()
                } else {
                    launchAssignedApp(slotKey)
                }
            }
            // Req 8: Bottom-left Two-Screen icon pulls up swipeable Opened Apps
            zone == "RECENTS_TWO_SCREENS" -> showSwipeableOpenedAppsDialog()
            zone == "GEAR_SKIN" -> showSkinCustomizerDialog()
            zone == "GEAR_HOME" -> showEscapeHatchDialog()
        }
    }

    // Req 3: Standard Icon Replacer Dialog
    private fun showIconPickerDialog(slotKey: String) {
        AlertDialog.Builder(context)
            .setTitle("Select Icon for $slotKey")
            .setItems(iconNames) { _, which ->
                prefs.edit().putInt("icon_$slotKey", which).apply()
                invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Req 5: Written Text Label Editor + App Assignment Dialog
    private fun showEditTextAndAppDialog(slotKey: String) {
        val currentLabel = prefs.getString("label_$slotKey", slotKey) ?: slotKey
        val input = EditText(context).apply {
            setText(currentLabel)
            setSelection(currentLabel.length)
            hint = "Enter button text"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(context)
            .setTitle("Customize $slotKey Text & App")
            .setView(input)
            .setPositiveButton("Save Text") { _, _ ->
                val newText = input.text.toString().trim().uppercase(Locale.US)
                if (newText.isNotEmpty()) {
                    prefs.edit().putString("label_$slotKey", newText).apply()
                    invalidate()
                }
            }
            .setNeutralButton("Save Text & Pick App") { _, _ ->
                val newText = input.text.toString().trim().uppercase(Locale.US)
                if (newText.isNotEmpty()) {
                    prefs.edit().putString("label_$slotKey", newText).apply()
                    invalidate()
                }
                showAppPickerDialog(slotKey)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchAssignedApp(slotKey: String) {
        val pkg = prefs.getString("pkg_$slotKey", null)
        if (pkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                openedPackages.add(pkg)
                context.startActivity(launchIntent)
                return
            }
        }
        Toast.makeText(context, "Pick an app for $slotKey", Toast.LENGTH_SHORT).show()
        showAppPickerDialog(slotKey)
    }

    private fun showAppPickerDialog(slotKey: String) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0).sortedBy { it.loadLabel(pm).toString() }
        val names = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Assign App to $slotKey")
            .setItems(names) { _, which ->
                val chosenPackage = apps[which].activityInfo.packageName
                prefs.edit().putString("pkg_$slotKey", chosenPackage).apply()
                Toast.makeText(context, "$slotKey mapped to ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Req 6: Visual All-Apps Grid with App Icons (Click to Open, Long-Press to Place on Launcher)
    private fun showVisualAllAppsGrid() {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0).sortedBy { it.loadLabel(pm).toString() }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181B22"))
            setPadding(24, 20, 24, 20)
        }

        val subtitle = TextView(context).apply {
            text = "Tap any app icon to open it • Long-press any app icon to place it on a launcher button"
            setTextColor(Color.parseColor("#A0AAB8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 16)
        }
        container.addView(subtitle)

        val gridView = GridView(context).apply {
            numColumns = 5
            verticalSpacing = 24
            horizontalSpacing = 16
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
        }

        var dialog: AlertDialog? = null

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val info = apps[position]
                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(12, 16, 12, 16)
                }

                val iconView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(96, 96)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(info.loadIcon(pm))
                }

                val labelView = TextView(context).apply {
                    text = info.loadLabel(pm).toString()
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 0)
                }

                itemLayout.addView(iconView)
                itemLayout.addView(labelView)
                return itemLayout
            }
        }

        gridView.adapter = adapter

        gridView.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].activityInfo.packageName
            pm.getLaunchIntentForPackage(pkg)?.let {
                openedPackages.add(pkg)
                dialog?.dismiss()
                context.startActivity(it)
            }
        }

        gridView.setOnItemLongClickListener { _, _, position, _ ->
            val appInfo = apps[position]
            val appLabel = appInfo.loadLabel(pm).toString()
            val appPkg = appInfo.activityInfo.packageName
            val slots = wingKeys.toTypedArray()

            AlertDialog.Builder(context)
                .setTitle("Place '$appLabel' on which Launcher Slot?")
                .setItems(slots) { _, which ->
                    val targetSlot = slots[which]
                    prefs.edit()
                        .putString("pkg_$targetSlot", appPkg)
                        .putString("label_$targetSlot", appLabel.uppercase(Locale.US))
                        .apply()
                    invalidate()
                    Toast.makeText(context, "$appLabel placed on $targetSlot", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        container.addView(
            gridView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        dialog = AlertDialog.Builder(context)
            .setTitle("All Installed Apps")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        dialog.show()
    }

    // Req 8: Swipeable Opened Apps Manager (Swipe any app card left or right to close it)
    private fun showSwipeableOpenedAppsDialog() {
        val pm = context.packageManager

        // Combine apps opened in this session with any active non-system processes
        try {
            activityManager.runningAppProcesses?.forEach { proc ->
                proc.pkgList?.forEach { pkg ->
                    if (pkg != context.packageName && pm.getLaunchIntentForPackage(pkg) != null) {
                        openedPackages.add(pkg)
                    }
                }
            }
        } catch (_: Exception) {}

        // Also include mapped shortcut apps if the list is currently empty
        if (openedPackages.isEmpty()) {
            for (k in wingKeys) {
                prefs.getString("pkg_$k", null)?.let { openedPackages.add(it) }
            }
        }

        val scrollView = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#181B22"))
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }
        scrollView.addView(listContainer)

        val instruction = TextView(context).apply {
            text = "Swipe any app left or right to close it • Tap an app to switch to it"
            setTextColor(Color.parseColor("#A0AAB8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 20)
        }
        listContainer.addView(instruction)

        var dialog: AlertDialog? = null

        if (openedPackages.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = "No opened background apps"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(20, 60, 20, 60)
            }
            listContainer.addView(emptyView)
        } else {
            for (pkg in openedPackages.toList().reversed()) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val appLabel = pm.getApplicationLabel(appInfo).toString()
                    val appIcon = pm.getApplicationIcon(appInfo)

                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundColor(Color.parseColor("#282D38"))
                        setPadding(28, 22, 28, 22)
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.setMargins(0, 0, 0, 16)
                        layoutParams = lp
                    }

                    val img = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(72, 72)
                        setImageDrawable(appIcon)
                    }

                    val title = TextView(context).apply {
                        text = appLabel
                        setTextColor(Color.WHITE)
                        textSize = 17f
                        setPadding(28, 0, 20, 0)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val hint = TextView(context).apply {
                        text = "⟨ Swipe ⟩"
                        setTextColor(secondaryColor)
                        textSize = 13f
                    }

                    card.addView(img)
                    card.addView(title)
                    card.addView(hint)

                    // Swipe Left or Right Touch Listener
                    var startX = 0f
                    var swiping = false
                    card.setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = ev.rawX
                                swiping = false
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val deltaX = ev.rawX - startX
                                if (abs(deltaX) > 20f) {
                                    swiping = true
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                    v.translationX = deltaX
                                    v.alpha = (1f - (abs(deltaX) / (v.width * 0.75f))).coerceIn(0.15f, 1f)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val deltaX = ev.rawX - startX
                                if (abs(deltaX) > v.width * 0.25f) {
                                    val exitX = if (deltaX > 0) v.width.toFloat() else -v.width.toFloat()
                                    v.animate()
                                        .translationX(exitX)
                                        .alpha(0f)
                                        .setDuration(180)
                                        .withEndAction {
                                            listContainer.removeView(v)
                                            openedPackages.remove(pkg)
                                            try {
                                                activityManager.killBackgroundProcesses(pkg)
                                            } catch (_: Exception) {}
                                            Toast.makeText(context, "Closed $appLabel", Toast.LENGTH_SHORT).show()
                                        }
                                        .start()
                                } else {
                                    v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                                    if (!swiping && ev.actionMasked == MotionEvent.ACTION_UP) {
                                        pm.getLaunchIntentForPackage(pkg)?.let {
                                            dialog?.dismiss()
                                            context.startActivity(it)
                                        }
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }

                    listContainer.addView(card)
                } catch (_: Exception) {}
            }
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("Opened Apps")
            .setView(scrollView)
            .setNeutralButton("System Recents") { _, _ ->
                try {
                    val intent = Intent("com.android.systemui.recents.TOGGLE_RECENTS")
                    intent.setClassName("com.android.systemui", "com.android.systemui.recents.RecentsActivity")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Use swipe cards to close apps on this unit", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .create()

        dialog.show()
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
                    .putInt("primary_color_v2", pri)
                    .putInt("secondary_color_v2", sec)
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
            .setTitle("Vinsanity Launcher Options")
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
                        Toast.makeText(context, "All customizations reset to default", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
