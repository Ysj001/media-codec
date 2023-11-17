package com.ysj.lib.media.codec.utils

import android.media.MediaMetadataRetriever
import android.os.Build

/*
 * 媒体元数据扩展。
 *
 * @author Ysj
 * Create time: 2023/11/17
 */

val MediaMetadataRetriever.videoWidth get() = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)

val MediaMetadataRetriever.videoHeight get() = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

val MediaMetadataRetriever.videoRotation get() = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

val MediaMetadataRetriever.bitrate get() = extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)

val MediaMetadataRetriever.durationMs get() = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

val MediaMetadataRetriever.frameRate
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
    } else {
        null
    }