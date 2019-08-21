package org.jitsi.nlj.util

import org.jitsi.utils.MediaType
import java.util.concurrent.ConcurrentHashMap

class ReceiveSsrcStore(private val ssrcAssociationStore: SsrcAssociationStore) {
    private val receiveSsrcsByMediaType: MutableMap<MediaType, MutableSet<Long>> =
        ConcurrentHashMap()

    /**
     * 'Primary' media SSRCs (excludes things like RTX). Note that
     * all SSRCs added via [addReceiveSsrc]
     */
    val mediaSsrcs = mutableSetOf<Long>()
    val videoSsrcs = mutableSetOf<Long>()

    init {
        ssrcAssociationStore.onAssociation(this::onSsrcAssociation)
    }

    fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) {
        receiveSsrcsByMediaType.getOrPut(mediaType, { mutableSetOf() }).add(ssrc)
        if (ssrcAssociationStore.isPrimarySsrc(ssrc)) {
            mediaSsrcs.add(ssrc)
            if (mediaType == MediaType.VIDEO) {
                videoSsrcs.add(ssrc)
            }
        }
    }

    fun removeReceiveSsrc(ssrc: Long) {
        receiveSsrcsByMediaType.values.forEach { it.remove(ssrc) }
    }

    private fun onSsrcAssociation(ssrcAssociation: SsrcAssociation) {
        // We assume an association only involves SSRCs of the same media type
        val mediaType = receiveSsrcsByMediaType.entries
            .find { it.value.contains(ssrcAssociation.primarySsrc) }?.key ?: return

        if (mediaType == MediaType.VIDEO) {
            videoSsrcs.remove(ssrcAssociation.secondarySsrc)
        }
        mediaSsrcs.remove(ssrcAssociation.secondarySsrc)
    }
}