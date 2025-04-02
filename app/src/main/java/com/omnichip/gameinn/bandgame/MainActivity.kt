package com.omnichip.gameinn.bandgame

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    val bands = BandManager()
    private var conf_band: BandDevice? = null
    lateinit var sound: SoundPlayer
        private set
    lateinit var game: GameState
        private set

    fun addBandEntry(band: BandDevice) {
        //band.util.log("entry: %s", if (band.cb_data == null) "new" else "duplicate")
        if (band.cb_data != null)
            return

        LayoutInflater.from(this).inflate(R.layout.band_element, band_list, false).apply {
            findViewById<TextView>(R.id.band_name).text = band.identity
            isEnabled = !game.started
            band_list.addView(this)
            band.cb_data = this

            updateBandAssociation(band)
            setOnClickListener {
                this@MainActivity.triggerBandVibe(band)
                conf_band = band
                AssociateBandActivity.start(this@MainActivity, band, 1)
            }
        }

        sound.playSound(SoundPlayer.NEW_BAND)
        band.startListening()
    }

    fun removeBandEntry(band: BandDevice) {
        //band.util.log("entry: %s", if (band.cb_data == null) "non-existent" else "removing")
        (band.cb_data as View?)?.let {
            band_list.removeView(it)
        }
        band.cb_data = null
    }

    fun updateBandData(band: BandDevice, data: BandData) {
        (band.cb_data as View?)?.apply {
            findViewById<TextView>(R.id.band_acc).text = String.format("%+6d  %+6d  %+6d", data.ax, data.ay, data.az)
            findViewById<TextView>(R.id.band_rot).text = String.format("%+6d  %+6d  %+6d", data.gx, data.gy, data.gz)
            findViewById<TextView>(R.id.band_ts).text = String.format("%12d", data.timestamp)
        }
    }

    fun updateBandCalib(band: BandDevice, status: String) {
        (band.cb_data as View?)?.apply {
            findViewById<TextView>(R.id.band_calib).text = status
        }
    }

    fun updateBandAssociation(band: BandDevice) {
        (band.cb_data as View?)?.apply {
            val player = band.association shr AssociateBandActivity.BAND_ASSOC_PLAYER_SHIFT
            val usage = band.association and AssociateBandActivity.BAND_ASSOC_USAGE
            findViewById<TextView>(R.id.band_assoc).text =
                AssociateBandActivity.getAssociationName(band.association)

            game.removeBand(band)
            if (player > 0)
                game.addBand(band, player, usage)
        }

        val player_ids = game.player_ids.fold("") { str, id ->
            String.format("%s%s%d", str, if (str.isEmpty()) "" else ", ", id)
        }
        findViewById<TextView>(R.id.player_list).text = if (player_ids.isEmpty()) "(none)" else player_ids
    }

    fun triggerBandVibe(band: BandDevice) {
        val vibes = ByteArray(8) { 0x01 }
        band.triggerVibe(vibes)
    }

    private val ts_log_format = SimpleDateFormat("HH:mm:ss.SSS ", Locale.US)

    private fun debugLog(it: String) {
        Log.i("BLE", it)

        TextView(this).apply {
            text = ts_log_format.format(Date()) + it
        } .also {
            logs.addView(it, 0)
        }
    }

    private fun testLog(it: String) {
        Log.i("TEST", it)
        Handler(mainLooper).post {
            gesture_list.addView(TextView(this).apply { text = it }, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.start_button).setOnClickListener { startGame() }
        findViewById<TextView>(R.id.test_button).setOnClickListener { toggleTest() }
        findViewById<TextView>(R.id.player_list).text = "(none)"

        val loop = Handler(mainLooper)
        bands.util.onLog {
            loop.post { debugLog(it) }
        }

        sound = SoundPlayer(this)
        sound.util.onLog(bands.util)
        //sound.playSound(SoundPlayer.WELCOME)

        game = GameState(sound, loop)
        game.util.onLog(bands.util)
        game.on_game_over = { onGameOver() }

        if (!BandManager.can_calibrate)
            debugLog("WARN: calibration library not loaded")

        bands.on_band_found = {
            it.on_new_data = { band, data -> loop.post { updateBandData(band, data) } }
            it.on_calibration_change = { band -> loop.post { updateBandCalib(band, band.calibrationStatus) } }

            addBandEntry(it)
        }
        bands.on_band_lost = {
            removeBandEntry(it)
        }

        val perms = arrayOf(Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!doAskForPermissions(perms))
            doInitialize()
    }

    override fun onRestart() {
        super.onRestart()
        debugLog("APP restarted")
    }

    override fun onResume() {
        super.onResume()
        debugLog("APP resumed")
    }

    override fun onStart() {
        super.onStart()
        debugLog("APP started")
    }

    override fun onStop() {
        super.onStop()
        debugLog("APP stopped")
    }

    override fun onDestroy() {
        debugLog("APP destroying")
        bands.cleanup()
        super.onDestroy()
    }

    private fun doInitialize() {
        try {
            bands.initialize(this)
        } catch (e: IllegalStateException) {
            debugLog("state error: " + e.message)
        } catch (e: RuntimeException) {
            debugLog("init error: " + e.message)
        }
    }

    private fun doAskForPermissions(perms: Array<String>): Boolean
    {
        var do_ask = true
        var need_perm = false

        perms.forEach {
            if (ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED)
                return@forEach
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, it))
                need_perm = true
            else
                do_ask = false
        }

        if (!need_perm || !do_ask)
            return false

        ActivityCompat.requestPermissions(this, perms, 1)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != 1)
            return

        if (grantResults.isEmpty())
            debugLog("permissions query cancelled")
        else
            for ((i, perm) in permissions.withIndex())
                debugLog(String.format("permission %s %s", perm, if (grantResults[i] == PackageManager.PERMISSION_GRANTED) "granted" else "denied"))

        doInitialize()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            0 -> {
                debugLog(String.format("user %saccepted to enable BT",
                                       if (resultCode == Activity.RESULT_OK) "" else "NOT "))
                if (resultCode == Activity.RESULT_OK)
                    doInitialize()
            }

            1 -> {
                if (data == null || resultCode != Activity.RESULT_OK) {
                    conf_band?.let {
                        debugLog(String.format("band setting cancelled for %s", it.identity))
                    }
                    return
                }

                conf_band?.let {
                    if (it.identity.equals(data.getStringExtra("name")))
                        return@let
                    AssociateBandActivity.updateBand(it, data)
                    updateBandAssociation(it)
                }
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun enableViews(enable: Boolean) {
        findViewById<View>(R.id.start_button).isEnabled = enable
        findViewById<View>(R.id.test_button).isEnabled = enable
        bands.bands.forEach {
            (it.cb_data as View?)?.isEnabled = enable
        }
    }

    private fun enableTestViews(enable: Boolean) {
        findViewById<View>(R.id.test_button).isEnabled = enable
        findViewById<View>(R.id.scroll_bands).visibility = if (enable) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.scroll_gestures).visibility = if (enable) View.VISIBLE else View.INVISIBLE
    }

    private fun startGame() {
        enableViews(false)

        debugLog("starting game")
        try {
            game.start()
        } catch (e: Exception) {
            debugLog("game start failed: " + e.toString())
            enableViews(true)
        }
    }

    private fun onGameOver() {
        debugLog("game ended")
        enableViews(true)
    }

    private var in_test_mode = false

    private fun toggleTest() {
        synchronized(this) {
            if (!in_test_mode) {
                enableTestMode()
                in_test_mode = true
            } else {
                disableTestMode()
                in_test_mode = false
            }
        }
    }

    private val saved_cbs = mutableListOf<PlayerBandState>()

    private fun enableTestMode() {
        enableViews(false)
        enableTestViews(true)
        debugLog("starting test")

        val player_det = Array<BandDetector>(4) { BandDetector() }
        val player_bands = Array<Int>(4) { 0 }

        bands.bands.forEach {
            val player = it.association shr AssociateBandActivity.BAND_ASSOC_PLAYER_SHIFT
            val usage = it.association and AssociateBandActivity.BAND_ASSOC_USAGE

            if (player <= 0 || player > player_det.size)
                return@forEach

            val usage_bit = 1 shl usage
            if (player_bands[player - 1] and usage_bit != 0)
                return@forEach
            player_bands[player - 1] = player_bands[player - 1] or usage_bit

            val det = player_det[player - 1]
            PlayerBandState(usage, it) { _, data ->
                for (gd in det.processData(usage, data)) {
                    val gest_name = BandDetector.gestureName.getOrElse(gd.type) { "[unknown]" }
                    testLog(String.format("gesture for p%d/e%d at %d: %s (%d)",
                        player, usage, gd.timestamp, gest_name, gd.type
                    ))
                }
            }.let {
                saved_cbs.add(it)
            }
        }

        player_det.zip(player_bands).withIndex().forEach {
            val det = it.value.first
            val band_mask = it.value.second
            val gesture_mask = det.getAvailableGestures(band_mask) and BandDetector.CONTINUOUS_GESTURES_MASK.inv()

            det.gestures = gesture_mask
            if (band_mask != 0)
                debugLog(String.format("player %d: bands 0x%02x gestures 0x%08x", it.index + 1, band_mask, gesture_mask))
        }

        testLog("---TEST---")
    }

    private fun disableTestMode() {
        debugLog("stopping test")

        saved_cbs.forEach { it.cleanup() }
        saved_cbs.clear()

        enableTestViews(false)
        enableViews(true)
    }
}
