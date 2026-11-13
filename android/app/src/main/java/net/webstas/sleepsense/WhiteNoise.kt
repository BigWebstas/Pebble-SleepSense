package net.webstas.sleepsense

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "WhiteNoisePlayer"
private const val FADE_MS = 12_000L
private const val TIMER_FADE_MS = 30_000L // the sleep timer fades the sound out over this long before it stops
private const val FADE_IN_MS = 1_500L // when a song starts without an overlap to fade in from

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
    private const val KEY_FILES = "files"
    private const val KEY_USE_FILES = "use_files"
    private const val KEY_TIMER_MIN = "timer_minutes"

    fun isPlayWhileTracking(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PLAY_WHILE_TRACKING, false)

    fun setPlayWhileTracking(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PLAY_WHILE_TRACKING, enabled)
        }
    }

    /** Minutes of playing after which the sound fades out and stops by itself; 0 = no timer. */
    fun timerMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TIMER_MIN, 0)

    fun setTimerMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_TIMER_MIN, minutes) }
    }

    fun sound(context: Context): WhiteNoiseSound {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOUND, WhiteNoiseSound.BROWN.id)
        return WhiteNoiseSound.entries.firstOrNull { it.id == id } ?: WhiteNoiseSound.BROWN
    }

    fun setSound(context: Context, sound: WhiteNoiseSound) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SOUND, sound.id)
            putBoolean(KEY_USE_FILES, false)
        }
    }

    /** The audio files chosen as the sleep sound, played in this order and repeated. */
    fun files(context: Context): List<Uri> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FILES, null) ?: return emptyList()
        val array = JSONArray(saved)
        return (0 until array.length()).map { Uri.parse(array.getString(it)) }
    }

    /** True when the chosen files, not a synthesized noise, are the sleep sound. */
    fun useFiles(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_FILES, false) && files(context).isNotEmpty()

    fun setFiles(context: Context, uris: List<Uri>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_FILES, JSONArray(uris.map { it.toString() }).toString())
            putBoolean(KEY_USE_FILES, uris.isNotEmpty())
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

    /** Master volume the players follow, 0..1: the sleep timer lowers it before stopping. */
    @Volatile
    internal var masterGain = 1f
        private set

    private val main = Handler(Looper.getMainLooper())
    private var timerStopAt = 0L
    private var timerFadeFrom = 0L
    private val timerStep = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now >= timerStopAt) {
                Log.i(TAG, "sleep timer ran out, stopping")
                stop()
                return
            }
            val left = (timerStopAt - now).toFloat() / (timerStopAt - timerFadeFrom).coerceAtLeast(1)
            masterGain = left * left // a squared curve sounds even, where a straight one seems to drop at the end
            main.postDelayed(this, 200)
        }
    }

    /** Starts the sleep timer from now (or cancels it when none is chosen). */
    fun rescheduleTimer(context: Context) {
        main.removeCallbacks(timerStep)
        masterGain = 1f
        val minutes = WhiteNoisePrefs.timerMinutes(context)
        if (minutes <= 0 || !isPlaying) return
        val now = SystemClock.elapsedRealtime()
        timerStopAt = now + minutes * 60_000L
        timerFadeFrom = max(now, timerStopAt - TIMER_FADE_MS)
        main.postDelayed(timerStep, timerFadeFrom - now)
    }

    // Chosen audio files play one after the other, each fading into the next
    private var loop: SongLoop? = null
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
        rescheduleTimer(context)

        val files = if (WhiteNoisePrefs.useFiles(context)) WhiteNoisePrefs.files(context) else emptyList()
        if (files.isNotEmpty()) {
            startFiles(context.applicationContext, files)
            return
        }

        worker = Thread({ runAudioLoop() }, "white-noise-player").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        main.removeCallbacks(timerStep)
        notifyListeners()
        worker?.join(1500)
        worker = null
        stopFiles()
    }

    /** Starts again with whatever is now chosen: the noise and the files are different players. */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    private fun startFiles(context: Context, files: List<Uri>) {
        loop = SongLoop(context, files) {
            isPlaying = false
            main.removeCallbacks(timerStep)
            notifyListeners()
            stopFiles()
        }.also { it.start() }
    }

    private fun stopFiles() {
        loop?.stop()
        loop = null
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
            var lastGain = 1f

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

                val gain = masterGain
                if (gain != lastGain) {
                    rampGain(chunk, CHUNK_SIZE, lastGain, gain, 1)
                    lastGain = gain
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

/** Scales the first [count] samples of [buf] by a gain moving straight from [from] to [to]; [stride] samples share one gain step. */
private fun rampGain(buf: ShortArray, count: Int, from: Float, to: Float, stride: Int) {
    val frames = count / stride
    for (f in 0 until frames) {
        val g = from + (to - from) * (f + 1) / frames
        for (c in 0 until stride) {
            val i = f * stride + c
            buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

private const val OUT_RATE = 44100
private const val OUT_CHUNK = 1024 // frames per round of the mixer

/**
 * One audio file decoded to stereo 16 bit at [OUT_RATE], whatever it was encoded as, so the loop can
 * mix any songs into a single [AudioTrack]. Throws if the file cannot be opened.
 */
private class SongDecoder(context: Context, uri: Uri) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var srcRate: Int
    private var channels: Int
    private var float = false
    private var step = 1.0

    /** Length in output frames, or -1 when the file does not say. */
    val durationFrames: Long

    private var inputDone = false
    private var outputDone = false
    private var chunk = ShortArray(0)
    private var chunkPos = 0
    private var chunkLen = 0 // samples, interleaved by [channels]

    // The two source frames the next output frame lies between, and how far (0..1) between them
    private var l0 = 0f; private var r0 = 0f; private var l1 = 0f; private var r1 = 0f
    private var pos = 0.0
    private var ended = false

    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            step = srcRate.toDouble() / OUT_RATE
            durationFrames = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) * OUT_RATE / 1_000_000 else -1
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
        ended = !fetch()
        if (!ended) {
            l0 = l1; r0 = r1
            ended = !fetch()
        }
    }

    /** Fills [out] with up to [frames] stereo frames; fewer (possibly none) means the file ended. */
    fun read(out: ShortArray, frames: Int): Int {
        if (ended) return 0
        var n = 0
        while (n < frames) {
            while (pos >= 1.0) {
                pos -= 1.0
                l0 = l1; r0 = r1
                if (!fetch()) { ended = true; return n }
            }
            out[2 * n] = (l0 + (l1 - l0) * pos).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * n + 1] = (r0 + (r1 - r0) * pos).toInt().coerceIn(-32768, 32767).toShort()
            pos += step
            n++
        }
        return n
    }

    fun close() {
        try { codec.stop() } catch (e: Exception) { }
        try { codec.release() } catch (e: Exception) { }
        try { extractor.release() } catch (e: Exception) { }
    }

    /** The next source frame into [l1], [r1]; false at the end. */
    private fun fetch(): Boolean {
        if (chunkPos >= chunkLen && !refill()) return false
        l1 = chunk[chunkPos].toFloat()
        r1 = if (channels > 1) chunk[chunkPos + 1].toFloat() else l1
        chunkPos += channels
        return true
    }

    /** Decodes until some samples are ready in [chunk]. */
    private fun refill(): Boolean {
        var idle = 0
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(0)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o >= 0 -> {
                    idle = 0
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset).limit(info.offset + info.size)
                    buf.order(java.nio.ByteOrder.nativeOrder())
                    val count = if (float) info.size / 4 else info.size / 2
                    if (chunk.size < count) chunk = ShortArray(count)
                    if (float) {
                        val f = buf.asFloatBuffer()
                        for (k in 0 until count) chunk[k] = (f.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    } else {
                        buf.asShortBuffer().get(chunk, 0, count)
                    }
                    chunkPos = 0
                    chunkLen = count - count % channels
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (chunkLen > 0) return true
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    step = srcRate.toDouble() / OUT_RATE
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                else -> if (++idle > 500) return false // the decoder has gone quiet
            }
        }
        return false
    }
}

