package me.phie.tawc.compositor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import me.phie.tawc.Settings
import java.io.File
import java.io.FileDescriptor

/**
 * Capture half of the audio bridge (plans/audio.md): the phone microphone
 * into the `audio-in-0` FIFO in the shared dir, which PipeWire's
 * pipe-tunnel source (`tawc_input`) reads as `/usr/share/tawc/audio-in-0`.
 * Fixed contract: s16le, mono, 48 kHz.
 *
 * The microphone is open only while a Linux app records. The pipe-tunnel
 * source reads the FIFO only while its node runs, so while idle the pipe
 * is kept full of silence and a reader draining it is the signal to open
 * the mic; once the pipe has stayed full for [READER_GONE_MS] the mic
 * closes and whatever audio was left unread is dropped. Gated on
 * [Settings.linuxMicrophone] and RECORD_AUDIO ([allowed]).
 */
object AudioCapture {
    const val FIFO_NAME = "audio-in-0"

    private const val TAG = "tawc"
    private const val RATE = 48000
    /** 20 ms of audio. Under PIPE_BUF, so a non-blocking write is all or nothing. */
    private const val CHUNK_BYTES = 1920
    /** Kernel pipe buffer: ~170 ms of audio. */
    private const val PIPE_BYTES = 16384
    private const val F_SETPIPE_SZ = 1031
    private const val POLL_MS = 250
    private const val READER_GONE_MS = 1000L
    /** After the mic failed to open, readers get silence this long. */
    private const val RETRY_MS = 5000L

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    fun allowed(context: Context): Boolean =
        Settings.linuxMicrophone &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(context: Context, shareDir: File) {
        if (thread != null) return
        val app = context.applicationContext
        val fifo = File(shareDir, FIFO_NAME)
        running = true
        thread = Thread({ run(app, fifo) }, "tawc-audio-in").also { it.start() }
    }

    /** The loop notices within one poll timeout or one chunk. */
    fun stop() {
        running = false
        thread = null
    }

    private fun run(context: Context, fifo: File) {
        val fd = try {
            openFifo(fifo)
        } catch (e: ErrnoException) {
            Log.w(TAG, "audio: cannot open ${fifo.path}: ${e.message}")
            return
        }
        val silence = ByteArray(CHUNK_BYTES)
        val pfd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLOUT.toShort()
        }
        var retryAt = 0L
        try {
            while (running) {
                fillWithSilence(fd, silence)
                if (!awaitReader(pfd)) continue
                if (!allowed(context) || SystemClock.elapsedRealtime() < retryAt) {
                    // Keep feeding silence at the reader's pace.
                    Thread.sleep(POLL_MS.toLong())
                    continue
                }
                if (!record(context, fd)) retryAt = SystemClock.elapsedRealtime() + RETRY_MS
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, "audio: capture bridge stopped: ${e.message}")
        } finally {
            try { Os.close(fd) } catch (_: ErrnoException) {}
        }
    }

    /** True once a reader has made room in the full pipe. */
    private fun awaitReader(pfd: StructPollfd): Boolean {
        pfd.revents = 0
        return try {
            Os.poll(arrayOf(pfd), POLL_MS) > 0
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINTR) false else throw e
        }
    }

    /** Drops unread audio, then fills the pipe with silence. */
    private fun fillWithSilence(fd: FileDescriptor, silence: ByteArray) {
        val scratch = ByteArray(PIPE_BYTES)
        while (readNonBlocking(fd, scratch) > 0) Unit
        while (writeNonBlocking(fd, silence, silence.size)) Unit
    }

    /** Streams the mic until the reader leaves; false if the mic didn't open. */
    private fun record(context: Context, fd: FileDescriptor): Boolean {
        val rec = try {
            newRecord()
        } catch (e: Exception) {
            Log.w(TAG, "audio: microphone unavailable: $e")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "audio: microphone failed to initialise")
            rec.release()
            return false
        }
        Log.i(TAG, "audio: microphone on")
        val buf = ByteArray(CHUNK_BYTES)
        try {
            rec.startRecording()
            // When the pipe was first seen full; 0 while the reader keeps up.
            var fullSince = 0L
            while (running && allowed(context)) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    Log.w(TAG, "audio: microphone read failed: $n")
                    break
                }
                if (n == 0) continue
                val now = SystemClock.elapsedRealtime()
                if (writeNonBlocking(fd, buf, n)) {
                    fullSince = 0L
                } else if (fullSince == 0L) {
                    fullSince = now
                } else if (now - fullSince >= READER_GONE_MS) {
                    break
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio: microphone failed: $e")
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
            Log.i(TAG, "audio: microphone off")
        }
        return true
    }

    private fun readNonBlocking(fd: FileDescriptor, buf: ByteArray): Int = try {
        Os.read(fd, buf, 0, buf.size)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) 0 else throw e
    }

    /** False when the pipe is full. */
    private fun writeNonBlocking(fd: FileDescriptor, buf: ByteArray, len: Int): Boolean = try {
        Os.write(fd, buf, 0, len)
        true
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) false else throw e
    }

    /** O_RDWR: open never blocks waiting for PipeWire, and the pipe
     *  survives PipeWire restarts. Non-blocking so a full pipe is visible. */
    private fun openFifo(fifo: File): FileDescriptor {
        if (fifo.exists() && !isFifo(fifo)) fifo.delete()
        if (!fifo.exists()) Os.mkfifo(fifo.path, "600".toInt(8))
        val fd = Os.open(fifo.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
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

    @Suppress("MissingPermission") // checked by allowed() before every call
    private fun newRecord(): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, CHUNK_BYTES * 4))
            .build()
    }
}
