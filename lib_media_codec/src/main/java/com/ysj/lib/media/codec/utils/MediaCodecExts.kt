package com.ysj.lib.media.codec.utils

import android.media.MediaCodec

/*
 * [MediaCodec] 相关扩展。
 *
 * @author Ysj
 * Create time: 2023/11/17
 */

/**
 * [MediaCodec.BUFFER_FLAG_CODEC_CONFIG]。
 */
inline val MediaCodec.BufferInfo.isCodecConfig get() = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

/**
 * [MediaCodec.BUFFER_FLAG_END_OF_STREAM]。
 */
inline var MediaCodec.BufferInfo.isEndOfStream
    get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    set(value) {
        flags = if (value) {
            flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
        } else {
            flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
        }
    }

/**
 * [MediaCodec.BUFFER_FLAG_KEY_FRAME]。
 */
inline var MediaCodec.BufferInfo.isKeyFrame
    get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    set(value) {
        flags = if (value) {
            flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            flags and MediaCodec.BUFFER_FLAG_KEY_FRAME.inv()
        }
    }

fun MediaCodec.BufferInfo.print() = "flags=$flags, offset=$offset, size=$size, presentationTimeUs=$presentationTimeUs"