/**
 * Plays [uris] in order and repeated, starting each one [FADE_MS] before the one before it ends and
 * fading between them, so no song ends abruptly (a single file fades into itself). The songs are
 * decoded here and mixed into one [AudioTrack] from a single thread: with two media players the second
 * one's start reconfigured the audio output right before each fade, which was heard as a pop and a pause.
 * A file that will not play is skipped; [onFailed] is called when none will.
 */
private class SongLoop(
    private val context: Context,
    private val uris: List<Uri>,
    private val onFailed: () -> Unit,
) {
    @Volatile
    private var stopped = false
    private val thread = Thread({ run() }, "song-loop").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
    }

    fun start() = thread.start()

    /** Ends the loop; the thread fades out the chunk it is on and then lets go of everything. */
    fun stop() {
        stopped = true
    }

    /** Opens file [index], or the first one after it that works, with the index it had; null when none does. */
    private fun open(index: Int): Pair<SongDecoder, Int>? {
        for (k in uris.indices) {
            val i = (index + k) % uris.size
            try {
                return SongDecoder(context, uris[i]) to i
            } catch (e: Exception) {
                Log.w(TAG, "could not play ${uris[i]}: $e")
            }
        }
        return null
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepsense:white-noise").apply { setReferenceCounted(false); acquire() }
        var track: AudioTrack? = null
        var cur: SongDecoder? = null
        var next: SongDecoder? = null
        var failed = false
        try {
            val first = open(0)
            if (first == null) { failed = true; return }
            cur = first.first
            var nextIndex = (first.second + 1) % uris.size

            val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf, OUT_RATE * 4 / 2)) // half a second
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            val a = ShortArray(OUT_CHUNK * 2)
            val b = ShortArray(OUT_CHUNK * 2)
            val fadeFramesWanted = FADE_MS * OUT_RATE / 1000
            var fadeFrames = fadeFramesWanted
            var played = 0L // frames of [cur] read so far
            var fadePos = -1L // frames into the cross fade, or -1 while none is going on
            var softPos = 0L // frames since a start from silence; the song is at full volume once it passes FADE_IN_MS
            val softFrames = FADE_IN_MS * OUT_RATE / 1000
            var lastGain = 1f

            while (true) {
                val stopping = stopped
                val n = cur!!.read(a, OUT_CHUNK)

                if (fadePos < 0) {
                    if (next == null && played > OUT_RATE) { // a moment into the song: have the next one ready
                        next = open(nextIndex)?.let { nextIndex = (it.second + 1) % uris.size; it.first }
                    }
                    val dur = cur.durationFrames
                    fadeFrames = if (dur > 0) min(fadeFramesWanted, dur / 2) else fadeFramesWanted
                    if (next != null && (n < OUT_CHUNK || (dur > 0 && played + n >= dur - fadeFrames))) {
                        Log.i(TAG, "cross fading into the next song")
                        fadePos = 0
                    } else if (n == 0) {
                        // the song ended and nothing was ready to fade into: carry straight on, from silence
                        Log.i(TAG, "song ended before the cross fade, starting the next one")
                        cur.close()
                        val o = open(nextIndex)
                        if (o == null) { failed = true; return }
                        cur = o.first
                        nextIndex = (o.second + 1) % uris.size
                        played = 0
                        softPos = 0
                        continue
                    }
                }

                var frames = n
                if (fadePos >= 0) {
                    val m = next!!.read(b, OUT_CHUNK)
                    frames = max(n, m)
                    for (f in 0 until frames) {
                        val t = ((fadePos + f).toFloat() / fadeFrames).coerceAtMost(1f)
                        // equal-power curves keep the loudness steady through the overlap
                        val gOut = cos(t * PI.toFloat() / 2)
                        val gIn = sin(t * PI.toFloat() / 2)
                        for (c in 0..1) {
                            val i = 2 * f + c
                            val mixed = (if (f < n) a[i] * gOut else 0f) + (if (f < m) b[i] * gIn else 0f)
                            a[i] = mixed.toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    fadePos += frames
                    if (fadePos >= fadeFrames) {
                        cur.close()
                        cur = next
                        next = null
                        played = fadePos
                        fadePos = -1
                    }
                } else {
                    played += n
                }

                if (softPos < softFrames) {
                    for (f in 0 until frames) {
                        val g = sin(min(1f, (softPos + f).toFloat() / softFrames) * PI.toFloat() / 2)
                        a[2 * f] = (a[2 * f] * g).toInt().toShort()
                        a[2 * f + 1] = (a[2 * f + 1] * g).toInt().toShort()
                    }
                    softPos += frames
                }

                // Following the sleep timer's fade out; a stop goes to silence within this chunk, so it does not click
                val gain = if (stopping) 0f else WhiteNoisePlayer.masterGain
                if (gain != lastGain) {
                    rampGain(a, frames * 2, lastGain, gain, 2)
                    lastGain = gain
                }

                var written = 0
                while (written < frames * 2) {
                    val w = track.write(a, written, frames * 2 - written)
                    if (w < 0) { failed = !stopping; return }
                    written += w
                }
                if (stopping) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "song loop error: $e")
            failed = !stopped
        } finally {
            cur?.close()
            next?.close()
            try { track?.stop() } catch (e: Exception) { }
            track?.release()
            if (wake.isHeld) wake.release()
            if (failed && !stopped) onFailed()
        }
    }
}
