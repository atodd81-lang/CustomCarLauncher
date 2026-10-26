package com.custom.carlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.regex.Pattern

class RadioScreenReaderService : AccessibilityService() {

    // Matches FM frequencies like 92.3, 101.5, 88.10 or AM frequencies like 680, 1010
    private val fmRegex = Pattern.compile("\\b(8[7-9]\\.[0-9]{1,2}|9[0-9]\\.[0-9]{1,2}|10[0-8]\\.[0-9]{1,2})\\b")
    private val amRegex = Pattern.compile("\\b([5-9][0-9]0|1[0-7][0-9]0)\\b")

    private val ignoreUiWords = setOf(
        "playing", "band", "settings", "sensitivity", "loc", "dx", "rds enable",
        "af enable", "area select", "usa", "europe", "fm", "fm1", "fm2", "fm3",
        "am", "am1", "am2", "st", "mono", "mhz", "khz", "eq", "aps", "seek"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase(Locale.US) ?: return

        // Ignore our own launcher
        if (pkg == "com.custom.carlauncher") return

        // 1. Check if event is a media notification/toast from a music or radio app
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val texts = event.text.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            if (texts.isNotEmpty()) {
                val combined = texts.joinToString(" — ")
                broadcastCapturedText(pkg, combined)
            }
            return
        }

        // 2. Check if the active window belongs to the Radio app or a known XYAuto/Car UI package
        val prefs = getSharedPreferences("agama_subaru_prefs", Context.MODE_PRIVATE)
        val mappedRadioPkg = prefs.getString("pkg_RADIO", "")?.lowercase(Locale.US).orEmpty()

        val isRadioPackage = (mappedRadioPkg.isNotEmpty() && pkg == mappedRadioPkg) ||
                pkg.contains("radio") ||
                pkg.contains("fm") ||
                pkg.contains("car.ui") ||
                pkg.contains("xyauto") ||
                pkg.contains("tw.") ||
                pkg.contains("microntek") ||
                pkg.contains("syu") ||
                pkg.contains("fyt")

        val isCarPlayPackage = pkg.contains("zlink") || pkg.contains("tlink") ||
                pkg.contains("carplay") || pkg.contains("speedplay") ||
                pkg.contains("autokit") || pkg.contains("phonemirrorbox")

        if (isCarPlayPackage) {
            // Notify launcher that CarPlay UI is actively running and connected
            val cpIntent = Intent("com.custom.carlauncher.CARPLAY_ACTIVE").apply {
                putExtra("connected", true)
            }
            sendBroadcast(cpIntent)
        }

        if (!isRadioPackage) return

        val rootNode = rootInActiveWindow ?: event.source ?: return
        val collectedTexts = mutableListOf<String>()
        collectNodeTexts(rootNode, collectedTexts)

        if (collectedTexts.isEmpty()) return

        var detectedFreq = ""
        var detectedBand = if (collectedTexts.any { it.equals("AM", true) || it.equals("AM1", true) || it.equals("AM2", true) }) "AM" else "FM"
        val extraInfo = mutableListOf<String>()

        for (raw in collectedTexts) {
            val clean = raw.trim()
            if (clean.isEmpty()) continue
            val lower = clean.lowercase(Locale.US)

            if (lower in ignoreUiWords) continue
            // Skip clock strings like 8:21 AM
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

            // Capture RDS Call Letters, Station Name, Genre (PTY), or Radio Text
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

    private fun broadcastCapturedText(pkg: String, text: String) {
        val intent = Intent("com.custom.carlauncher.MEDIA_NOTIFICATION_SCRAPED").apply {
            putExtra("pkg", pkg)
            putExtra("text", text)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}
}
