package net.webstas.sleepsense

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Repeat

private const val TAG = "SpotifyRemote"

/**
 * Plays and pauses a Spotify playlist through the Spotify app's own remote interface, so it works without
 * opening Spotify on screen. The first connection asks Spotify (once) to let this app control it, which
 * needs the app in front; later ones connect in the background. The app is registered at developer.spotify.com
 * with this package name, the signing key's fingerprint and [REDIRECT_URI].
 */
object SpotifyRemote {
    private const val CLIENT_ID = "c846ae70c3f047b4b1d71c27d5910fde" // identifies the app to Spotify; not a secret
    private const val REDIRECT_URI = "sleepsense://spotify-callback"

    private var remote: SpotifyAppRemote? = null

    // The SDK makes handlers for its answers, so it must be called from a thread with a looper: the main one
    private val main = Handler(Looper.getMainLooper())

    /** Connects, asking Spotify's permission when [allowPrompt] (the app is in front); calls back with whether it worked. */
    fun connect(context: Context, allowPrompt: Boolean, done: (Boolean) -> Unit) {
        main.post {
            try {
                connectOnMain(context, allowPrompt, done)
            } catch (e: Exception) {
                Log.w(TAG, "could not connect to Spotify: $e")
                done(false)
            }
        }
    }

    private fun connectOnMain(context: Context, allowPrompt: Boolean, done: (Boolean) -> Unit) {
        remote?.takeIf { it.isConnected }?.let { done(true); return }
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(allowPrompt)
            .build()
        SpotifyAppRemote.connect(context.applicationContext, params, object : Connector.ConnectionListener {
            override fun onConnected(connected: SpotifyAppRemote) {
                remote = connected
                done(true)
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "could not connect to Spotify: $error")
                done(false)
            }
        })
    }

    /** Plays [address] ("spotify:playlist:...") in order and repeating, once connected; calls back with whether it could. */
    fun play(context: Context, address: String, done: (Boolean) -> Unit) = connect(context, false) { connected ->
        val player = remote?.takeIf { connected }?.playerApi
        if (player == null) {
            done(false)
            return@connect
        }
        player.setShuffle(false)
        player.setRepeat(Repeat.ALL)
        player.play(address)
        done(true)
    }

    fun pause() {
        main.post { remote?.takeIf { it.isConnected }?.playerApi?.pause() }
    }

    fun disconnect() {
        main.post {
            remote?.let { SpotifyAppRemote.disconnect(it) }
            remote = null
        }
    }
}
