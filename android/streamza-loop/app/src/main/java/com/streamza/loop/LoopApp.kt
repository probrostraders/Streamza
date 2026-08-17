package com.streamza.loop

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

class LoopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registered explicitly rather than relying on coil-video's META-INF/services auto-discovery,
        // which turned out not to be reliable here — the picked-video thumbnail in Stream just showed
        // blank without it. This makes every AsyncImage in the app able to decode a frame straight from
        // a video Uri, not just the ones that remember to opt in.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()
        )
    }
}
