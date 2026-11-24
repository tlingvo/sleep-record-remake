package sleep.record

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import org.json.JSONObject

/** Bundled sleep audio. All calls run on the main thread; the page polls [state] while the player is open. */
internal class Player(private val context: Context) {
    private var file: String? = null
    private var media: MediaPlayer? = null
    private var prepared = false
    private var error = ""

    fun tracks() = context.assets.list("audio").orEmpty().filter { it.endsWith(".mp3", true) || it.endsWith(".wav", true) }.sorted()
    fun open(name: String) {
        require(name in tracks()) { "找不到此音频" }
        if (file == name) return
        release()
        file = name
        val instance = MediaPlayer()
        media = instance
        runCatching {
            instance.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            context.assets.openFd("audio/$name").use { instance.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            instance.setOnPreparedListener { if (media === it) prepared = true }
            instance.setOnErrorListener { _, _, _ -> error = "无法播放此音频"; prepared = false; true }
            instance.prepareAsync()
        }.onFailure { error = "无法读取此音频" }
    }
    fun toggle() {
        val instance = media ?: return
        if (!prepared) return
        if (instance.isPlaying) instance.pause() else {
            if (instance.currentPosition >= instance.duration - 100) instance.seekTo(0)
            instance.start()
        }
    }
    fun seek(fraction: Double) {
        val instance = media ?: return
        if (prepared) instance.seekTo((instance.duration * fraction.coerceIn(0.0, 1.0)).toInt())
    }
    fun state(): JSONObject {
        val instance = media
        val ready = instance != null && prepared
        return json("name" to file?.substringBeforeLast('.'), "prepared" to ready, "playing" to (ready && instance!!.isPlaying),
            "position" to (if (ready) instance!!.currentPosition else 0), "duration" to (if (ready) instance!!.duration else 0), "error" to error)
    }
    fun release() {
        media?.release(); media = null; file = null; prepared = false; error = ""
    }
}
