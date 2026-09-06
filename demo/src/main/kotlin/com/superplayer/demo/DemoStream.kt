package com.superplayer.demo

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
 */
internal enum class DemoStream(
    val labelRes: Int,
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
        contentId = "demo:tears-of-steel",
        uri = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd",
    ),
}
