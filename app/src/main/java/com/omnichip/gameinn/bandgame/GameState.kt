package com.omnichip.gameinn.bandgame

import android.os.Handler
import java.util.*

class GameState(val soundPlayer: SoundPlayer, private val loop: Handler) {
    private val timer = Timer()

    private val players = hashMapOf<Int, PlayerState>()
    private val allowed_exercises = mutableSetOf<Int>()
    private var allowed_exercise_mask = 0L
    public val util = LogUtil()
    public var on_game_over: (() -> Unit)? = null
    private var n_players = 0

    private var started_ok = false
    private var prev_gesture = 0
    private var n_hit = 0
    private var n_rounds_left = 0

    public val started: Boolean
        get() = n_rounds_left > 0
    public val player_ids
        get() = players.keys.toSet()

    companion object {
        const val ROUND_TIME_SECS = 5
    }

    init {
        soundPlayer.onSoundCompleted { loop.post { soundCompleted() } }
    }

    fun addBand(band: BandDevice, player: Int, which: Int) {
        if (player <= 0)
            throw IllegalArgumentException("no need for a band w/o player")
        if (!players.containsKey(player)) {
            ++n_players
            players[player] = PlayerState(player).apply {
                on_hit = { loop.post { onPlayerHit(this) } }
            }
        }

        players[player]!!.addBand(band, which)
    }

    fun removeBand(band: BandDevice) {
        var remove: PlayerState? = null
        players.values.forEach {
            it.removeBand(band)
            if (it.bands_present == 0)
                remove = it
        }
        if (remove != null) {
            players.remove(remove!!.index)
            --n_players;
        }
    }

    fun pruneBands() {
        var all_excercises = BandDetector.ALL_GESTURES_MASK

        players.values.forEach {
            all_excercises = all_excercises and it.gestures_available
        }

        players.values.forEach {
            it.enableGestures(all_excercises)
        }

        allowed_exercises.clear()
        allowed_exercises.addAll(all_excercises.listOfBits())
        allowed_exercise_mask = all_excercises
    }

    private var round_timeout: TimerTask = object : TimerTask() { override fun run() {} }
    init {
        round_timeout.cancel()
    }

    private fun soundCompleted() {
        if (n_rounds_left == 0) {
            if (started_ok) {
                started_ok = false
                on_game_over?.invoke()
            }
            return
        }

        if (n_hit == n_players) {
            roundFinished()
            return
        }

        round_timeout = object : TimerTask() {
            override fun run() {
                loop.post {
                    roundFinished()
                }
            }
        }
        timer.schedule(round_timeout, ROUND_TIME_SECS * 1000L)
    }

    private fun getNextRandomGesture(): Int {
        val prev = prev_gesture
        val prev_mask = 1L shl prev
        val invalid_mask: Long
        if (prev_mask and BandDetector.POINT_GESTURE_MASK != 0L) {
            val shift = if (prev < BandDetector.POINT_LEFT_LEG_DOWN) 5 else 3
            val offset = if (prev < BandDetector.POINT_LEFT_LEG_DOWN) BandDetector.POINT_LEFT_HAND_DOWN else BandDetector.POINT_LEFT_LEG_DOWN

            val same_level_mask: Long
            if (prev_mask and BandDetector.POINT_ANY_MASK != 0L)
                same_level_mask = (1L or (1L shl shift)) shl (prev - offset - 2 * shift)
            else
                same_level_mask = 1L shl ((prev - offset) % shift + 2 * shift)
            invalid_mask = prev_mask or same_level_mask
        } else {
            invalid_mask = prev_mask
        }

        if (allowed_exercise_mask and invalid_mask.inv() == 0L)
            return allowed_exercises.random()

        while (true) {
            val gi = allowed_exercises.random()
            if ((1L shl gi) and invalid_mask == 0L)
                return gi
        }
    }

    private fun startNextRound() {
        val gi = getNextRandomGesture()
        prev_gesture = gi

        util.log("%d rounds left, next excercise: %d", n_rounds_left, gi)

        players.values.forEach {
            it.reset(1L shl gi)
        }
        n_hit = 0

        soundPlayer.playExcerciseSound(gi)
    }

    private fun onPlayerHit(player: PlayerState) {
        util.log("player %d finished", player.index)
        if (++n_hit != n_players)
            return

        if (round_timeout.cancel())
            roundFinished()
    }

    private fun roundFinished() {
        var first_ts: Long? = null
        players.values.forEach {
            it.stopWaiting()
            it.hit_timestamp?.let {
                if (first_ts == null || first_ts!! > it)
                    first_ts = it
            }
        }

        if (first_ts == null) {
            // nobody got it
            startNextRound()
            return
        }

        players.values.forEach {
            it.updateRoundStats(first_ts!!)
        }

        if (--n_rounds_left > 0)
            startNextRound()
        else
            finishGame()
    }

    private fun finishGame() {
        var best_player = 0
        var best_times = 0L
        var best_count = 0

        val better_player = fun(pstate: PlayerState): Boolean {
            if (pstate.hit_completed > best_count)
                return true
            if (pstate.hit_completed < best_count)
                return false
            return pstate.hit_delays < best_times
        }

        players.values.forEach {
            if (better_player(it)) {
                best_player = it.index
                best_times = it.hit_delays
                best_count = it.hit_completed
            }
        }

        util.log("game result: player %d won", best_player)
        if (best_player == 0)
            return  // FIXME: bug

        soundPlayer.playPlayerWins(best_player)

        val vibes = ByteArray(8) { 0x01 }
        vibes[2] = 0x8a.toByte()
        vibes[5] = 0x8a.toByte()
        players[best_player]?.vibeAll(vibes)
    }

    fun start(rounds: Int = 5) {
        if (rounds <= 0)
            throw IllegalArgumentException("no rounds?")
        if (n_players == 0)
            throw IllegalStateException("no players")
        pruneBands()
        if (allowed_exercises.isEmpty())
            throw IllegalStateException("no common bands")

        started_ok = true
        prev_gesture = 0
        n_rounds_left = rounds
        startNextRound()
    }

    fun cleanup() {
        players.values.forEach {
            it.cleanup()
        }
        players.clear()
    }
}