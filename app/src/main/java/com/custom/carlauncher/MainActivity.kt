package com.custom.carlauncher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
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
                        dashboardView.updateGpsData(mph, sats)
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

    // Skin Colors (Default is now True Crimson Red #E6192E & Blue #4A90E2)
    private var primaryColor = prefs.getInt("primary_color_v2", Color.parseColor("#E6192E"))
    private var secondaryColor = prefs.getInt("secondary_color_v2", Color.parseColor("#4A90E2"))
    private val bgDark = Color.parseColor("#111318")
    private val podColor = Color.parseColor("#232730")
    private val podPressedColor = Color.parseColor("#343A46")
    private val innerRingColor = Color.parseColor("#2C303B")
    private val hubBgColor = Color.parseColor("#16181F")

    // Center Hub Mode: 0 = Subaru Logo, 1 = Speedometer, 2 = Digital Clock
    private var centerMode = prefs.getInt("center_mode", 0)
    private var currentMph = 0
    private var satelliteCount = 12
    private var isPlayingState = false
    private var pressedZone: String? = null

    private val wingKeys = listOf("RADIO", "CARPLAY", "MUSIC", "PHONE", "INTERNET", "APPS")

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
        color = Color.parseColor("#D0D5DD")
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
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
        strokeWidth = 3.2f
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconWhiteOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.8f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val thinWhiteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
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

    fun updateGpsData(mph: Int, sats: Int) {
        currentMph = mph
        satelliteCount = if (sats > 0) sats else 12
        invalidate()
    }

    fun togglePlayText() {
        isPlayingState = !isPlayingState
        invalidate()
    }

    /**
     * Helper that draws red text with a crisp, thin white outer border.
     */
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

        val rHub = h * 0.29f
        val rInner = h * 0.46f
        val rOuter = w * 0.46f

        drawOuterWings(canvas, cx, cy, w, h, rInner, rOuter)
        drawInnerRing(canvas, cx, cy, rHub, rInner)
        drawCentralHub(canvas, cx, cy, rHub)
    }

    private fun drawOuterWings(
        canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, rInner: Float, rOuter: Float
    ) {
        val wingRect = RectF(cx - rOuter, cy - rOuter * 0.78f, cx + rOuter, cy + rOuter * 0.78f)
        fillPaint.color = podColor
        canvas.drawOval(wingRect, fillPaint)

        val y1 = h * 0.19f
        val y2 = h * 0.39f
        val y3 = h * 0.61f
        val y4 = h * 0.81f
        val rowCenters = floatArrayOf(h * 0.10f, h * 0.29f, h * 0.50f, h * 0.71f, h * 0.90f)

        pressedZone?.let { zone ->
            fillPaint.color = podPressedColor
            val idx = when (zone) {
                "RADIO", "PHONE" -> 1
                "CARPLAY", "INTERNET" -> 2
                "MUSIC", "APPS" -> 3
                "VOICE", "GEARS" -> 4
                else -> -1
            }
            if (idx != -1) {
                val topY = when (idx) { 1 -> y1; 2 -> y2; 3 -> y3; else -> y4 }
                val botY = when (idx) { 1 -> y2; 2 -> y3; 3 -> y4; else -> h }
                val leftX = if (zone in listOf("RADIO", "CARPLAY", "MUSIC", "VOICE")) 0f else cx
                val rightX = if (leftX == 0f) cx else w
                canvas.save()
                canvas.clipRect(leftX, topY, rightX, botY)
                canvas.drawOval(wingRect, fillPaint)
                canvas.restore()
            }
        }

        strokePaint.strokeWidth = 10f
        strokePaint.color = bgDark
        for (y in floatArrayOf(y1, y2, y3, y4)) {
            canvas.drawLine(0f, y, w, y, strokePaint)
            canvas.drawLine(w * 0.05f, y, w * 0.95f, y, dashedPaint)
        }

        canvas.drawArc(wingRect, 142f, 76f, false, dashedPaint)
        canvas.drawArc(wingRect, -38f, 76f, false, dashedPaint)

        // Left & Right Wing Text Labels (Red with Thin White Border)
        val labelSize = h * 0.038f
        val leftX = cx - (rInner + (rOuter - rInner) * 0.52f)
        val rightX = cx + (rInner + (rOuter - rInner) * 0.52f)

        drawRedOutlinedText(canvas, prefs.getString("label_RADIO", "RADIO") ?: "RADIO", leftX, rowCenters[1] + 10f, labelSize)
        drawRedOutlinedText(canvas, prefs.getString("label_CARPLAY", "CARPLAY") ?: "CARPLAY", leftX, rowCenters[2] + 10f, labelSize)
        drawRedOutlinedText(canvas, prefs.getString("label_MUSIC", "MUSIC") ?: "MUSIC", leftX, rowCenters[3] + 10f, labelSize)

        drawRedOutlinedText(canvas, prefs.getString("label_PHONE", "PHONE") ?: "PHONE", rightX, rowCenters[1] + 10f, labelSize)
        drawRedOutlinedText(canvas, prefs.getString("label_INTERNET", "INTERNET") ?: "INTERNET", rightX, rowCenters[2] + 10f, labelSize)
        drawRedOutlinedText(canvas, prefs.getString("label_APPS", "APPS") ?: "APPS", rightX, rowCenters[3] + 10f, labelSize)

        drawTopLeftStatus(canvas, leftX, rowCenters[0], h)
        drawTopRightStatus(canvas, rightX, rowCenters[0], h)
        drawVoiceIcon(canvas, leftX, rowCenters[4], h * 0.035f)

        drawGearIcon(canvas, rightX - h * 0.055f, rowCenters[4], h * 0.032f, secondaryColor)
        drawGearIcon(canvas, rightX + h * 0.055f, rowCenters[4], h * 0.032f, secondaryColor)
    }

    private fun drawInnerRing(canvas: Canvas, cx: Float, cy: Float, rHub: Float, rInner: Float) {
        strokePaint.strokeWidth = 14f
        strokePaint.color = bgDark
        canvas.drawCircle(cx, cy, rInner, strokePaint)

        fillPaint.color = innerRingColor
        canvas.drawCircle(cx, cy, rInner - 6f, fillPaint)

        canvas.drawCircle(cx, cy, rInner + 2f, dashedPaint)

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

        val h = height.toFloat()
        for (y in floatArrayOf(h * 0.39f, h * 0.61f)) {
            val dy = abs(y - cy)
            if (dy < rInner) {
                val dxOuter = sqrt(rInner * rInner - dy * dy)
                val dxInner = if (dy < rHub) sqrt(rHub * rHub - dy * dy) else 0f
                canvas.drawLine(cx - dxOuter, y, cx - dxInner, y, strokePaint)
                canvas.drawLine(cx - dxOuter, y, cx - dxInner, y, dashedPaint)
                canvas.drawLine(cx + dxInner, y, cx + dxOuter, y, strokePaint)
                canvas.drawLine(cx + dxInner, y, cx + dxOuter, y, dashedPaint)
            }
        }

        val rMid = (rHub + rInner) / 2f

        // TOP ARC: Date (Red w/ White Outline), Day of Week, and Volume - / +
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val dayFormat = SimpleDateFormat("EEEE", Locale.US)
        val now = Date()

        drawRedOutlinedText(canvas, dateFormat.format(now), cx, cy - rMid - h * 0.01f, h * 0.033f)

        textPaint.textSize = h * 0.026f
        textPaint.color = secondaryColor
        canvas.drawText(dayFormat.format(now), cx, cy - rMid + h * 0.03f, textPaint)

        // Volume - and + (Red w/ White Outline)
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

        // LEFT & RIGHT INNER RING ICONS (Red with White Outline)
        val iconSize = h * 0.032f
        drawRadioIcon(canvas, cx - rMid * 0.92f, h * 0.29f, iconSize, primaryColor)
        drawCarPlayIcon(canvas, cx - rMid, h * 0.50f, iconSize, primaryColor)
        drawMusicIcon(canvas, cx - rMid * 0.92f, h * 0.71f, iconSize, primaryColor)

        drawPhoneIcon(canvas, cx + rMid * 0.92f, h * 0.29f, iconSize, primaryColor)
        drawGlobeIcon(canvas, cx + rMid, h * 0.50f, iconSize, primaryColor)
        drawAppsGridIcon(canvas, cx + rMid * 0.92f, h * 0.71f, iconSize, primaryColor)
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
                canvas.drawText("$currentMph MPH", cx, cy + rHub * 0.42f, textPaint)
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

    // --- Vector Icon Helpers (Dual-Pass White Outline + Red Interior) ---
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

    private fun drawVoiceIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        iconPaint.color = secondaryColor
        canvas.drawRoundRect(RectF(cx - s * 0.35f, cy - s * 0.8f, cx + s * 0.35f, cy + s * 0.2f), 12f, 12f, iconPaint)
        canvas.drawArc(RectF(cx - s * 0.7f, cy - s * 0.5f, cx + s * 0.7f, cy + s * 0.55f), 0f, 180f, false, iconPaint)
        canvas.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.95f, iconPaint)
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
        canvas.drawLine(cx - 15f, cy - 12f, cx + 15f, cy + 18f, iconPaint)
        canvas.drawCircle(cx, cy + 3f, 8f, iconPaint)
        textPaint.textSize = h * 0.028f
        textPaint.color = secondaryColor
        canvas.drawText(satelliteCount.toString(), cx + 36f, cy - 4f, textPaint)
    }

    private fun drawTopRightStatus(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        fillPaint.color = secondaryColor
        canvas.drawCircle(cx - 48f, cy + 2f, h * 0.020f, fillPaint)
        textPaint.textSize = h * 0.040f
        textPaint.color = secondaryColor
        val readout = if (currentMph > 0) "$currentMph MPH" else "+59°"
        canvas.drawText(readout, cx + 18f, cy + 14f, textPaint)
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
                val isLongPress = (System.currentTimeMillis() - downTime) > 650L
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
        val rInner = h * 0.46f

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
                        else -> "MEDIA_PLAY"
                    }
                }
                dx < 0 -> {
                    when {
                        y < h * 0.39f -> "RADIO"
                        y < h * 0.61f -> "CARPLAY"
                        else -> "MUSIC"
                    }
                }
                else -> {
                    when {
                        y < h * 0.39f -> "PHONE"
                        y < h * 0.61f -> "INTERNET"
                        else -> "APPS"
                    }
                }
            }
        }

        val isLeft = x < cx
        return when {
            y < h * 0.19f -> if (isLeft) "STATUS_LEFT" else "STATUS_RIGHT"
            y < h * 0.39f -> if (isLeft) "RADIO" else "PHONE"
            y < h * 0.61f -> if (isLeft) "CARPLAY" else "INTERNET"
            y < h * 0.81f -> if (isLeft) "MUSIC" else "APPS"
            else -> {
                if (isLeft) "VOICE"
                else {
                    val rightWingCenter = cx + (rInner + (w * 0.46f - rInner) * 0.52f)
                    if (x < rightWingCenter) "GEAR_SKIN" else "GEAR_HOME"
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
                togglePlayText()
            }
            "APPS" -> {
                if (isLongPress) showAppPickerDialog("APPS")
                else showAllAppsDrawer()
            }
            "RADIO", "CARPLAY", "MUSIC", "PHONE", "INTERNET" -> {
                if (isLongPress) {
                    showAppPickerDialog(zone)
                } else {
                    launchAssignedApp(zone)
                }
            }
            "VOICE" -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    showAppPickerDialog("VOICE")
                }
            }
            "GEAR_SKIN" -> showSkinCustomizerDialog()
            "GEAR_HOME" -> showEscapeHatchDialog()
        }
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
        Toast.makeText(context, "Select app for $slotKey (Long-press anytime to change)", Toast.LENGTH_SHORT).show()
        showAppPickerDialog(slotKey)
    }

    private fun showAppPickerDialog(slotKey: String) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0).sortedBy { it.loadLabel(pm).toString() }
        val names = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Assign App to $slotKey Button")
            .setItems(names) { _, which ->
                val chosenPackage = apps[which].activityInfo.packageName
                prefs.edit().putString("pkg_$slotKey", chosenPackage).apply()
                Toast.makeText(context, "$slotKey mapped to ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAllAppsDrawer() {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0).sortedBy { it.loadLabel(pm).toString() }
        val names = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("All Installed Apps")
            .setItems(names) { _, which ->
                val pkg = apps[which].activityInfo.packageName
                pm.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
            }
            .setNegativeButton("Close", null)
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
            "Reset Mapped Button Shortcuts"
        )
        AlertDialog.Builder(context)
            .setTitle("System & Launcher Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> LauncherUtils.openHomeSettings(context)
                    1 -> context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    2 -> {
                        for (k in wingKeys) prefs.edit().remove("pkg_$k").apply()
                        Toast.makeText(context, "Shortcuts reset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
