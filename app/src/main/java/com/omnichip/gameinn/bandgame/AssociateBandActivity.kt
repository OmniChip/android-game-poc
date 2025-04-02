package com.omnichip.gameinn.bandgame

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children

class AssociateBandActivity : AppCompatActivity() {
    lateinit var strap_group: ViewGroup
    lateinit var strap_buttons: Array<RadioButton>
    lateinit var calreset_box: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assoc)

        val saved_player = intent.getIntExtra(ID_PLAYER, 0)
        val saved_usage= intent.getIntExtra(ID_USAGE, 0)
        val saved_calreset= intent.getBooleanExtra(ID_CALRESET, false)

        val player = savedInstanceState?.getInt(ID_PLAYER) ?: saved_player
        val usage = savedInstanceState?.getInt(ID_USAGE) ?: saved_usage
        val calreset = savedInstanceState?.getBoolean(ID_CALRESET) ?: saved_calreset

        findViewById<NumberPicker>(R.id.player_num).apply {
            minValue = 0
            maxValue = 4
            setOnValueChangedListener { _, _, new ->
                setState(new, -1, calreset_box.isChecked)
            }
        }

        findViewById<Button>(R.id.assoc_revert).setOnClickListener {
            setState(saved_player, saved_usage, saved_calreset)
        }

        findViewById<Button>(R.id.assoc_save).setOnClickListener {
            val bundle = Bundle()
            onSaveInstanceState(bundle)
            val data = Intent()
            data.putExtra(ID_PLAYER, bundle.getInt(ID_PLAYER))
            data.putExtra(ID_USAGE, bundle.getInt(ID_USAGE))
            data.putExtra(ID_CALRESET, bundle.getBoolean(ID_CALRESET))
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        val torso_radio_btn = findViewById<RadioButton>(R.id.strap_torso)!!
        findViewById<TextView>(R.id.strap_torso_txt).setOnClickListener {
            torso_radio_btn.performClick()
        }

        strap_group = findViewById<ViewGroup>(R.id.grp_strap)
        strap_buttons = strap_group.children.mapNotNull { it as? RadioButton }.toList().toTypedArray()
        calreset_box = findViewById<CheckBox>(R.id.calib_reset)

        strap_buttons.forEach { btn ->
            btn.tag = (btn.tag as String).toInt()

            btn.setOnClickListener {
                for (other in strap_buttons)
                    other.isChecked = other == it
                strap_group.tag = it.tag
            }
        }

        strap_group.tag = usage
        strap_buttons.sortBy { it.tag as Int }
        strap_buttons.withIndex().forEach {
            assert(it.value.tag as Int == it.index)
        }

        findViewById<TextView>(R.id.band_name).text = intent.getStringExtra(ID_NAME)
        setState(player, usage, calreset)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putInt(ID_PLAYER, findViewById<NumberPicker>(R.id.player_num).value)
        outState.putInt(ID_USAGE, strap_group.tag as Int)
        outState.putBoolean(ID_CALRESET, calreset_box.isChecked)
    }

    private fun setState(player: Int, usage: Int, calreset: Boolean) {
        val band_used = player > 0

        findViewById<View>(R.id.assoc_none).visibility = if (band_used) View.INVISIBLE else View.VISIBLE
        findViewById<NumberPicker>(R.id.player_num).value = player
        strap_group.visibility = if (band_used) View.VISIBLE else View.INVISIBLE
        strap_buttons.getOrNull(usage)?.callOnClick()
        calreset_box.isChecked = calreset
    }

    companion object {
        const val BAND_ASSOC_HAND_LEFT = 0
        const val BAND_ASSOC_HAND_RIGHT = 1
        const val BAND_ASSOC_LEG_LEFT = 2
        const val BAND_ASSOC_LEG_RIGHT = 3
        const val BAND_ASSOC_TORSO = 4
        const val BAND_ASSOC_USAGE = 0x7
        const val BAND_ASSOC_PLAYER_SHIFT = 3

        const val ID_NAME = "name"
        const val ID_PLAYER = "player"
        const val ID_USAGE = "usage"
        const val ID_CALRESET = "reset_calibration"

        fun start(parent: Activity, band: BandDevice, requestCode: Int) =
            Intent(parent, AssociateBandActivity::class.java).apply {
                putExtra(ID_NAME, band.identity)
                putExtra(ID_PLAYER, band.association shr BAND_ASSOC_PLAYER_SHIFT)
                putExtra(ID_USAGE, band.association and BAND_ASSOC_USAGE)
                parent.startActivityForResult(this, requestCode)
            }

        fun updateBand(band: BandDevice, intent: Intent) {
            val player = intent.getIntExtra(ID_PLAYER, 0)
            val usage = intent.getIntExtra(ID_USAGE, 0)
            band.association = (player shl BAND_ASSOC_PLAYER_SHIFT) or (usage and BAND_ASSOC_USAGE)
            if (intent.getBooleanExtra(ID_CALRESET, false))
                band.resetCalibration()
        }

        fun getAssociationName(association: Int): String {
            val player = association shr BAND_ASSOC_PLAYER_SHIFT
            val usage = association and BAND_ASSOC_USAGE
            if (player == 0)
                return "(unused)"

            val entity = when (usage) {
                BAND_ASSOC_HAND_LEFT -> "left hand"
                BAND_ASSOC_HAND_RIGHT -> "right hand"
                BAND_ASSOC_LEG_LEFT -> "left leg"
                BAND_ASSOC_LEG_RIGHT -> "right leg"
                BAND_ASSOC_TORSO -> "torso"
                else -> "(bad value)"
            }
            return String.format("player %d's %s", player, entity)
        }
    }
}