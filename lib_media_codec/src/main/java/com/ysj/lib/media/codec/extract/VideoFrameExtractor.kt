package com.ysj.lib.media.codec.extract

import android.graphics.Bitmap
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.util.concurrent.Executor

/**
 * 视频帧提取器。
 *
 * @author Ysj
 * Create time: 2023/11/7
 */
class VideoFrameExtractor private constructor(builder: Builder) {

    companion object {
        private const val TAG = "VideoThumbnail"
    }

    internal val executor = builder.executor ?: Dispatchers.IO.asExecutor()

    /**
     * 创建提取任务。
     *
     * @param videoPath 处理目标视频文件。
     * @param resolution 设置缩略图的分辨率。如果不设置则和视频原始分辨率一致。
     * @param rangeUs 设置取缩略图的时间区间。如果不设置则取全部。
     */
    fun createTask(
        videoPath: String,
        resolution: Size? = null,
        rangeUs: LongRange? = null,
    ): Task = VideoFrameExtractTask(
        this,
        videoPath,
        rangeUs,
        resolution,
    )

    interface Task {

        fun start(callbackExecutor: Executor, callback: Callback)

        fun stop()

        fun interface Callback {
            fun onEvent(event: Event)
        }

    }

    sealed interface Event {

        data object Started : Event

        data class FrameAvailable(
            val ptsNs: Long,
            val bitmap: Bitmap,
        ) : Event

        data class Finalized(
            val error: Throwable?,
        ) : Event

    }

    class Builder {

        internal var executor: Executor? = null

        /**
         * 设置用于执行转换的 [Executor]。
         * - 如果不设置默认使用 [Dispatchers.IO]。
         */
        fun setExecutor(executor: Executor?) = apply {
            this.executor = executor
        }

        fun build(): VideoFrameExtractor {
            return VideoFrameExtractor(this)
        }

    }
}