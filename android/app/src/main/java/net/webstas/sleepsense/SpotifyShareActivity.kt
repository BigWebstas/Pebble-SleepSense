package net.webstas.sleepsense

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * What Spotify's Share button hands over: choosing SleepSense in the share sheet sends the playlist's link here.
 * The link becomes the sleep sound, Spotify is asked once to let this app control it, and the app comes forward.
 */
class SpotifyShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val address = SpotifyLink.parse(shared)
        if (address == null) {
            Toast.makeText(this, R.string.white_noise_spotify_invalid, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        WhiteNoisePrefs.setSpotify(this, address)
        if (WhiteNoisePlayer.isPlaying) WhiteNoisePlayer.restart(this)
        // The one-time permission, asked while this is in front; the answer comes back here
        SpotifyRemote.connect(this, true) { ok ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.white_noise_spotify_saved else R.string.white_noise_spotify_not_connected,
                    Toast.LENGTH_LONG,
                ).show()
                if (ok) SpotifyRemote.disconnect()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                finish()
            }
        }
    }
}
