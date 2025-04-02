package com.omnichip.gameinn.bandgame

typealias PlayerGestureCallback = (PlayerBandState, BandData) -> Unit

class PlayerBandState(val which: Int, val band: BandDevice, data_cb: PlayerGestureCallback) {
    private val on_saved_new_data = band.on_new_data
    private var last_ts = 0L
    var start_ts = 0L
        private set

    init {
        band.on_new_data = { it, data ->
            last_ts = data.timestamp
            data_cb(this, data)
            on_saved_new_data?.invoke(it, data)
        }
    }

    fun cleanup() {
        band.on_new_data = on_saved_new_data
    }

    fun reset() {
        start_ts = last_ts
    }
}

class PlayerState(val index: Int) {
    private val bands = hashMapOf<Int, PlayerBandState>()
    private val detector = BandDetector()
    var on_hit: ((PlayerState)->Unit)? = null

    val bands_present
       get() = bands.keys.toSet().fold(0) { acc, v -> acc or (1 shl v) }
    val gestures_available
        get() = detector.getAvailableGestures(bands_present) and BandDetector.ALL_GESTURES_MASK

    var hit_delays = 0L
        private set
    var hit_completed = 0
        private set
    var wanted_gestures = 0L
        private set

    fun addBand(band: BandDevice, which: Int) {
        bands[which] = PlayerBandState(which, band) { pstate, data ->
            for (gs in detector.processData(which, data))
                checkGesture(gs, gs.timestamp - pstate.start_ts)
        }
    }

    fun removeBand(band: BandDevice) {
        val which = bands.entries.find { it.value.band == band }
        which?.run {
            value.cleanup()
            bands.remove(key)
        }
    }

    fun enableGestures(gestures: Long) {
        detector.gestures = gestures
    }

    var hit_timestamp: Long? = null
        private set

    private fun checkGesture(gs: GestureData, ts: Long) {
        synchronized(this) {
            if ((1L shl gs.type) and wanted_gestures == 0L)
                return

            wanted_gestures = 0L
            hit_timestamp = ts
        }
        on_hit?.invoke(this)
    }

    fun stopWaiting() {
        synchronized(this) {
            wanted_gestures = 0L
        }
    }

    fun updateRoundStats(first_ts: Long) {
        val hit = hit_timestamp
        if (hit == null)
            return

        hit_delays += hit - first_ts
        ++hit_completed
    }

    fun reset(wanted: Long) {
        synchronized(this) {
            wanted_gestures = wanted
            hit_timestamp = null
            bands.entries.forEach { it.value.reset() }
            detector.resetDetection()
        }
    }

    fun vibeAll(vibes: ByteArray) {
        bands.values.forEach {
            it.band.triggerVibe(vibes)
        }
    }

    fun cleanup() {
        while (bands.isNotEmpty()) {
            removeBand(bands.values.first().band)
        }
        detector.cleanup()
    }
}