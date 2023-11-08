package com.ysj.lib.media.codec.utils

import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/*
 * [MediaFormat] 相关操作的扩展。
 *
 * @author Ysj
 * Create time: 2023/7/9
 */

fun MediaFormat.value(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

fun MediaFormat.value(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default

fun MediaFormat.value(key: String, default: Float): Float =
    if (containsKey(key)) getFloat(key) else default

fun MediaFormat.value(key: String, default: String): String =
    if (containsKey(key)) getString(key) ?: default else default

fun MediaFormat.value(key: String, default: ByteBuffer): ByteBuffer =
    if (containsKey(key)) getByteBuffer(key) ?: default else default

var MediaFormat.mime: String
    set(value) = setString(MediaFormat.KEY_MIME, value)
    get() = requireNotNull(getString(MediaFormat.KEY_MIME))

var MediaFormat.width: Int
    set(value) = setInteger(MediaFormat.KEY_WIDTH, value)
    get() = getInteger(MediaFormat.KEY_WIDTH)

var MediaFormat.height: Int
    set(value) = setInteger(MediaFormat.KEY_HEIGHT, value)
    get() = getInteger(MediaFormat.KEY_HEIGHT)

var MediaFormat.durationUs: Long
    set(value) = setLong(MediaFormat.KEY_DURATION, value)
    get() = getLong(MediaFormat.KEY_DURATION)

var MediaFormat.rotation
    set(value) = setInteger("rotation-degrees", value)
    get() = value("rotation-degrees", 0)

var MediaFormat.bitrate
    set(value) = setInteger(MediaFormat.KEY_BIT_RATE, value)
    get() = value(MediaFormat.KEY_BIT_RATE, -1)

var MediaFormat.frameRate
    set(value) = setInteger(MediaFormat.KEY_FRAME_RATE, value)
    get() = value(MediaFormat.KEY_FRAME_RATE, -1)

var MediaFormat.iFrameInterval
    set(value) = setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, value)
    get() = value(MediaFormat.KEY_I_FRAME_INTERVAL, -1)

/** Constants are declared in [MediaCodecInfo.CodecCapabilities] */
var MediaFormat.colorFormat
    set(value) = setInteger(MediaFormat.KEY_COLOR_FORMAT, value)
    get() = value(MediaFormat.KEY_COLOR_FORMAT, -1)

val MediaFormat.maxInputSize get() = getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)