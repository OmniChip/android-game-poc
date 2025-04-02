package com.omnichip.gameinn.bandgame

import android.content.Context
import android.media.MediaPlayer
import java.util.*

class SoundPlayer(ctx: Context) {
    private val mp: MediaPlayer
    public val util = LogUtil()
    private var on_sound_completed: (()->Unit)? = null
    private val queue = LinkedList<Int>()

    init {
        mp = MediaPlayer.create(ctx, R.raw.sounds)
        mp.setOnCompletionListener{
//            util.log("sound completed")
            soundCompleted()
        }
    }

    companion object {
        const val WELCOME = 0
        const val NEW_BAND = 1
        const val EXC_ANNOUNCE = 2
        const val EXC_HAND = 3
        const val EXC_LEG = 4
        const val EXC_LEFT = 5
        const val EXC_RIGHT = 6
        const val PITCH_DOWN = 7
        const val PITCH_LOW = 8
        const val PITCH_LEVEL = 9
        const val PITCH_HIGH = 10
        const val PITCH_UP = 11
        const val SQUAT = 12
        const val PLAYER1 = 13
        const val N_PLAYER_SOUNDS = 4
        const val PLAYER_WINS = PLAYER1 + N_PLAYER_SOUNDS
    }

    fun playSound(id: Int) {
        if (mp.isPlaying) {
            mp.stop()
            mp.prepare()
        }
        mp.seekTo(0)
        mp.selectTrack(id)
        mp.start()
//        util.log("sound started: %d", id)
    }

    fun playExcerciseSound(index: Int) {
        val mask = 1L shl index

        queue.add(EXC_ANNOUNCE)

        if (index == BandDetector.SQUAT_GESTURE) {
            queue.add(SQUAT)
        } else if (mask and BandDetector.POINT_GESTURE_MASK != 0L) {
            if (mask and BandDetector.POINT_LEFT_MASK != 0L)
                queue.add(EXC_LEFT)
            else if (mask and BandDetector.POINT_RIGHT_MASK != 0L)
                queue.add(EXC_RIGHT)

            if (mask and BandDetector.POINT_ARM_MASK != 0L)
                queue.add(EXC_HAND)
            else if (mask and BandDetector.POINT_LEG_MASK != 0L)
                queue.add(EXC_LEG)
            else
                assert(false)

            val pitch = if (mask and BandDetector.POINT_ARM_MASK != 0L)
                (index - BandDetector.POINT_LEFT_HAND_DOWN) % 5 else
                (index - BandDetector.POINT_LEFT_LEG_DOWN) % 3
            queue.add(PITCH_DOWN + pitch)
        } else {
            assert(false)
        }

        soundCompleted()
    }

    fun playPlayerWins(index: Int) {
        if (index > 0 && index <= N_PLAYER_SOUNDS)
            queue.add(PLAYER1 + index - 1)
        queue.add(PLAYER_WINS)

        soundCompleted()
    }

    private fun soundCompleted() {
        if (queue.isNotEmpty()) {
            playSound(queue.remove())
            return
        }

        on_sound_completed?.invoke()
    }

    fun onSoundCompleted(cb: (()->Unit)?) {
        on_sound_completed = cb
    }
}