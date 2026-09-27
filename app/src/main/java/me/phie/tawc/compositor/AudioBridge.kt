package me.phie.tawc.compositor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.File
import java.io.FileDescriptor

/**
 * Playback half of the audio bridge (plans/audio.md). The rootfs audio
 * server (PipeWire's pipe-tunnel sink, or a PulseAudio pipe sink) writes
 * raw PCM into the `audio-out-0` FIFO in the shared dir, seen in the rootfs
 * as `/usr/share/tawc/audio-out-0`; this plays it through an AudioTrack.
 * Fixed contract: s16le, stereo, 48 kHz. The track pauses when the writer
 * goes quiet, so an idle session doesn't keep the audio path busy.
 */
object AudioBridge {
    const val FIFO_NAME = "audio-out-0"

    private const val TAG = "tawc"
    private const val RATE = 48000
    private const val FRAME_BYTES = 4
    /** Kernel pipe buffer: ~85 ms of audio, bounding the added latency. */
    private const val PIPE_BYTES = 16384
    private const val F_SETPIPE_SZ = 1031
    private const val IDLE_PAUSE_MS = 250

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    fun start(shareDir: File) {
        if (thread != null) return
        val fifo = File(shareDir, FIFO_NAME)
        running = true
        thread = Thread({ run(fifo) }, "tawc-audio-out").also { it.start() }
    }

    /** The loop notices within one poll timeout. */
    fun stop() {
        running = false
        thread = null
    }

    private fun run(fifo: File) {
        val fd = try {
            openFifo(fifo)
        } catch (e: ErrnoException) {
            Log.w(TAG, "audio: cannot open ${fifo.path}: ${e.message}")
            return
        }
        val track = newTrack()
        val buf = ByteArray(PIPE_BYTES)
        // Bytes of a partial frame carried over to the next read.
        var pending = 0
        val pfd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        try {
            while (running) {
                pfd.revents = 0
                val ready = try {
                    Os.poll(arrayOf(pfd), IDLE_PAUSE_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (ready == 0) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    continue
                }
                val n = Os.read(fd, buf, pending, buf.size - pending)
                if (n <= 0) continue
                val total = pending + n
                val whole = total - total % FRAME_BYTES
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                if (whole > 0) track.write(buf, 0, whole, AudioTrack.WRITE_BLOCKING)
                pending = total - whole
                if (pending > 0) System.arraycopy(buf, whole, buf, 0, pending)
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, "audio: bridge stopped: ${e.message}")
        } finally {
            track.release()
            try { Os.close(fd) } catch (_: ErrnoException) {}
        }
    }

    /** O_RDWR keeps our end open across writer restarts: open never blocks
     *  waiting for the audio server, and reads never see EOF when it exits. */
    private fun openFifo(fifo: File): FileDescriptor {
        if (fifo.exists() && !isFifo(fifo)) fifo.delete()
        if (!fifo.exists()) Os.mkfifo(fifo.path, "600".toInt(8))
        val fd = Os.open(fifo.path, OsConstants.O_RDWR, 0)
        try {
            Os.fcntlInt(fd, F_SETPIPE_SZ, PIPE_BYTES)
        } catch (_: ErrnoException) {
            // Keep the default pipe size; only latency suffers.
        }
        return fd
    }

    private fun isFifo(f: File): Boolean = try {
        OsConstants.S_ISFIFO(Os.lstat(f.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, PIPE_BYTES))
            .build()
    }
}
