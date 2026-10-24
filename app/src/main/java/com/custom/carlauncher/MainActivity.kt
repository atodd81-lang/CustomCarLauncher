package com.custom.carlauncher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.custom.carlauncher.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaHelper: MediaControlHelper
    private lateinit var locationManager: LocationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaHelper = MediaControlHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setupControls()
        runSubaruStartupAnimation()
        startSpeedometer()
    }

    private fun setupControls() {
        binding.btnPrev.setOnClickListener { mediaHelper.prevTrack() }
        binding.btnNext.setOnClickListener { mediaHelper.nextTrack() }
        binding.btnPlayPause.setOnClickListener { mediaHelper.playPause() }

        binding.btnApps.setOnClickListener { LauncherUtils.openAllApps(this) }
        binding.btnSettings.setOnClickListener { LauncherUtils.openHomeSettings(this) }

        binding.centralHub.setOnClickListener {
            if (binding.speedContainer.visibility == View.VISIBLE) {
                binding.speedContainer.visibility = View.GONE
                binding.imgSubaruLogo.visibility = View.VISIBLE
            } else {
                binding.speedContainer.visibility = View.VISIBLE
                binding.imgSubaruLogo.visibility = View.GONE
            }
        }

        binding.centralHub.setOnLongClickListener {
            Toast.makeText(this, "Opening Home Switcher...", Toast.LENGTH_SHORT).show()
            LauncherUtils.openHomeSettings(this)
            true
        }
    }

    private fun runSubaruStartupAnimation() {
        val fadeIn = ObjectAnimator.ofFloat(binding.imgSubaruLogo, View.ALPHA, 0f, 1f).setDuration(1000)
        val scaleX = ObjectAnimator.ofFloat(binding.imgSubaruLogo, View.SCALE_X, 0.75f, 1.0f).setDuration(1000)
        val scaleY = ObjectAnimator.ofFloat(binding.imgSubaruLogo, View.SCALE_Y, 0.75f, 1.0f).setDuration(1000)

        AnimatorSet().apply {
            playTogether(fadeIn, scaleX, scaleY)
            start()
        }
    }

    private fun startSpeedometer() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                1.0f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (location.hasSpeed()) {
                            val mph = (location.speed * 2.23694f).toInt()
                            binding.tvSpeed.text = mph.toString()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            )
        } catch (_: SecurityException) {
            binding.tvSpeed.text = "GPS"
        }
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
