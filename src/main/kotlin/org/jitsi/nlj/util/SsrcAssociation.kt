package org.jitsi.nlj.util

import org.jitsi.nlj.rtp.SsrcAssociationType

class SsrcAssociation(
    val primarySsrc: Long,
    val secondarySsrc: Long,
    val type: SsrcAssociationType
)

// class LocalSsrcAssociation(
//     primarySsrc: Long,
//     secondarySsrc: Long,
//     type: SsrcAssociationType
// ) : SsrcAssociation(primarySsrc, secondarySsrc, type)
//
// class RemoteSsrcAssociation(
//     primarySsrc: Long,
//     secondarySsrc: Long,
//     type: SsrcAssociationType
// ) : SsrcAssociation(primarySsrc, secondarySsrc, type)
