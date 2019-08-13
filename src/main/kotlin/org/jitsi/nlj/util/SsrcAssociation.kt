package org.jitsi.nlj.util

import org.jitsi.nlj.rtp.SsrcAssociationType

data class SsrcAssociation(
    val primarySsrc: Long,
    val secondarySsrc: Long,
    val type: SsrcAssociationType
) {
    override fun toString(): String = "$secondarySsrc -> $primarySsrc ($type)"
}
