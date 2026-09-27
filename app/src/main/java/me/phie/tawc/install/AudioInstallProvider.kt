package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.compositor.AudioBridge
import java.io.File

/**
 * Rootfs half of the audio bridge (plans/audio.md): a PipeWire drop-in
 * that loads a pipe-tunnel sink writing to [AudioBridge]'s FIFO, and
 * [SESSION_PATH], the script [me.phie.tawc.compositor.RootfsAudio] runs
 * with the compositor. Both are inert until the distro's `pipewire`
 * (+ `wireplumber`, `pipewire-pulse`) packages are installed.
 */
internal object AudioInstallProvider : TawcInstallProvider {
    override val name: String = "audio"

    const val SESSION_PATH = "/usr/lib/tawc/audio-session"
    /** A package-style drop-in: the distro's pipewire.conf reads it. */
    const val SINK_CONF_PATH = "/usr/share/pipewire/pipewire.conf.d/50-tawc-output.conf"

    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        val dir = File(context.filesDir, "audio").also { it.mkdirs() }
        val session = File(dir, "audio-session")
        session.writeText(SESSION_SCRIPT)
        session.setExecutable(true, false)
        val conf = File(dir, "50-tawc-output.conf")
        conf.writeText(SINK_CONF)
        return listOf(
            TawcInstall(session.absolutePath, SESSION_PATH, TawcInstall.Type.COPY),
            TawcInstall(conf.absolutePath, SINK_CONF_PATH, TawcInstall.Type.COPY),
        )
    }

    private val SESSION_SCRIPT = """
        #!/bin/sh
        # TAWC audio stack: started with the compositor, SIGTERMed on stop.
        # No-op without PipeWire, or when a PipeWire already runs.
        command -v pipewire >/dev/null 2>&1 || exit 0
        command -v pgrep >/dev/null 2>&1 && pgrep -x pipewire >/dev/null && exit 0
        trap 'kill ${'$'}(jobs -p) 2>/dev/null; wait' TERM INT
        pipewire &
        i=0
        while [ ! -S "${'$'}{XDG_RUNTIME_DIR:-/tmp}/pipewire-0" ] && [ ${'$'}i -lt 50 ]; do
            sleep 0.1; i=${'$'}((i + 1))
        done
        command -v wireplumber >/dev/null 2>&1 && wireplumber &
        command -v pipewire-pulse >/dev/null 2>&1 && pipewire-pulse &
        wait
    """.trimIndent() + "\n"

    private val SINK_CONF = """
        # TAWC audio bridge sink: s16le/stereo/48 kHz PCM into the FIFO
        # the TAWC app plays through an AudioTrack.
        context.modules = [
          { name = libpipewire-module-pipe-tunnel
            args = {
              tunnel.mode = sink
              tunnel.may-pause = true
              pipe.filename = "/usr/share/tawc/${AudioBridge.FIFO_NAME}"
              audio.format = "S16LE"
              audio.rate = 48000
              audio.channels = 2
              audio.position = [ FL FR ]
              node.name = "tawc_output"
              node.description = "TAWC Android output"
              stream.props = { media.class = "Audio/Sink" }
            }
          }
        ]
    """.trimIndent() + "\n"
}
