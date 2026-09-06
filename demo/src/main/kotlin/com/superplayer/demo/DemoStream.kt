package com.superplayer.demo

/**
 * The public test streams the demo offers, and the only place a streaming protocol is named.
 *
 * Note what this type does *not* carry: no source factory, no protocol flag, no per-protocol
 * construction. A stream is a label and a URI, because that is genuinely all SuperPlayer needs to
 * play one — the demo would look the same if a third protocol were added to the list.
 *
 * [viewId] is the id of the picker button that selects this stream. It lives here rather than being
 * derived from the ordinal so that the ids are the app's own declared resources: see `res/values/ids.xml`.
 */
internal enum class DemoStream(val viewId: Int, val labelRes: Int, val uri: String) {

    /**
     * Apple's public "Advanced stream (HEVC/H.264, fMP4)" HLS example — the reference stream the HLS
     * specification's own authors publish, multi-bitrate with alternate audio and subtitle
     * renditions.
     *
     * ref: https://developer.apple.com/streaming/examples/
     */
    HLS(
        viewId = R.id.stream_hls,
        labelRes = R.string.stream_hls,
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
        viewId = R.id.stream_dash,
        labelRes = R.string.stream_dash,
        uri = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd",
    ),
}
