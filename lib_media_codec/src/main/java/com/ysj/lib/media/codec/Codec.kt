package com.ysj.lib.media.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.ysj.lib.media.codec.Codec.BufferInput.Data
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/**
 * 定义通用编解码接口。
 *
 * @author Ysj
 * Create time: 2023/11/17
 */
interface Codec<I : Codec.Input> {

    /**
     * [Codec] 的配置。
     */
    val config: Config

    /**
     * [Codec] 的输入。
     */
    val input: I

    /**
     * [Codec] 的输出。
     */
    val output: Output

    /**
     * 开始 [Codec]。
     */
    fun start()

    /**
     * 暂停 [Codec]。
     */
    fun pause()

    /**
     * 停止 [Codec]。
     */
    fun stop()

    /**
     * 释放 [Codec]。
     */
    fun release()

    /**
     * 设置 [Codec] 的处理回调。
     *
     * @param executor 设置回调的执行器。
     * @param callback 设置回调接口。
     */
    fun setCallback(executor: Executor?, callback: Callback?)

    /**
     * 请求关键帧。
     */
    fun requestKeyFrame()

    /**
     * 定义 [Codec] 的配置。
     */
    interface Config {

        val mimeType: String

        fun toMediaFormat(): MediaFormat

    }

    /**
     * 定义 [Codec] 的输出配置。
     */
    fun interface OutputConfig {

        fun toMediaFormat(): MediaFormat

    }

    /**
     * 定义 [Codec] 输入数据的接口。
     */
    interface Input

    /**
     * 定义以 [Surface] 作为 [Input]。
     */
    interface SurfaceInput : Input {

        /**
         * 调用该方法可以获得一个用于给 [Codec] 提供输入数据的 [Surface]。
         */
        fun surface(): Surface

    }

    /**
     * 定义以 [Data] 作为输入的 [Input]。
     */
    interface BufferInput : Input {

        /**
         * 调用该方法获取一个 [Codec] 的输入数据。
         */
        fun acquire(): Data?

        /**
         * 定义作为 [Codec] 输入数据的缓存。
         */
        interface Data {

            /**
             * 获取一个 [ByteBuffer]。
             */
            fun buffer(): ByteBuffer

            /**
             * 设置 [buffer] 中的数据的所对应的预览时间。
             */
            fun setPresentationTimeUs(presentationTimeUs: Long)

            /**
             * 标记 [buffer] 是否是最后一个。
             */
            fun setEndOfStream(isEnd: Boolean)

            /**
             * 将 [buffer] 提交给 [Codec]。
             *
             * @return 返回 true 表示 [buffer] 已经成功提交给 [Codec]，返回 false 表示已经提交或被释放。
             */
            fun submit(): Boolean

            /**
             * 释放 [buffer]。
             *
             * @return 返回 true 表示已经释放或已经提交，返回 false 表示已经释放过。
             */
            fun release(): Boolean

        }
    }

    /**
     * 定义 [Codec] 的 [Output]。
     */
    interface Output {

        /**
         * [Codec] 内部会调用该方法来获取一个用于输出数据的 [Surface]。
         */
        fun surface(): Surface?

        /**
         * 释放。
         */
        fun release()

        /**
         * @return 如果已经释放返回 true。
         */
        fun isReleased(): Boolean

        /**
         * [Codec] 内部会调用该方法返回一个处理好的 [Data]。
         */
        fun onCodecOutput(data: Data)

        /**
         * 定义 [Codec.Output] 输出的数据。
         */
        interface Data : Closeable {

            fun buffer(): ByteBuffer

            fun bufferInfo(): MediaCodec.BufferInfo

            fun size(): Int

            fun presentationTimeUs(): Long

            fun isKeyFrame(): Boolean

            fun isEnd(): Boolean

            fun render()
        }
    }

    /**
     * [Codec] 处理的回调。
     */
    fun interface Callback {
        fun onEvent(event: Event)
    }

    /**
     * [Codec] 相关事件。
     */
    sealed interface Event {

        /**
         * [Codec] 已经处于开始状态。
         */
        data object Started : Event

        /**
         * [Codec] 已经处于暂停状态。
         */
        data object Paused : Event

        /**
         * [Codec] 的输出配置已经更新。
         */
        class OutputConfigUpdated(
            val config: OutputConfig
        ) : Event

        /**
         * [Codec] 已经处于最终状态。
         */
        class Finalized(
            /**
             * 处理过程的异常。
             * - 如果为 null 表示没有异常正常结束。
             * - 如果为 [CancellationException] 表示用户主动停止。
             */
            val error: Throwable?,
        ) : Event

    }
}