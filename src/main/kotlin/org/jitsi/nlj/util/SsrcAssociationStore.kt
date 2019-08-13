package org.jitsi.nlj.util

import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import java.util.concurrent.CopyOnWriteArrayList

class SsrcAssociationStore : NodeStatsProducer {
    private val ssrcAssociations: MutableList<SsrcAssociation> = CopyOnWriteArrayList()

    fun addAssociation(ssrcAssociation: SsrcAssociation) {
        ssrcAssociations.add(ssrcAssociation)
    }

    // TODO: if either of these methods are too slow, we'll need
    // to hold multiple data structures (one primary -> secondary
    // and another secondary -> primary
    fun getPrimarySsrc(secondarySsrc: Long): Long? {
        return ssrcAssociations.find { it.secondarySsrc == secondarySsrc }?.primarySsrc
    }

    fun getSecondarySsrc(primarySsrc: Long, associationType: SsrcAssociationType): Long? {
        return ssrcAssociations.find { it.type == associationType && it.primarySsrc == primarySsrc }?.secondarySsrc
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("SSRC Association Store").apply {
            addString("SSRC associations", ssrcAssociations.toString())
        }
    }
}