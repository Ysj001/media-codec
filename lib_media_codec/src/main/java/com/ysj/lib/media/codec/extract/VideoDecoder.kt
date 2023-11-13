package com.ysj.lib.media.codec.extract

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import android.view.Surface
import com.ysj.lib.egl.EGLEnv
import com.ysj.lib.egl.GLHandlerThread
import com.ysj.lib.egl.checkEGLError
import com.ysj.lib.egl.program.GLOESProgram
import com.ysj.lib.egl.texture.GLOESTexture
import com.ysj.lib.egl.texture.GLTexture
import com.ysj.lib.media.codec.executor.SequentialExecutor
import com.ysj.lib.media.codec.utils.durationUs
import com.ysj.lib.media.codec.utils.height
import com.ysj.lib.media.codec.utils.mime
import com.ysj.lib.media.codec.utils.width
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor


/**
 * 视频解码器。
 *
 * @author Ysj
 * Create time: 2023/11/7
 */
class VideoDecoder constructor(
    executor: Executor,
    private val callback: Callback,
) {

    companion object {
        private const val TAG = "VideoDecoder"
    }

    private val executor = object : Executor {
        private val delegate = SequentialExecutor(executor)
        override fun execute(command: Runnable) {
            delegate.execute {
                try {
                    command.run()
                } catch (e: Exception) {
                    reset()
                    callback.onFinalized(e)
                } catch (e: Throwable) {
                    reset()
                    callback.onFinalized(e)
                    throw e
                }
            }
        }
    }

    private val lock = DecodeLock()

    // =================== execute by executor ===================

    private var extractor: MediaExtractor? = null
    private var glThread: GLHandlerThread? = null

    private var isSignalEnd = false

    // ===========================================================

    // =================== execute by glThread ===================

    private var glSurface: EGLSurface? = null
    private var glProgram: GLOESProgram? = null
    private var glTexture: GLTexture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var codec: MediaCodec? = null

    private var pixelBuf: ByteBuffer? = null

    // ===========================================================

    fun start(path: String, rotation: Int, rangeUs: LongRange?, size: Size?) = executor.execute {
        var format: MediaFormat? = null
        var trackIndex: Int? = null
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        for (index in 0 until extractor.trackCount) {
            val fm = extractor.getTrackFormat(index)
            val mime = fm.mime
            if (mime.startsWith("video")) {
                format = fm
                trackIndex = index
                break
            }
        }
        if (format == null || trackIndex == null) {
            extractor.runCatching { release() }
            callback.onFinalized(IllegalArgumentException("file not support. path=$path"))
            return@execute
        }

        this.extractor = extractor

        val relRangeUs = rangeUs ?: 0..format.durationUs
        val relSize = size ?: Size(format.width, format.height)

        extractor.selectTrack(trackIndex)
        extractor.seekTo(relRangeUs.first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val glThread = GLHandlerThread(
            "VideoConverterGLThread",
            onError = {
                executor.execute {
                    callback.onFinalized(it)
                }
            },
            onExited = onExited@{
                val codec = this.codec
                if (codec != null) {
                    codec.runCatching {
                        reset()
                        release()
                    }
                    this.codec = null
                }
                glProgram?.release()
                glProgram = null
                surface?.release()
                surface = null
                glTexture?.release()
                glTexture = null
                surfaceTexture?.release()
                surfaceTexture = null
                if (it != null && glSurface != null) {
                    EGL14.eglDestroySurface(it.display, glSurface)
                    glSurface = null
                }
            }
        )
        this.glThread = glThread
        glThread.start()
        glThread.exec {
            var pixelBuf = this.pixelBuf
            val bufSize = relSize.width * relSize.height * 4
            if (pixelBuf == null || pixelBuf.capacity() != bufSize) {
                pixelBuf = ByteBuffer.allocateDirect(bufSize).order(ByteOrder.LITTLE_ENDIAN)
            }
            val eglSurface = createPbufferSurface(it, relSize.width, relSize.height)
            EGL14.eglMakeCurrent(it.display, eglSurface, eglSurface, it.context)
            val texture = GLOESTexture()
            val surfaceTexture = SurfaceTexture(texture.id)
            surfaceTexture.setOnFrameAvailableListener(OnFrameListener(relSize.width, relSize.height))
            val surface = Surface(surfaceTexture)
            val program = GLOESProgram()
            Matrix.rotateM(program.mvpMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
            this.glSurface = eglSurface
            this.glProgram = program
            this.glTexture = texture
            this.surfaceTexture = surfaceTexture
            this.surface = surface
            this.pixelBuf = pixelBuf
            val codec = MediaCodec.createDecoderByType(format.mime)
            codec.setCallback(CodeCallback(relRangeUs))
            codec.configure(format, surface, null, 0)
            this.codec = codec
            codec.start()
        }

        callback.onStarted(relRangeUs, relSize)

        Log.d(TAG, "start.")
    }

    fun stop() = executor.execute {
        if (isSignalEnd || glThread == null) {
            return@execute
        }
        isSignalEnd = true
        Log.d(TAG, "stop.")
    }

    private fun reset() {
        val extractor = this.extractor
        if (extractor != null) {
            extractor.runCatching {
                release()
            }
            this.extractor = null
        }
        lock.setLock(false)
        glThread?.quit()
        glThread = null
        isSignalEnd = false
        Log.i(TAG, "reset.")
    }

    private fun createPbufferSurface(eglEnv: EGLEnv, width: Int, height: Int): EGLSurface {
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(
            eglEnv.display,
            eglEnv.config,
            surfaceAttribs, 0
        ) ?: throw RuntimeException("surface was null")
        checkEGLError("eglCreatePbufferSurface")
        return eglSurface
    }

    private inner class OnFrameListener(val width: Int, val height: Int) : SurfaceTexture.OnFrameAvailableListener {

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            try {
                surfaceTexture.updateTexImage()
                val ptsNs = surfaceTexture.timestamp
                val program = checkNotNull(glProgram)
                val texture = checkNotNull(glTexture)
                val pixelBuf = checkNotNull(pixelBuf)
                program.texture = texture
                program.run()
                pixelBuf.rewind()
                GLES20.glReadPixels(
                    0,
                    0,
                    width,
                    height,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    pixelBuf
                )
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(pixelBuf)
                executor.execute {
                    callback.onFrameArrive(bitmap, ptsNs)
                }
            } finally {
                lock.setLock(false)
            }
        }

    }

    private inner class CodeCallback(val rangeUs: LongRange) : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = executor.execute {
            if (isSignalEnd) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return@execute
            }
            val extractor = checkNotNull(extractor)
            if (extractor.sampleTime > rangeUs.last) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return@execute
            }
            val buffer = requireNotNull(codec.getInputBuffer(index))
            val size = extractor.readSampleData(buffer, 0)
            if (size > 0) {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
                return@execute
            }
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = executor.execute {
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0 && info.presentationTimeUs < rangeUs.first) {
                codec.releaseOutputBuffer(index, false)
                return@execute
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                lock.setLock(true)
                codec.releaseOutputBuffer(index, info.presentationTimeUs * 1000)
                lock.awaitFrame()
            } else {
                codec.releaseOutputBuffer(index, false)
                executor.execute {
                    reset()
                    callback.onFinalized(null)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            executor.execute {
                reset()
                callback.onFinalized(e)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(TAG, "output format. $format")
        }

    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private class DecodeLock : Object() {
        private var waiting = false

        @Synchronized
        fun setLock(waiting: Boolean) {
            this.waiting = waiting
            if (!waiting) {
                notifyAll()
            }
        }

        @Synchronized
        fun awaitFrame() {
            if (!waiting) return
            try {
                wait(2500)
                if (waiting) {
                    throw RuntimeException("wait frame timeout!")
                }
            } catch (e: InterruptedException) {
                Thread.interrupted()
                return
            }
        }
    }

    interface Callback {
        fun onStarted(rangeUs: LongRange, resolution: Size)
        fun onFinalized(e: Throwable?)
        fun onFrameArrive(image: Bitmap, ptsNs: Long)
    }

}