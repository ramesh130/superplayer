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

package com.superplayer.demo

import android.content.Context
import com.superplayer.core.MediaRequest

/**
 * The public test streams the demo offers, and the only place a streaming protocol is named.
 *
 * Note what this type does *not* carry: no source factory, no protocol flag, no per-protocol
 * construction. A stream is a label, an identity and a URI, because that is genuinely all SuperPlayer
 * needs to play one — the demo would look the same if a third protocol were added to the list.
 *
 * A stream has no id of its own for the picker to key on. It used to: the picker's buttons were
 * views, and views need window-unique ids. In Compose the selection is a value, held in
 * [androidx.compose.runtime.saveable.rememberSaveable] and keyed on nothing, so the declared ids and
 * the collision hazard they documented are both gone.
 *
 * [contentId] is what the stream *is*, as opposed to where it is served from — the identifier
 * SuperPlayer keys a resume position on. Written in a namespace of the demo's own, and deliberately
 * not derived from [uri]: a second CDN host for the same title has to be the same content id, which
 * is the entire point of `MediaRequest` carrying one.
 *
 * [titleRes] and [subtitleRes] are what the notification, the lock screen and a car head unit show.
 * They are separate from [labelRes] because they answer a different question: the label names a
 * choice in a picker ("HLS"), and the title names the content to someone who is looking at a
 * notification and has never seen this app's picker.
 */
internal enum class DemoStream(
    val labelRes: Int,
    val titleRes: Int,
    val subtitleRes: Int,
    val contentId: String,
    val uri: String,
) {

    /**
     * Apple's public "Advanced stream (HEVC/H.264, fMP4)" HLS example — the reference stream the HLS
     * specification's own authors publish, multi-bitrate with alternate audio and subtitle
     * renditions.
     *
     * ref: https://developer.apple.com/streaming/examples/
     */
    HLS(
        labelRes = R.string.stream_hls,
        titleRes = R.string.stream_hls_title,
        subtitleRes = R.string.stream_hls_subtitle,
        contentId = "demo:bipbop-advanced",
        uri = "https://devstreaming-cdn.apple.com/videos/streaming/examples/" +
            "img_bipbop_adv_example_fmp4/master.m3u8",
    ),

    /**
     * A clear (unencrypted) H.264 DASH stream from the media set ExoPlayer's own demo application
     * has shipped against for years, so it is a stream Media3 is known to be exercised on rather
     * than one picked for this demo alone.
     *
     * ref: https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json
     */
    DASH(
        labelRes = R.string.stream_dash,
        titleRes = R.string.stream_dash_title,
        subtitleRes = R.string.stream_dash_subtitle,
        contentId = "demo:tears-of-steel",
        uri = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd",
    ),
}

/**
 * The request for this stream, with everything an external surface needs to describe it.
 *
 * One function for every screen and the service, because two descriptions of the same stream — one for the app,
 * one for the notification — is exactly the drift that makes a notification say something the screen disagrees
 * with. Only where playback starts differs between them, so that is the argument.
 */
internal fun DemoStream.request(
    context: Context,
    startPosition: MediaRequest.StartPosition = MediaRequest.StartPosition.Beginning,
): MediaRequest = MediaRequest.Builder(contentId)
    .addSource(uri)
    .setStartPosition(startPosition)
    .setTitle(context.getString(titleRes))
    .setSubtitle(context.getString(subtitleRes))
    .build()
