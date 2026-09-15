/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.core

/**
 * A video codec a feed's content is encoded in, as [PlayerPool.Builder.setFeedCodecs] takes it.
 *
 * An enum rather than a MIME type string, and the reason is the direction a pool reads a codec it
 * cannot find: a declared codec the device has no decoder for is left out of the bound rather than
 * collapsing it, so a misspelt MIME string would be silently ignored rather than rejected. A closed
 * set of names cannot be misspelt.
 *
 * The codecs short-form and on-demand feeds are delivered in. A codec added later is a new entry,
 * which a consumer's exhaustive `when` over this enum would have to name.
 */
public enum class VideoCodec(
    /**
     * The platform's MIME type for it, lowercased as `DeviceCapacity.kt` compares them. Spelled out
     * rather than read from `MediaFormat`, whose AV1 constant is API 29's and above this module's
     * minimum; each is the value of the constant its entry cites.
     */
    internal val mimeType: String,
) {
    /**
     * H.264 / AVC, `video/avc`.
     *
     * ref: https://developer.android.com/reference/android/media/MediaFormat#MIMETYPE_VIDEO_AVC
     */
    H264("video/avc"),

    /**
     * H.265 / HEVC, `video/hevc`.
     *
     * ref: https://developer.android.com/reference/android/media/MediaFormat#MIMETYPE_VIDEO_HEVC
     */
    HEVC("video/hevc"),

    /**
     * VP9, `video/x-vnd.on2.vp9`.
     *
     * ref: https://developer.android.com/reference/android/media/MediaFormat#MIMETYPE_VIDEO_VP9
     */
    VP9("video/x-vnd.on2.vp9"),

    /**
     * AV1, `video/av01`.
     *
     * ref: https://developer.android.com/reference/android/media/MediaFormat#MIMETYPE_VIDEO_AV1
     */
    AV1("video/av01"),
}
