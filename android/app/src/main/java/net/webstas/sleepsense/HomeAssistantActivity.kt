package net.webstas.sleepsense

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Where the MQTT broker for Home Assistant is set up. */
class HomeAssistantActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val statusLoop = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private fun <T : android.view.View> id(res: Int): T = findViewById(res)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_assistant)
        id<android.view.View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val saved = MqttSettings.load(this)
        id<Switch>(R.id.ha_enable).isChecked = saved.enabled
        id<EditText>(R.id.ha_host).setText(saved.host)
        id<EditText>(R.id.ha_port).setText(saved.port.toString())
        id<EditText>(R.id.ha_user).setText(saved.user)
        id<EditText>(R.id.ha_password).setText(saved.password)

        id<Button>(R.id.ha_save).setOnClickListener {
            MqttSettings(
                enabled = id<Switch>(R.id.ha_enable).isChecked,
                host = id<EditText>(R.id.ha_host).text.toString().trim(),
                port = id<EditText>(R.id.ha_port).text.toString().toIntOrNull() ?: 1883,
                user = id<EditText>(R.id.ha_user).text.toString().trim(),
                password = id<EditText>(R.id.ha_password).text.toString(),
            ).save(this)
            HomeAssistant.start(this)
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(statusLoop)
        super.onPause()
    }

    private fun refreshStatus() {
        id<TextView>(R.id.ha_status).setText(
            when {
                !MqttSettings.load(this).enabled -> R.string.ha_off
                HomeAssistant.connected -> R.string.ha_connected
                else -> R.string.ha_disconnected
            },
        )
    }
}
