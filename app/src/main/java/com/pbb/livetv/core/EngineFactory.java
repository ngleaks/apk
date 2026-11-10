package com.pbb.livetv.core;

import com.pbb.livetv.StreamFormat;
import com.pbb.livetv.media.HlsEngine;
import com.pbb.livetv.media.PlayerEngine;
import com.pbb.livetv.media.ProgressiveEngine;

/** Chooses the right {@link PlayerEngine} for a URL. HLS gets the segment engine, everything else
 *  goes through the platform player's progressive path. */
public final class EngineFactory implements PlayerEngine.Factory {

    @Override
    public PlayerEngine create(String url, PlayerEngine.Listener listener) {
        if (StreamFormat.isHls(url)) {
            return new HlsEngine(listener);
        }
        return new ProgressiveEngine(listener);
    }
}
