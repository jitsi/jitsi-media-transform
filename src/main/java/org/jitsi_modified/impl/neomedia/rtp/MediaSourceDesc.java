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
     * The {@link RtpEncodingDesc}s that this {@link MediaSourceDesc}
     * possesses, ordered by their subjective quality from low to high.
     */
    private final RtpEncodingDesc[] rtpEncodings;

    /**
     * Allow the lookup of a layer by the encoding id of a received packet.
     */
    private final Map<Long, RtpLayerDesc> layersById = new HashMap<>();

    /**
     * Allow the lookup of a layer by index .
     */
    private final Map<Integer, RtpLayerDesc> layersByIndex = new HashMap<>();

    /**
     * A string which identifies the owner of this source (e.g. the endpoint
     * which is the sender of the source).
     */
    private final String owner;

    /**
     * Ctor.
     *
     * @param rtpEncodings The {@link RtpEncodingDesc}s that this instance
     * possesses.
     */
    public MediaSourceDesc(
        RtpEncodingDesc[] rtpEncodings)
    {
        this(rtpEncodings, null);
    }

    /**
     * Ctor.
     *
     * @param rtpEncodings The {@link RtpEncodingDesc}s that this instance
     * possesses.
     */
    public MediaSourceDesc(
        RtpEncodingDesc[] rtpEncodings,
        String owner)
    {
        this.rtpEncodings = rtpEncodings;
        this.owner = owner;
    }

    public void updateLayerCache()
    {
        layersById.clear();
        layersByIndex.clear();

        for (RtpEncodingDesc encoding: this.rtpEncodings)
        {
            for (RtpLayerDesc layer: encoding.getLayers())
            {
                layersById.put(encoding.encodingId(layer), layer);
                layersByIndex.put(layer.getIndex(), layer);
            }
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
     * Returns an array of all the {@link RtpEncodingDesc}s for this instance,
     * in subjective quality ascending order.
     *
     * @return an array of all the {@link RtpEncodingDesc}s for this instance,
     * in subjective quality ascending order.
     */
    public RtpEncodingDesc[] getRtpEncodings()
    {
        return rtpEncodings;
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
        RtpLayerDesc layer = getRtpLayerByQualityIdx(idx);
        if (layer == null)
        {
            return 0;
        }

        // TODO: previous code returned a lower layer if this layer's bitrate was 0.
        // Do we still need this?
        return layer.getBitrateBps(nowMs);
    }

    public boolean hasRtpLayers()
    {
        return !layersByIndex.isEmpty();
    }

    public Iterable<RtpLayerDesc> getRtpLayers()
    {
        return Arrays.stream(rtpEncodings).flatMap(e -> Arrays.stream(e.getLayers()))::iterator;
    }

    public int numRtpLayers()
    {
        int total = 0;
        for (RtpEncodingDesc encoding: rtpEncodings)
        {
            total += encoding.getLayers().length;
        }

        return total;
    }

    public long getPrimarySSRC()
    {
        return rtpEncodings[0].getPrimarySSRC();
    }

    public RtpLayerDesc getRtpLayerByQualityIdx(int idx)
    {
        return layersByIndex.get(idx);
    }

    public RtpLayerDesc findRtpLayerDesc(VideoRtpPacket videoRtpPacket)
    {
        if (ArrayUtils.isNullOrEmpty(rtpEncodings))
        {
            return null;
        }

        long encodingId = RtpLayerDesc.getEncodingId(videoRtpPacket);
        RtpLayerDesc desc = layersById.get(encodingId);
        if (desc != null)
        {
            return desc;
        }

        for (RtpEncodingDesc encoding: rtpEncodings)
        {
            if (encoding.matches(videoRtpPacket.getSsrc()))
            {
                return encoding.findRtpLayerDesc(videoRtpPacket);
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MediaSourceDesc ").append(hashCode()).append(" has encodings:\n");
        for (RtpEncodingDesc encodingDesc : rtpEncodings)
        {
            sb.append("  ").append(encodingDesc.toString());
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
        return rtpEncodings.length > 0 && rtpEncodings[0].getPrimarySSRC() == ssrc;
    }
}
