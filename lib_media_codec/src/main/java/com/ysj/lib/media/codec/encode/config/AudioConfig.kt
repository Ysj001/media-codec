package com.ysj.lib.media.codec.encode.config

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Rational
import com.ysj.lib.media.codec.Codec
import com.ysj.lib.media.codec.utils.bitrate

/**
 * 音频编码配置。
 *
 * @author Ysj
 * Create time: 2022/11/2
 */
class AudioConfig private constructor(builder: Builder) : Codec.Config {

    companion object {

        // 基于 (48000 AAC(LC) 高质量) 的基础配置。
        private const val AUDIO_BITRATE_BASE = 156_000
        private const val AUDIO_CHANNEL_COUNT_BASE = 2
        private const val AUDIO_SAMPLE_RATE_BASE = 48000

        fun builder(): Builder = Builder()
            .setMimeType(MediaFormat.MIMETYPE_AUDIO_AAC)
            .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC)

        fun default(channelCount: Int, sampleRate: Int): AudioConfig {
            val resolvedBitrate = scaleAndClampBitrate(sampleRate, channelCount)
            return builder()
                .setBitrate(resolvedBitrate)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .build()
        }

        fun scaleAndClampBitrate(channelCount: Int, sampleRate: Int): Int {
            // Scale bitrate based on source number of channels relative to base channel count.
            val channelCountRatio = Rational(channelCount, AUDIO_CHANNEL_COUNT_BASE)
            // Scale bitrate based on source sample rate relative to profile sample rate.
            val sampleRateRatio = Rational(sampleRate, AUDIO_SAMPLE_RATE_BASE)

            val resolvedBitrate = AUDIO_BITRATE_BASE *
                channelCountRatio.toDouble() *
                sampleRateRatio.toDouble()

            return resolvedBitrate.toInt()
        }
    }

    override val mimeType: String = builder.mimeType!!

    val profile: Int = builder.profile
    val bitrate: Int = builder.bitrate!!
    val sampleRate: Int = builder.sampleRate!!
    val channelCount: Int = builder.channelCount!!
    val bufferSize: Int? = builder.bufferSize

    override fun toMediaFormat(): MediaFormat {
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
        format.bitrate = bitrate
        if (profile != -1) {
            if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            } else {
                format.setInteger(MediaFormat.KEY_PROFILE, profile)
            }
        }
        val bufferSize = this.bufferSize
        if (bufferSize != null) {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }
        return format
    }

    class Builder {
        internal var mimeType: String? = null
        internal var profile: Int = -1
        internal var bitrate: Int? = null
        internal var sampleRate: Int? = null
        internal var channelCount: Int? = null
        internal var bufferSize: Int? = null

        fun setMimeType(mimeType: String) = apply {
            this.mimeType = mimeType
        }

        fun setProfile(profile: Int) = apply {
            this.profile = profile
        }

        fun setBitrate(bitrate: Int) = apply {
            this.bitrate = bitrate
        }

        fun setSampleRate(sampleRate: Int) = apply {
            this.sampleRate = sampleRate
        }

        fun setChannelCount(channelCount: Int) = apply {
            this.channelCount = channelCount
        }

        fun setBufferSize(bufferSize: Int) = apply {
            this.bufferSize = bufferSize
        }

        fun build(): AudioConfig {
            val sb = StringBuilder()
            if (mimeType == null) {
                sb.append(" mimeType")
            }
            if (bitrate == null) {
                sb.append(" bitrate")
            }
            if (sampleRate == null) {
                sb.append(" sampleRate")
            }
            if (channelCount == null) {
                sb.append(" channelCount")
            }
            if (sb.isNotEmpty()) {
                throw IllegalArgumentException("Missing required properties:$sb")
            }
            if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                require(profile != -1) {
                    "Encoder mime set to AAC, but no AAC profile was provided."
                }
            }
            return AudioConfig(this)
        }
    }
}