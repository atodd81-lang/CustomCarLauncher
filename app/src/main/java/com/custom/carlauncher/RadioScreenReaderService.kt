package com.custom.carlauncher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.regex.Pattern

class RadioScreenReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // Matches FM frequencies like 92.3, 101.5, 88.10 or AM frequencies like 680, 1010
    private val fmRegex = Pattern.compile("\\b(8[7-9]\\.[0-9]{1,2}|9[0-9]\\.[0-9]{1,2}|10[0-8]\\.[0-9]{1,2})\\b")
    private val amRegex = Pattern.compile("\\b([5-9][0-9]0|1[0-7][0-9]0)\\b")

    private val ignoreUiWords = setOf(
        "playing", "band", "settings", "sensitivity", "loc", "dx", "rds enable",
        "af enable", "area select", "usa", "europe", "fm", "fm1", "fm2", "fm3",
        "am", "am1", "am2", "st", "mono", "mhz", "khz", "eq", "aps", "seek",
        "pty", "ta", "af", "reg", "radio", "tuner"
    )

    // Listens for forced rescan requests right after the user presses < or > on the launcher
    private val forceScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.custom.carlauncher.FORCE_A11Y_SCAN") {
                val returnHomeAfter = intent.getBooleanExtra("return_home", false)
                handler.postDelayed({ scanAllInteractiveWindows(returnHomeAfter) }, 120L)
                handler.postDelayed({ scanAllInteractiveWindows(returnHomeAfter) }, 350L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter("com.custom.carlauncher.FORCE_A11Y_SCAN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceScanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(forceScanReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(forceScanReceiver)
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase(Locale.US) ?: return

        // 1. Capture Rich Media / Podcast / Music Player Notification Updates (even when on Home Screen!)
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val parcelable = event.parcelableData
            if (parcelable is Notification) {
                val extras = parcelable.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

                val parts = listOf(title, text, subText).filter { it.isNotEmpty() }.distinct()
                if (parts.isNotEmpty() && pkg != "com.custom.carlauncher") {
                    broadcastCapturedMedia(pkg, parts.joinToString("  —  "))
                    return
                }
            }

            val eventTexts = event.text.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            if (eventTexts.isNotEmpty() && pkg != "com.custom.carlauncher") {
                broadcastCapturedMedia(pkg, eventTexts.joinToString("  —  "))
            }
            return
        }

        if (pkg == "com.custom.carlauncher") return

        // 2. Check if CarPlay is active
        val isCarPlayPackage = pkg.contains("zlink") || pkg.contains("tlink") ||
                pkg.contains("carplay") || pkg.contains("speedplay") ||
                pkg.contains("autokit") || pkg.contains("phonemirrorbox")

        if (isCarPlayPackage) {
            sendBroadcast(Intent("com.custom.carlauncher.CARPLAY_ACTIVE").putExtra("connected", true))
        }

        // 3. Check if event is from Radio or Media App
        val prefs = getSharedPreferences("agama_subaru_prefs", Context.MODE_PRIVATE)
        val mappedRadioPkg = prefs.getString("pkg_RADIO", "")?.lowercase(Locale.US).orEmpty()
        val mappedMusicPkg = prefs.getString("pkg_MUSIC", "")?.lowercase(Locale.US).orEmpty()

        val isRadioPackage = (mappedRadioPkg.isNotEmpty() && pkg == mappedRadioPkg) ||
                pkg.contains("radio") || pkg.contains("fm") || pkg.contains("car.ui") ||
                pkg.contains("xyauto") || pkg.contains("tw.") || pkg.contains("microntek") ||
                pkg.contains("syu") || pkg.contains("fyt")

        if (isRadioPackage) {
            val rootNode = rootInActiveWindow ?: event.source
            if (rootNode != null) {
                val found = parseAndBroadcastRadioNode(rootNode, prefs)
                if (found && prefs.getBoolean("pending_radio_peek", false)) {
                    prefs.edit().putBoolean("pending_radio_peek", false).apply()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        } else if (mappedMusicPkg.isNotEmpty() && pkg == mappedMusicPkg) {
            val rootNode = rootInActiveWindow ?: event.source
            if (rootNode != null) {
                parseAndBroadcastMusicNode(pkg, rootNode)
            }
        }
    }

    /**
     * Scans all interactive windows (including background/split windows) for Radio or Media text.
     */
    private fun scanAllInteractiveWindows(returnHomeAfter: Boolean) {
        val prefs = getSharedPreferences("agama_subaru_prefs", Context.MODE_PRIVATE)
        var foundStation = false

        try {
            for (window in windows) {
                val root = window.root ?: continue
                val winPkg = root.packageName?.toString()?.lowercase(Locale.US).orEmpty()
                if (winPkg.isEmpty() || winPkg == "com.custom.carlauncher") continue

                if (parseAndBroadcastRadioNode(root, prefs)) {
                    foundStation = true
                }
            }
        } catch (_: Exception) {}

        if (!foundStation) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                foundStation = parseAndBroadcastRadioNode(activeRoot, prefs)
            }
        }

        if (returnHomeAfter && prefs.getBoolean("pending_radio_peek", false)) {
            prefs.edit().putBoolean("pending_radio_peek", false).apply()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun parseAndBroadcastRadioNode(rootNode: AccessibilityNodeInfo, prefs: android.content.SharedPreferences): Boolean {
        val collectedTexts = mutableListOf<String>()
        collectNodeTexts(rootNode, collectedTexts)
        if (collectedTexts.isEmpty()) return false

        var detectedFreq = ""
        var detectedBand = if (collectedTexts.any {
                it.equals("AM", true) || it.equals("AM1", true) || it.equals("AM2", true)
            }) "AM" else "FM"
        val extraInfo = mutableListOf<String>()

        for (raw in collectedTexts) {
            val clean = raw.trim()
            if (clean.isEmpty()) continue
            val lower = clean.lowercase(Locale.US)

            if (lower in ignoreUiWords) continue
            if (clean.contains(":") && (lower.endsWith("am") || lower.endsWith("pm") || clean.length <= 5)) continue

            val fmMatcher = fmRegex.matcher(clean)
            if (fmMatcher.find()) {
                detectedFreq = "${fmMatcher.group(1)} FM"
                detectedBand = "FM"
                continue
            }

            if (detectedFreq.isEmpty() && detectedBand == "AM") {
                val amMatcher = amRegex.matcher(clean)
                if (amMatcher.find()) {
                    detectedFreq = "${amMatcher.group(1)} AM"
                    continue
                }
            }

            if (clean.length in 2..48 && !clean.all { it.isDigit() || it == '.' }) {
                if (!extraInfo.contains(clean)) {
                    extraInfo.add(clean)
                }
            }
        }

        if (detectedFreq.isNotEmpty()) {
            val fullStationText = if (extraInfo.isNotEmpty()) {
                "$detectedFreq  •  ${extraInfo.take(2).joinToString("  •  ")}"
            } else {
                detectedFreq
            }

            prefs.edit().putString("last_radio_text", fullStationText).apply()
            val updateIntent = Intent("com.custom.carlauncher.RADIO_SCRAPED").apply {
                putExtra("station_text", fullStationText)
            }
            sendBroadcast(updateIntent)
            return true
        }
        return false
    }

    private fun parseAndBroadcastMusicNode(pkg: String, rootNode: AccessibilityNodeInfo) {
        val collectedTexts = mutableListOf<String>()
        collectNodeTexts(rootNode, collectedTexts)
        val meaningful = collectedTexts.filter {
            val l = it.lowercase(Locale.US)
            it.length in 3..60 && !it.contains(":") && l !in ignoreUiWords
        }.distinct()
        if (meaningful.isNotEmpty()) {
            broadcastCapturedMedia(pkg, meaningful.take(2).joinToString("  —  "))
        }
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            out.add(t)
        }
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrEmpty() && d != t) {
            out.add(d)
        }
        val count = node.childCount
        for (i in 0 until count) {
            collectNodeTexts(node.getChild(i), out)
        }
    }

    private fun broadcastCapturedMedia(pkg: String, text: String) {
        val intent = Intent("com.custom.carlauncher.MEDIA_NOTIFICATION_SCRAPED").apply {
            putExtra("pkg", pkg)
            putExtra("text", text)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}
}
