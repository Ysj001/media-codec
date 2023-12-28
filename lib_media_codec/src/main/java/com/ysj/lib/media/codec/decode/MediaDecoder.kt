package com.ysj.lib.media.codec.decode

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.ysj.lib.media.codec.AbsCodec
import com.ysj.lib.media.codec.Codec
import com.ysj.lib.media.codec.utils.mime
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/**
 * 通用的媒体解码器。
 *
 * @author Ysj
 * Create time: 2023/11/20
 */
class MediaDecoder(
    ioExecutor: Executor,
    override val config: Codec.Config,
    override val output: Codec.Output,
) : AbsCodec<Codec.BufferInput>(ioExecutor) {

    companion object {
        private const val TAG = "MediaDecoder"
    }

    private val bufferInput = BufferInput()
    override val input: Codec.BufferInput = bufferInput

    private val format: MediaFormat = config.toMediaFormat()
    private val codec: MediaCodec = MediaCodec.createDecoderByType(format.mime)
    private val isVideo = format.mime.startsWith("video", true)

    private var error: Throwable? = null
    private var isSignalEnd = false

    override fun start() = executor.execute {
        when (state) {
            State.IDLE -> {
                codec.setCallback(CodecCallback())
                codec.configure(format, output.surface(), null, 0)
                codec.start()
                state = State.STARTED
                sendEvent(Codec.Event.Started)
                Log.d(TAG, "start.")
            }
            State.PAUSED -> TODO("Not yet implemented")
            State.STARTED,
            State.STOPPING,
            State.RELEASING,
            State.RELEASED -> throw IllegalStateException("codec state=$state")
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
            State.RELEASED -> throw IllegalStateException("codec was released.")
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
        // nothing
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
        val buffer = bufferInput.acquire()
        if (buffer != null) {
            buffer.setEndOfStream(true)
            buffer.submit()
        }
        bufferInput.release()
        Log.d(TAG, "signalEndOfInputStream")
    }

    // execute by execute
    private fun resetInternal() {
        bufferInput.release()
        codec.reset()
        isSignalEnd = false
        error = null
        state = State.IDLE
        Log.d(TAG, "resetInternal.")
    }

    // execute by execute
    private fun releaseInternal() {
        bufferInput.release()
        codec.reset()
        codec.release()
        state = State.RELEASED
        Log.d(TAG, "releaseInternal.")
    }

    private inner class CodecCallback : MediaCodec.Callback() {

        private var hasEndData = false
        private var lastPresentationTimeUs = 0L

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = executor.execute {
            if (isSignalEnd) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return@execute
            }
            bufferInput.offerBuffer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = executor.execute {
            if (hasEndData) {
                Log.d(TAG, "Drop buffer by already reach end of stream.")
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                Log.d(TAG, "Drop buffer by codec config.")
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (output.isReleased()) {
                Log.d(TAG, "Drop buffer by released output.")
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
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
                output.onCodecOutput(OutputData(info, index, codec.getOutputBuffer(index)!!))
            } else {
                hasEndData = true
                when (state) {
                    State.IDLE -> throw IllegalStateException("encoder not start.")
                    State.STARTED,
                    State.PAUSED,
                    State.STOPPING -> {
                        val data = OutputData(info, index, codec.getOutputBuffer(index)!!)
                        data.onBufferReleased = {
                            sendEvent(Codec.Event.Finalized(error))
                            resetInternal()
                        }
                        output.onCodecOutput(data)
                    }
                    State.RELEASING -> {
                        val data = OutputData(info, index, codec.getOutputBuffer(index)!!)
                        data.onBufferReleased = {
                            sendEvent(Codec.Event.Finalized(error))
                            releaseInternal()
                        }
                        output.onCodecOutput(data)
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

    private inner class BufferInput : Codec.BufferInput {

        private val freeBufferIndexQueue = ArrayDeque<Int>()

        private val lock = ReentrantLock()

        override fun acquire(): Codec.BufferInput.Data? {
            lock.lock()
            val bufferIndex = freeBufferIndexQueue.poll()
            if (bufferIndex == null) {
                lock.unlock()
                return null
            }
            return object : InputData(bufferIndex) {
                override fun submit(): Boolean {
                    val submit = super.submit()
                    lock.unlock()
                    return submit
                }

                override fun release(): Boolean {
                    val cancel = super.release()
                    lock.unlock()
                    return cancel
                }
            }
        }

        fun release() {
            lock.lock()
            freeBufferIndexQueue.clear()
            lock.unlock()
        }

        fun offerBuffer(bufferIndex: Int) {
            lock.lock()
            freeBufferIndexQueue.offer(bufferIndex)
            lock.unlock()
        }
    }

    private open inner class InputData(val bufferIndex: Int) : Codec.BufferInput.Data {

        private val terminated = AtomicBoolean(false)

        private val buffer = codec.getInputBuffer(bufferIndex)!!

        private var presentationTimeUs = 0L

        private var isEndOfStream = false

        override fun buffer(): ByteBuffer {
            checkTerminated()
            return buffer
        }

        override fun setPresentationTimeUs(presentationTimeUs: Long) {
            checkTerminated()
            this.presentationTimeUs = max(0, presentationTimeUs)
        }

        override fun setEndOfStream(isEnd: Boolean) {
            checkTerminated()
            this.isEndOfStream = isEnd
        }

        override fun submit(): Boolean {
            if (terminated.getAndSet(true)) {
                return false
            }
            executor.execute {
                when (state) {
                    State.IDLE -> Unit
                    State.STARTED,
                    State.PAUSED,
                    State.STOPPING,
                    State.RELEASING -> codec.queueInputBuffer(
                        bufferIndex,
                        buffer.position(),
                        buffer.limit(),
                        presentationTimeUs,
                        if (isEndOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )
                    State.RELEASED -> Unit
                }
            }
            return true
        }

        override fun release(): Boolean {
            if (terminated.getAndSet(true)) {
                return false
            }
            executor.execute {
                when (state) {
                    State.IDLE -> Unit
                    State.STARTED,
                    State.PAUSED,
                    State.STOPPING,
                    State.RELEASING -> codec.queueInputBuffer(
                        bufferIndex,
                        0, 0,
                        0,
                        0
                    )
                    State.RELEASED -> Unit
                }
            }
            return true
        }

        private fun checkTerminated() = check(!terminated.get()) {
            "The buffer is submitted or canceled."
        }
    }

    private inner class OutputData(
        val bufferInfo: MediaCodec.BufferInfo,
        val bufferIndex: Int,
        val buffer: ByteBuffer,
    ) : Codec.Output.Data {

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
            return if (isVideo) bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 else true
        }

        override fun isEnd(): Boolean {
            return bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
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