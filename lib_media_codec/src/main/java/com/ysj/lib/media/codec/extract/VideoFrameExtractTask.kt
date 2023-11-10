package com.ysj.lib.media.codec.extract

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Size
import androidx.annotation.GuardedBy
import com.ysj.lib.media.codec.executor.SequentialExecutor
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 视频帧提取任务。
 *
 * @author Ysj
 * Create time: 2023/11/8
 */
internal class VideoFrameExtractTask(
    private val extractor: VideoFrameExtractor,
    private val videoPath: String,
    private val rangeUs: LongRange?,
    private val resolution: Size?,
) : VideoFrameExtractor.Task {

    companion object {
        private const val TAG = "VideoFrameExtractTask"
    }

    private enum class State {
        IDLE,
        STARTING,
        STARTED,
        STOPPING,
    }

    private val executor = object : Executor {
        private val delegate = SequentialExecutor(extractor.executor)
        override fun execute(command: Runnable) {
            delegate.execute {
                try {
                    command.run()
                } catch (e: Exception) {
                    errorInternal(e)
                } catch (e: Throwable) {
                    errorInternal(e)
                    throw e
                }
            }
        }
    }

    // ================ execute by executor ================

    private var state = State.IDLE

    private var decoder: VideoDecoder? = null

    private var executeError: Throwable? = null

    // =====================================================

    @GuardedBy("this")
    private var callbackExecutor: Executor? = null

    @GuardedBy("this")
    private var callback: VideoFrameExtractor.Task.Callback? = null

    @GuardedBy("this")
    private var callbackRejected = false

    override fun start(callbackExecutor: Executor, callback: VideoFrameExtractor.Task.Callback) {
        synchronized(this) {
            this.callbackExecutor = callbackExecutor
            this.callback = callback
        }
        executor.execute {
            check(state == State.IDLE)
            val rotation: Int
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)
                rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toInt()
                    ?: 0
            } finally {
                retriever.runCatching { release() }
            }
            val decoder = VideoDecoder(extractor.executor, VideoDecodeCallback())
            this.decoder = decoder
            decoder.start(videoPath, rotation, rangeUs, resolution)
            this.state = State.STARTING
            Log.d(TAG, "start. rotation=$rotation , rangeUs=$rangeUs , resolution=$resolution")
        }
    }

    override fun stop() = executor.execute {
        when (state) {
            State.IDLE,
            State.STOPPING -> Unit
            State.STARTING,
            State.STARTED -> {
                executeError = CancellationException("stop")
                stopInternal()
            }
        }
    }

    // execute by executor
    private fun errorInternal(e: Throwable) {
        Log.d(TAG, "errorInternal.", e)
        executeError = e
        when (state) {
            State.IDLE,
            State.STOPPING -> Unit
            State.STARTING -> {
                finalizeInternal()
            }
            State.STARTED -> {
                stopInternal()
            }
        }
    }

    // execute by executor
    private fun finalizeInternal() {
        val throwable = this.executeError
        this.decoder = null
        this.executeError = null
        this.state = State.IDLE
        sendEvent(VideoFrameExtractor.Event.Finalized(
            error = throwable,
        ))
        synchronized(this) {
            callback = null
            callbackExecutor = null
            callbackRejected = false
        }
        Log.d(TAG, "finalizeInternal. $throwable")
    }

    // execute by executor
    private fun stopInternal() {
        when (state) {
            State.IDLE,
            State.STOPPING -> return
            State.STARTING,
            State.STARTED -> Unit
        }
        state = State.STOPPING
        decoder?.stop()
        Log.d(TAG, "stopInternal.")
    }

    // execute by executor
    private fun onFrameAvailable(image: Bitmap, ptsNs: Long) {
        sendEvent(VideoFrameExtractor.Event.FrameAvailable(
            ptsNs = ptsNs,
            bitmap = image,
        ))
    }

    private fun sendEvent(event: VideoFrameExtractor.Event) {
        val callbackExecutor: Executor
        val callback: VideoFrameExtractor.Task.Callback
        synchronized(this) {
            if (this.callbackRejected) {
                return
            }
            callbackExecutor = checkNotNull(this.callbackExecutor)
            callback = checkNotNull(this.callback)
        }
        try {
            callbackExecutor.execute {
                callback.onEvent(event)
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "send event rejected. ${e.message}")
            synchronized(this) {
                this.callbackExecutor = null
                this.callback = null
                this.callbackRejected = true
            }
        }
    }

    private inner class VideoDecodeCallback : VideoDecoder.Callback {

        override fun onStarted() = executor.execute {
            when (state) {
                State.IDLE -> Unit
                State.STARTING -> {
                    state = State.STARTED
                    sendEvent(VideoFrameExtractor.Event.Started)
                }
                State.STARTED -> throw IllegalStateException("was started.")
                State.STOPPING -> Unit
            }
        }

        override fun onFinalized(e: Throwable?) = executor.execute {
            if (e == null) {
                finalizeInternal()
            } else {
                errorInternal(e)
            }
        }

        override fun onFrameArrive(image: Bitmap, ptsNs: Long) = executor.execute {
            if (state == State.STARTED) {
                onFrameAvailable(image, ptsNs)
            }
        }

    }
}