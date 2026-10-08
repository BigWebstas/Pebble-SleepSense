package net.webstas.sleepsense

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.core.content.edit
import kotlin.math.max

private const val TAG = "WhiteNoisePlayer"

/** The available synthesized sleep sound types. */
enum class WhiteNoiseSound(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
) {
    BROWN("brown", R.string.white_noise_brown_title, R.string.white_noise_brown_desc),
    PINK("pink", R.string.white_noise_pink_title, R.string.white_noise_pink_desc),
    WHITE("white", R.string.white_noise_white_title, R.string.white_noise_white_desc),
}

/** User preferences for white noise playback. */
object WhiteNoisePrefs {
    private const val PREFS = "white_noise"
    private const val KEY_PLAY_WHILE_TRACKING = "play_while_tracking"
    private const val KEY_SOUND = "sound"

    fun isPlayWhileTracking(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PLAY_WHILE_TRACKING, false)

    fun setPlayWhileTracking(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PLAY_WHILE_TRACKING, enabled)
        }
    }

    fun sound(context: Context): WhiteNoiseSound {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOUND, WhiteNoiseSound.BROWN.id)
        return WhiteNoiseSound.entries.firstOrNull { it.id == id } ?: WhiteNoiseSound.BROWN
    }

    fun setSound(context: Context, sound: WhiteNoiseSound) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SOUND, sound.id)
        }
    }
}

/**
 * Real-time synthesis of continuous, seamless soothing sleep noises (Brown, Pink, White)
 * using an AudioTrack stream with soft fade-in/fade-out to avoid clicks.
 */
object WhiteNoisePlayer {
    private const val SAMPLE_RATE = 22050
    private const val CHUNK_SIZE = 1024

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    private var soundType = WhiteNoiseSound.BROWN

    private var worker: Thread? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    @Synchronized
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isPlaying)
    }

    @Synchronized
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val playing = isPlaying
        synchronized(this) {
            listeners.toList()
        }.forEach { it(playing) }
    }

    fun setSound(sound: WhiteNoiseSound) {
        soundType = sound
    }

    @Synchronized
    fun start(context: Context, sound: WhiteNoiseSound = WhiteNoisePrefs.sound(context)) {
        soundType = sound
        if (isPlaying) return
        isPlaying = true
        notifyListeners()

        worker = Thread({ runAudioLoop() }, "white-noise-player").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        notifyListeners()
        worker?.join(1500)
        worker = null
    }

    private fun runAudioLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuf, CHUNK_SIZE * 4)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack init failed: $e")
            isPlaying = false
            notifyListeners()
            return
        }

        try {
            track.play()
            val chunk = ShortArray(CHUNK_SIZE)
            val rng = java.util.Random()

            // State for pink noise filter (Kellet approximation)
            var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
            // State for brown noise (leaky integrator)
            var lastBrown = 0f

            // 1-second gentle fade in
            val fadeSamples = (SAMPLE_RATE * 1.0f).toInt()
            var currentSampleCount = 0

            while (isPlaying) {
                val currentType = soundType
                for (i in 0 until CHUNK_SIZE) {
                    val white = rng.nextFloat() * 2f - 1f
                    val sample = when (currentType) {
                        WhiteNoiseSound.BROWN -> {
                            lastBrown = (lastBrown + (0.02f * white)) / 1.02f
                            (lastBrown * 3.5f).coerceIn(-1f, 1f)
                        }
                        WhiteNoiseSound.PINK -> {
                            b0 = 0.99886f * b0 + white * 0.0555179f
                            b1 = 0.99332f * b1 + white * 0.0750759f
                            b2 = 0.96900f * b2 + white * 0.1538520f
                            b3 = 0.86650f * b3 + white * 0.3104856f
                            b4 = 0.55000f * b4 + white * 0.5329522f
                            b5 = -0.7616f * b5 - white * 0.0168980f
                            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
                            b6 = white * 0.115926f
                            (p * 0.11f).coerceIn(-1f, 1f)
                        }
                        WhiteNoiseSound.WHITE -> {
                            (white * 0.35f).coerceIn(-1f, 1f)
                        }
                    }

                    val fadeFactor = if (currentSampleCount < fadeSamples) {
                        currentSampleCount.toFloat() / fadeSamples
                    } else {
                        1f
                    }
                    currentSampleCount++

                    chunk[i] = (sample * 32767f * fadeFactor).toInt().coerceIn(-32768, 32767).toShort()
                }

                var written = 0
                while (written < CHUNK_SIZE && isPlaying) {
                    val n = track.write(chunk, written, CHUNK_SIZE - written)
                    if (n < 0) { isPlaying = false; break }
                    written += n
                }
            }

            // Quick fade out on stop to avoid click (1024 samples ~ 46 ms)
            for (i in 0 until CHUNK_SIZE) {
                val decay = 1f - (i.toFloat() / CHUNK_SIZE)
                chunk[i] = (chunk[i] * decay).toInt().toShort()
            }
            track.write(chunk, 0, CHUNK_SIZE)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack loop error: $e")
        } finally {
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                // ignore
            }
            isPlaying = false
            notifyListeners()
        }
    }
}
