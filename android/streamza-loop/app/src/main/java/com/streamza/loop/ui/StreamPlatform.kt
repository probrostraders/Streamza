package com.streamza.loop.ui

/** Ingest presets for the platforms Streamza Loop's own marketing pages already target (see
 *  site/youtube-live-streaming.html, site/facebook-live-streaming.html, site/twitch-streaming.html,
 *  site/custom-rtmp.html) — picking one just fills in the well-known RTMP URL so the user only has to
 *  paste their stream key. Custom leaves the URL editable for anything else (Kick, a self-hosted
 *  server, etc.). */
enum class StreamPlatform(val label: String, val defaultUrl: String?) {
    YouTube("YouTube", "rtmp://a.rtmp.youtube.com/live2"),
    Facebook("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/"),
    Twitch("Twitch", "rtmp://live.twitch.tv/app"),
    Custom("Custom RTMP", null),
}

/** One destination row being edited in the New Stream form, before it's turned into a plain
 *  [com.streamza.loop.data.Destination] (url, key) for the /start request. */
data class DestinationDraft(
    val platform: StreamPlatform = StreamPlatform.YouTube,
    val customUrl: String = "",
    val key: String = "",
) {
    val resolvedUrl: String get() = platform.defaultUrl ?: customUrl
    val isValid: Boolean get() = resolvedUrl.isNotBlank() && key.isNotBlank()
}
