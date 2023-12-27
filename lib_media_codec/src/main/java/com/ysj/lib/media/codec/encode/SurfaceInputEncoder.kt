package com.ysj.lib.media.codec.encode

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.ysj.lib.media.codec.AbsCodec
import com.ysj.lib.media.codec.Codec
import com.ysj.lib.media.codec.utils.isCodecConfig
import com.ysj.lib.media.codec.utils.isEndOfStream
import com.ysj.lib.media.codec.utils.isKeyFrame
import com.ysj.lib.media.codec.utils.mime
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 封装使用 [Codec.SurfaceInput] 的编码器。
 *
 * @author Ysj
 * Create time: 2023/11/15
 */
class SurfaceInputEncoder(
    ioExecutor: Executor,
    override val config: Codec.Config,
    override val output: Codec.Output,
) : AbsCodec<Codec.SurfaceInput>(ioExecutor) {

    companion object {
        private const val TAG = "SurfaceInputEncoder"
    }

    private val surfaceInput = SurfaceInput()
    override val input: Codec.SurfaceInput = surfaceInput

    private val format: MediaFormat = config.toMediaFormat()
    private val codec: MediaCodec = MediaCodec.createEncoderByType(format.mime)
    private val isVideo = format.mime.startsWith("video", true)

    private var error: Throwable? = null
    private var isSignalEnd = false

    init {
        Log.d(TAG, "codec format: $format")
        resetInternal()
    }

    override fun start() = executor.execute {
        when (state) {
            State.IDLE -> {
                codec.start()
                state = State.STARTED
                sendEvent(Codec.Event.Started)
            }
            State.PAUSED -> TODO("Not yet implemented")
            State.STARTED,
            State.STOPPING,
            State.RELEASING,
            State.RELEASED -> throw IllegalStateException("encoder state=$state")
        }
    }

    override fun pause() = executor.execute {
        TODO("Not yet implemented")
    }

    override fun stop() = executor.execute {
        when (state) {
            State.IDLE -> Unit
            State.STARTED,
            State.PAUSED -> {
                signalEndOfInputStream()
                state = State.STOPPING
            }
            State.STOPPING -> Unit
            State.RELEASING -> Unit
            State.RELEASED -> throw IllegalStateException("encoder was released.")
        }
    }

    override fun release() = executor.execute {
        when (state) {
            State.IDLE -> {
                releaseInternal()
            }
            State.STARTED,
            State.PAUSED -> {
                signalEndOfInputStream()
                state = State.RELEASING
            }
            State.STOPPING -> {
                state = State.RELEASING
            }
            State.RELEASING,
            State.RELEASED -> Unit
        }
    }

    override fun requestKeyFrame() {
        if (!isVideo) {
            return
        }
        executor.execute {
            when (state) {
                State.IDLE -> Unit
                State.STARTED -> {
                    val bundle = Bundle()
                    bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    codec.setParameters(bundle)
                }
                State.PAUSED -> Unit
                State.STOPPING -> Unit
                State.RELEASING -> Unit
                State.RELEASED -> throw IllegalStateException("encoder was released.")
            }
        }
    }

    override fun onErrorInternal(error: Throwable) {
        Log.d(TAG, "onErrorInternal. ${error.message}")
        this.error = error
        when (state) {
            State.IDLE,
            State.STARTED,
            State.PAUSED,
            State.STOPPING,
            State.RELEASING -> {
                sendEvent(Codec.Event.Finalized(error))
                releaseInternal()
            }
            State.RELEASED -> Unit
        }
    }

    // execute by execute
    private fun signalEndOfInputStream() {
        if (state == State.IDLE || state == State.RELEASED || isSignalEnd) {
            return
        }
        isSignalEnd = true
        codec.signalEndOfInputStream()
        Log.d(TAG, "signalEndOfInputStream")
    }

    // execute by execute
    private fun resetInternal() {
        codec.reset()
        codec.setCallback(CodecCallback())
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surfaceInput.resetSurface()
        isSignalEnd = false
        error = null
        state = State.IDLE
        Log.d(TAG, "resetInternal.")
    }

    // execute by execute
    private fun releaseInternal() {
        surfaceInput.release()
        codec.reset()
        codec.release()
        state = State.RELEASED
        Log.d(TAG, "releaseInternal.")
    }

    private inner class CodecCallback : MediaCodec.Callback() {

        private var hasEndData = false
        private var lastPresentationTimeUs = -1L

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = executor.execute {
            if (hasEndData) {
                Log.d(TAG, "Drop buffer by already reach end of stream.")
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (info.isCodecConfig) {
                Log.d(TAG, "Drop buffer by codec config.")
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (output.isReleased()) {
                Log.d(TAG, "Drop buffer by released output.")
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (info.isEndOfStream) {
                if (info.size <= 0) {
                    Log.d(TAG, "Drop buffer by invalid buffer size.")
                    codec.releaseOutputBuffer(index, false)
                    return@execute
                }
                if (info.presentationTimeUs <= lastPresentationTimeUs) {
                    try {
                        Log.d(TAG, "Drop buffer by out of order buffer from MediaCodec.")
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: MediaCodec.CodecException) {
                        onErrorInternal(e)
                    }
                    return@execute
                }
                lastPresentationTimeUs = info.presentationTimeUs
                output.onCodecOutput(OutputBuffer(info, index, codec.getOutputBuffer(index)!!))
            } else {
                hasEndData = true
                when (state) {
                    State.IDLE -> throw IllegalStateException("encoder not start.")
                    State.STARTED,
                    State.PAUSED,
                    State.STOPPING -> {
                        val buffer = OutputBuffer(info, index, codec.getOutputBuffer(index)!!)
                        buffer.onBufferReleased = {
                            sendEvent(Codec.Event.Finalized(error))
                            resetInternal()
                        }
                        output.onCodecOutput(buffer)
                    }
                    State.RELEASING -> {
                        val buffer = OutputBuffer(info, index, codec.getOutputBuffer(index)!!)
                        buffer.onBufferReleased = {
                            sendEvent(Codec.Event.Finalized(error))
                            releaseInternal()
                        }
                        output.onCodecOutput(buffer)
                    }
                    State.RELEASED -> throw IllegalStateException("encoder was released.")
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = executor.execute {
            onErrorInternal(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = executor.execute {
            sendEvent(Codec.Event.OutputConfigUpdated { format })
        }

    }

    private inner class SurfaceInput : Codec.SurfaceInput {

        private var surface: Surface? = null

        override fun surface(): Surface {
            return checkNotNull(surface)
        }

        fun resetSurface() {
            surface?.release()
            surface = codec.createInputSurface()
        }

        fun release() {
            surface?.release()
            surface = null
        }
    }

    private inner class OutputBuffer(
        val bufferInfo: MediaCodec.BufferInfo,
        val bufferIndex: Int,
        val buffer: ByteBuffer,
    ) : Codec.Output.Buffer {

        var onBufferReleased: (() -> Unit)? = null

        private val closed = AtomicBoolean(false)

        override fun buffer(): ByteBuffer {
            check(!this.closed.get()) { "encoded data is closed." }
            buffer.position(bufferInfo.offset)
            buffer.limit(bufferInfo.offset + bufferInfo.size)
            return buffer
        }

        override fun bufferInfo(): MediaCodec.BufferInfo = bufferInfo
        override fun size() = bufferInfo.size
        override fun presentationTimeUs() = bufferInfo.presentationTimeUs

        override fun isKeyFrame(): Boolean {
            return if (isVideo) bufferInfo.isKeyFrame else true
        }

        override fun isEnd(): Boolean {
            return bufferInfo.isEndOfStream
        }

        override fun render() {
            if (closed.getAndSet(true)) {
                return
            }
            executor.execute {
                when (state) {
                    State.IDLE -> Unit
                    State.STARTED,
                    State.PAUSED,
                    State.STOPPING,
                    State.RELEASING -> {
                        codec.releaseOutputBuffer(
                            bufferIndex,
                            bufferInfo.presentationTimeUs * 1000,
                        )
                        onBufferReleased?.invoke()
                        onBufferReleased = null
                    }
                    State.RELEASED -> Unit
                }
            }
        }

        override fun close() {
            if (closed.getAndSet(true)) {
                return
            }
            executor.execute {
                when (state) {
                    State.IDLE -> Unit
                    State.STARTED,
                    State.PAUSED,
                    State.STOPPING,
                    State.RELEASING -> {
                        codec.releaseOutputBuffer(bufferIndex, false)
                        onBufferReleased?.invoke()
                        onBufferReleased = null
                    }
                    State.RELEASED -> Unit
                }
            }
        }
    }
}