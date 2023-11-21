package com.ysj.lib.media.codec.decode.config

import android.media.MediaFormat
import com.ysj.lib.media.codec.Codec
import com.ysj.lib.media.codec.utils.mime

/**
 * 默认实现的解码配置。
 *
 * @author Ysj
 * Create time: 2023/11/20
 */
class DefaultDecodeConfig(private val mediaFormat: MediaFormat) : Codec.Config {

    override val mimeType: String = mediaFormat.mime
    override fun toMediaFormat(): MediaFormat = mediaFormat

}