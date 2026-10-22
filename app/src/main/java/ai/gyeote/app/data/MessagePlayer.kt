package ai.gyeote.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer

/** Audio stays in memory and is cleared on completion, error, interruption, or driving restriction. */
class MessagePlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
    private var player: MediaPlayer? = null
    private var data: ByteArray? = null

    fun play(bytes: ByteArray, complete: () -> Unit, failed: () -> Unit) {
        stop()
        require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024)
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            bytes.fill(0); failed(); return
        }
        data = bytes
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(object : MediaDataSource() {
                override fun getSize(): Long = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
                override fun close() { bytes.fill(0) }
            })
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stop(); complete() }
            setOnErrorListener { _, _, _ -> stop(); failed(); true }
            prepareAsync()
        }
    }
    fun stop() {
        player?.release(); player = null
        data?.fill(0); data = null
        audio.abandonAudioFocusRequest(focus)
    }
}
