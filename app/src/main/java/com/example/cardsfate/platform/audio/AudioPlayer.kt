package com.example.cardsfate.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Looper

/** Main-thread owner. Uses system game volume; bundled clips already contain their reverb. */
class AudioPlayer(context: Context) {
    private val assets = context.applicationContext.assets
    private var speech: MediaPlayer? = null
    private var effect: MediaPlayer? = null
    private var generation = 0L
    private var released = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun playNarration(id: String, onComplete: () -> Unit = {}, onError: (Throwable) -> Unit = {}) =
        playSummary(listOf(id), onComplete, onError)

    fun playSummary(sequenceIds: List<String>, onComplete: () -> Unit = {}, onError: (Throwable) -> Unit = {}) {
        checkMain()
        stopSpeech()
        if (released) { onError(IllegalStateException("Audio player released")); return }
        val token = generation
        val ids = sequenceIds.toList()
        fun playIndex(index: Int) {
            if (generation != token || released) return
            if (index == ids.size) { onComplete(); return }
            try {
                val path = narrationPath(ids[index])
                val player = createPlayer(path)
                speech = player
                player.setOnPreparedListener { current ->
                    if (generation == token && speech === current && !released) current.start()
                }
                player.setOnCompletionListener { current ->
                    if (generation == token && speech === current && !released) {
                        speech = null
                        dispose(current)
                        playIndex(index + 1)
                    }
                }
                player.setOnErrorListener { current, what, extra ->
                    if (generation == token && speech === current && !released) {
                        stopSpeech()
                        onError(IllegalStateException("Narration playback failed ($what/$extra): $path"))
                    }
                    true
                }
                player.prepareAsync()
            } catch (failure: Exception) {
                if (generation == token && !released) { stopSpeech(); onError(failure) }
            }
        }
        playIndex(0)
    }

    /** Most recent effect replaces the previous effect. Returns load/start failures explicitly. */
    fun playEffect(effectId: String): Result<Unit> {
        checkMain()
        return runCatching {
            check(!released) { "Audio player released" }
            require(effectId.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid effect ID" }
            effect?.let(::dispose)
            effect = null
            val player = createPlayer("audio/sfx/$effectId.wav")
            effect = player
            player.setOnPreparedListener { if (effect === it && !released) it.start() }
            player.setOnCompletionListener { if (effect === it) { effect = null; dispose(it) } }
            player.setOnErrorListener { current, _, _ ->
                if (effect === current) { effect = null; dispose(current) }
                true
            }
            try { player.prepareAsync() } catch (failure: Exception) {
                effect = null
                dispose(player)
                throw failure
            }
        }
    }

    fun stopSpeech() {
        checkMain()
        generation++
        val previous = speech
        speech = null
        previous?.let(::dispose)
    }

    fun release() {
        checkMain()
        if (released) return
        released = true
        stopSpeech()
        val previous = effect
        effect = null
        previous?.let(::dispose)
    }

    private fun createPlayer(path: String): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            assets.openFd(path).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            return player
        } catch (failure: Exception) { player.release(); throw failure }
    }

    private fun narrationPath(id: String): String {
        require(id.matches(Regex("[a-z0-9/-]+")) && ".." !in id && !id.startsWith('/')) { "Invalid narration ID" }
        return "audio/narration/$id.ogg"
    }

    private fun dispose(player: MediaPlayer) {
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        player.release()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "AudioPlayer requires main thread" }
}
