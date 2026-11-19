package sleep.record

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.LinearLayout

internal class PlayerUi(private val activity: MainActivity) {
    private var file: String? = null
    private var media: MediaPlayer? = null
    private var prepared = false
    private var error = ""

    fun list() = with(activity) {
        title("放松睡觉音频", back = true, subtitle = "离线播放")
        val content = scroller(body)
        val files = assets.list("audio").orEmpty().filter { it.endsWith(".mp3", true) || it.endsWith(".wav", true) }.sorted()
        val card = content.card()
        if (files.isEmpty()) card.label("暂无音频文件", tint = palette.muted)
        files.forEach { name -> entry(card, name.substringBeforeLast('.'), "播放") { open(Screen("player", name)) } }
        content.label("音频由 Qwen3-TTS 生成", 12, palette.muted, gap = 16)
    }
    fun show(name: String) = with(activity) {
        if (file != name) {
            release()
            file = name
            val instance = MediaPlayer()
            media = instance
            runCatching {
                instance.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                assets.openFd("audio/$name").use { instance.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                instance.setOnPreparedListener { if (media === it) { prepared = true; if (isShowing("player")) render() } }
                instance.setOnErrorListener { _, _, _ -> error = "无法播放此音频"; prepared = false; if (isShowing("player")) render(); true }
                instance.prepareAsync()
            }.onFailure { error = "无法读取此音频" }
        }
        title("播放器", back = true)
        val card = scroller(body).card()
        card.addView(MoonArt(this), LinearLayout.LayoutParams(-1, dp(188)))
        card.label(name.substringBeforeLast('.'), 22, bold = true)
        val instance = media ?: return@with
        val length = if (prepared) instance.duration else 0
        val progressView = progress(card, length, 0)
        val time = card.label(if (error.isNotEmpty()) error else "00:00 / ${mmss(length / 1000L)}", 14, palette.muted, gap = 6)
        val control = card.action(if (instance.playing()) "暂停" else "播放", true, prepared) {
            if (instance.playing()) instance.pause() else {
                if (instance.currentPosition >= instance.duration - 100) instance.seekTo(0)
                instance.start()
            }
            render()
        }
        if (prepared) {
            val tick = object : Runnable {
                override fun run() {
                    if (!isShowing("player") || media !== instance || !prepared) return
                    val position = instance.currentPosition
                    progressView.progress = position
                    val timeText = "${mmss(position / 1000L)} / ${mmss(length / 1000L)}"
                    if (time.text != timeText) time.text = timeText
                    val controlText = if (instance.playing()) "暂停" else "播放"
                    if (control.text != controlText) control.text = controlText
                    handler.postDelayed(this, 200)
                }
            }
            renderTick = tick
            tick.run()
        }
    }
    private fun MediaPlayer.playing() = prepared && isPlaying
    fun release() {
        media?.release(); media = null; file = null; prepared = false; error = ""
    }
}

