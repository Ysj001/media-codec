package com.ysj.lib.media.codec

import android.util.Log
import androidx.annotation.GuardedBy
import com.ysj.lib.media.codec.executor.SequentialExecutor
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 对 [Codec] 进行抽象封装。
 *
 * @author Ysj
 * Create time: 2023/11/20
 */
abstract class AbsCodec<I : Codec.Input>(ioExecutor: Executor) : Codec<I> {

    enum class State {
        IDLE,
        STARTED,
        PAUSED,
        STOPPING,
        RELEASING,
        RELEASED,
    }

    /**
     * 按照调用顺序执行的 [Executor]。
     */
    protected val executor = object : Executor {
        private val delegate = SequentialExecutor(ioExecutor)
        override fun execute(command: Runnable) {
            delegate.execute {
                try {
                    command.run()
                } catch (e: Exception) {
                    onErrorInternal(e)
                } catch (e: Throwable) {
                    onErrorInternal(e)
                    throw e
                }
            }
        }
    }

    // execute by executor
    protected var state = State.IDLE

    @GuardedBy("this")
    protected var callback: Codec.Callback? = null

    @GuardedBy("this")
    protected var callbackExecutor: Executor? = null

    @GuardedBy("this")
    private var callbackRejected = false

    @Synchronized
    override fun setCallback(executor: Executor?, callback: Codec.Callback?) {
        this.callback = callback
        this.callbackExecutor = executor
    }

    // execute by executor
    protected abstract fun onErrorInternal(error: Throwable)

    protected open fun sendEvent(event: Codec.Event): Boolean {
        val callback: Codec.Callback
        val callbackExecutor: Executor
        synchronized(this) {
            if (this.callbackRejected) {
                return false
            }
            callbackExecutor = checkNotNull(this.callbackExecutor)
            callback = checkNotNull(this.callback)
        }
        try {
            callbackExecutor.execute {
                callback.onEvent(event)
            }
        } catch (e: RejectedExecutionException) {
            Log.w(javaClass.simpleName, "send event rejected. ${e.message}")
            synchronized(this) {
                this.callback = null
                this.callbackExecutor = null
                this.callbackRejected = true
            }
            return false
        } catch (e: Exception) {
            Log.w(javaClass.simpleName, "send event exception. ${e.message}")
            return false
        }
        return true
    }

}