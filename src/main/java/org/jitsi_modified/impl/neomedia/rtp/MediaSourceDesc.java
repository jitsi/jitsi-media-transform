/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.rtp;

import org.jitsi.nlj.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.*;

import java.util.*;

/**
 * Represents a collection of {@link RtpLayerDesc}s that encode the same
 * media source. This specific implementation provides webrtc simulcast stream
 * suspension detection.
 *
 * @author George Politis
 */
public class MediaSourceDesc
{
    /**
     * The {@link RtpLayerDesc}s that this {@link MediaSourceDesc}
     * possesses, ordered by their subjective quality from low to high.
     */
    private final RtpLayerDesc[] rtpLayers;

    /**
     * Allow the lookup of a layer by the layer id of a received packet.
     */
    private final Map<Long, RtpLayerDesc> layersById = new HashMap<>();

    /**
     * A string which identifies the owner of this source (e.g. the endpoint
     * which is the sender of the source).
     */
    private final String owner;

    /**
     * Ctor.
     *
     * @param rtpLayers The {@link RtpLayerDesc}s that this instance
     * possesses.
     */
    public MediaSourceDesc(
        RtpLayerDesc[] rtpLayers)
    {
        this(rtpLayers, null);
    }

    /**
     * Ctor.
     *
     * @param rtpLayers The {@link RtpLayerDesc}s that this instance
     * possesses.
     */
    public MediaSourceDesc(
        RtpLayerDesc[] rtpLayers,
        String owner)
    {
        this.rtpLayers = rtpLayers;
        this.owner = owner;
    }

    public void updateLayerCache()
    {
        for (RtpLayerDesc layer : this.rtpLayers)
        {
            layersById.put(layer.getEncodingId(), layer);
        }
    }

    /**
     * @return the identifier of the owner of this source.
     */
    public String getOwner()
    {
        return owner;
    }

    /**
     * Gets the last "stable" bitrate (in bps) of the encoding of the specified
     * index. The "stable" bitrate is measured on every new frame and with a
     * 5000ms window.
     *
     * @return the last "stable" bitrate (bps) of the encoding at the specified
     * index.
     */
    public long getBitrateBps(long nowMs, int idx)
    {
        if (ArrayUtils.isNullOrEmpty(rtpLayers))
        {
            return 0;
        }

        if (idx > -1)
        {
            for (int i = idx; i > -1; i--)
            {
                long bps = rtpLayers[i].getBitrateBps(nowMs);
                if (bps > 0)
                {
                    return bps;
                }
            }
        }

        return 0;
    }

    public boolean hasRtpLayers()
    {
        return !ArrayUtils.isNullOrEmpty(rtpLayers);
    }

    public int numRtpLayers()
    {
        if (ArrayUtils.isNullOrEmpty(rtpLayers))
        {
            return 0;
        }
        return rtpLayers.length;
    }

    public long getPrimarySSRC()
    {
        if (ArrayUtils.isNullOrEmpty(rtpLayers))
        {
            return -1;
        }
        return rtpLayers[0].getPrimarySSRC();
    }

    public RtpLayerDesc getRtpLayerByQualityIdx(int idx)
    {
        if (ArrayUtils.isNullOrEmpty(rtpLayers))
        {
            return null;
        }

        if (idx >= rtpLayers.length)
        {
            return null;
        }

        return rtpLayers[idx];
    }

    public RtpLayerDesc findRtpLayerDesc(VideoRtpPacket videoRtpPacket)
    {
        if (ArrayUtils.isNullOrEmpty(rtpLayers))
        {
            return null;
        }

        long encodingId = RtpLayerDesc.getEncodingId(videoRtpPacket);
        RtpLayerDesc desc = layersById.get(encodingId);
        if (desc != null)
        {
            return desc;
        }

        return Arrays.stream(rtpLayers)
                .filter(layer -> layer.matches(videoRtpPacket))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MediaSourceDesc ").append(hashCode()).append(" has layers:\n");
        for (RtpLayerDesc layerDesc : rtpLayers)
        {
            sb.append("  ").append(layerDesc.toString());
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * FIXME: this should probably check whether the specified SSRC is part
     * of this source (i.e. check all layers and include secondary SSRCs).
     *
     * @param ssrc the SSRC to match.
     * @return {@code true} if the specified {@code ssrc} is the primary SSRC
     * for this source.
     */
    public boolean matches(long ssrc)
    {
        return rtpLayers.length > 0 && rtpLayers[0].getPrimarySSRC() == ssrc;
    }
}
