package com.ysj.lib.media.codec.executor

import androidx.annotation.GuardedBy
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 确保按照 FIFO 执行的 [Executor]。
 *
 * @author Ysj
 * Create time: 2023/6/9
 */
class SequentialExecutor constructor(private val executor: Executor) : Executor {

    private enum class WorkerState {
        IDLE,
        QUEUING,
        RUNNING,
    }

    private val worker = Worker()

    @GuardedBy("queue")
    private val queue = ArrayDeque<Runnable>()

    @GuardedBy("queue")
    private var workerState = WorkerState.IDLE

    override fun execute(task: Runnable?) {
        task ?: return

        val submittedTask: Runnable
        synchronized(queue) {
            if (workerState == WorkerState.QUEUING || workerState == WorkerState.RUNNING) {
                queue.add(task)
                return
            }
            submittedTask = Runnable { task.run() }
            queue.add(submittedTask)
            workerState = WorkerState.QUEUING
        }

        try {
            executor.execute(worker)
        } catch (t: Throwable) {
            if (t !is RuntimeException && t !is Error) {
                throw t
            }
            synchronized(queue) {
                val removed =
                    (workerState == WorkerState.IDLE || workerState == WorkerState.QUEUING)
                        && queue.removeLastOccurrence(submittedTask)
                if (removed || t !is RejectedExecutionException) {
                    throw t
                }
            }
        }

    }

    private inner class Worker : Runnable {

        override fun run() {
            try {
                workOnQueue()
            } catch (e: Error) {
                synchronized(queue) {
                    workerState = WorkerState.IDLE
                }
                throw e
            }
        }

        private fun workOnQueue() {
            var interruptedDuringTask = false
            try {
                var isRunning = false
                while (true) {
                    val task: Runnable
                    synchronized(queue) {
                        if (!isRunning) {
                            if (workerState == WorkerState.RUNNING) {
                                return
                            }
                            workerState = WorkerState.RUNNING
                            isRunning = true
                        }
                        val poll = queue.poll()
                        if (poll == null) {
                            workerState = WorkerState.IDLE
                            return
                        }
                        task = poll
                    }
                    interruptedDuringTask = interruptedDuringTask or Thread.interrupted()
                    task.run()
                }
            } finally {
                if (interruptedDuringTask) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}