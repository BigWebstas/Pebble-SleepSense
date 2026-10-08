package net.webstas.sleepsense

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch

/** Where the MQTT broker for Home Assistant is set up. */
class HomeAssistantActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val statusLoop = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    // Android 17 only lets an app reach other devices on the home network once this is granted
    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            HomeAssistant.start(this)
            refreshStatus()
        }

    private fun localNetworkGranted() =
        checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun <T : android.view.View> id(res: Int): T = findViewById(res)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_home_assistant)
        id<android.view.View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val saved = MqttSettings.load(this)
        id<MaterialSwitch>(R.id.ha_enable).isChecked = saved.enabled
        id<EditText>(R.id.ha_host).setText(saved.host)
        id<EditText>(R.id.ha_port).setText(saved.port.toString())
        id<EditText>(R.id.ha_user).setText(saved.user)
        id<EditText>(R.id.ha_password).setText(saved.password)

        id<Button>(R.id.ha_save).setOnClickListener {
            MqttSettings(
                enabled = id<MaterialSwitch>(R.id.ha_enable).isChecked,
                host = id<EditText>(R.id.ha_host).text.toString().trim(),
                port = id<EditText>(R.id.ha_port).text.toString().toIntOrNull() ?: 1883,
                user = id<EditText>(R.id.ha_user).text.toString().trim(),
                password = id<EditText>(R.id.ha_password).text.toString(),
            ).save(this)
            HomeAssistant.start(this)
            if (id<MaterialSwitch>(R.id.ha_enable).isChecked && !localNetworkGranted()) {
                requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
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
        id<TextView>(R.id.ha_status).setText(HomeAssistant.statusText(this))
    }
}
