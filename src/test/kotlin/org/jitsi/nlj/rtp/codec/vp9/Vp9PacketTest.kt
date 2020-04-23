package org.jitsi.nlj.rtp.codec.vp9

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer
import javax.xml.bind.DatatypeConverter

class Vp9PacketTest : ShouldSpec() {

    /* Packet captured from Chrome VP9 call */
    private val packetData1 =
        DatatypeConverter.parseHexBinary(
                // RTP
            "906536b69f3077686098017b" +
                // RTP header extension
                "bede0002" + "3202168751210700" +
                // I=1,P=0,L=1,F=0,B=1,E=0,V=1,(Z=0)
                "aa" +
                // M=1,PID=0x1e65=7781
                "9e65" +
                // TID=0,U=0,SID=0,D=0
                "00" +
                // TL0PICIDX=0xfd=253
                "fd" +
                // Begin SS: N_S=2,Y=1,G=1
                "58" +
                // WIDTH=320
                "0140" +
                // HEIGHT=180
                "00b4" +
                // WIDTH=640
                "0280" +
                // HEIGHT=360
                "0168" +
                // WIDTH=1280
                "0500" +
                // HEIGHT=720
                "02d0" +
                // N_G=4
                "04" +
                // TID=0,U=0,R=1
                "04" +
                // P_DIFF=4
                "04" +
                // TID=2,U=1,R=1
                "54" +
                // P_DIFF=1
                "01" +
                // TID=1,U=1,R=1
                "34" +
                // P_DIFF=2
                "02" +
                // TID=2,U=1,R=1
                "54" +
                // P_DIFF=1
                "01" +
                // VP8 media
                "834983420013f00b3827f8167858e0907063a8000fc05f7d47bc0ff89d17d17a1f41f63fa3fcfe93d17a1ec7ea7f57a5efa3d8e2a6972f7efabebbbc544a77e0e58efad41bc774580eae544a8fae28c022e9eec0007fb8fb7cb043eaaf44e8ce16b562814b6ce4415af5707b427d45250830ff3b85b4fd597d3630b5c19582896aa1b843f07ff38b97320772946aadd7a9158dd3abf74bcfa8c101ffe29dc5fe4ab14c519605b301738f96e3901bb730781e5033ee32966e8815556a2192973585cc5c9e5a980d5de00a2af2e84ac4cb3c340e1dc9abe3579eb63e7aa2acc9946c4ef63791239fc2a11c81d86bf85d2ded77de684d634a683a585cc474082c2a1ccad0214b900853bb8ccebaaf64fcaa805cd2279fb92abb9db54e513be9be6b023daa0e609aae4abf816a909654ecd18ed511de5bb3797405f2961e558f3d7779f2d140e05766f3fd538f422aa934a3a83b4fbefb503583de11b4875afd28b1b752a1c9cb7464bdacc184975ae232524fb6673bcd4e1c63e9a95152015e0c96b402664705cfb62bae067f63fd65a4af60f01ad90dad6b121f372e49c65fc0cc65e39a5dc7ca121a56147403c1791298229ac264b35f49e920d75c50bf390e742980423fdcad915195114b1ff860cecdee9b20a0be9e379be0d593a7d02e5ec2606b3bd97e4633195000575f8c90558d63fbe6081deecb825a09760d3ab39b7f2588f4bec048bdc42caca803748dc3a4ff252536b23974161627f240a98bc9b05d775e5d34b243efb921cff0c5ac32f31416e1977632831166717cf4a5e0ca3cda49e536ddce91d3c7a49aaadee03b451db4db46fd7e3c008a7a1ed6d89a1737c18b295205ce8334502c01179e771003e1e91bafabe29cd9e3df2b120b823c4a05ad2056e80ca34cd49f73b6bf843e420365ba6d4004a4cf1f1aa8ce262c350a0998b67f0f20ae93d5857ef78a3f15dbb793cd03a1b102693c362f8df17cf722729499f1ea60868b5ead94359a7e2afc34d2a8072457e95422f6ab2258836bb33f0190e7989208cc1789fd197364c08a2eef40f01bad896cb3808fba179237e6131df6ad0c5afab2fc6053c550314eaba951ac26fdb47a65a059186ecd0370204436f0f35794c2726b30779fa580e4101614ac6c94d935b347897f6300db72c4809f03f24d9e73dde36ecb068ff60060ef2571cd16c9b4161272eac16dd66824852c24f0267c77edd98d78ae1617b44029d71b397f32044ce1c02fdb5a29c031396b54fc12895f0f73ddcdd19931df1d23f99f2a5a189097fad57d4631e18c8d8aa8a6015776f87c5da82604f03f8a57b2cdb4bd6079de81f8d912c966067c3ffffe3cf522bb93e5c7597b083b4915"
        )

    private val packet1 = Vp9Packet(packetData1, 0, packetData1.size)

    init {
        "A VP9 packet" {
            val p = packet1
            should("be parsed correctly") {
                p.isStartOfFrame shouldBe true
                p.isEndOfFrame shouldBe false
                p.hasPictureId shouldBe true
                p.hasExtendedPictureId shouldBe true
                p.pictureId shouldBe 7781
                p.hasTL0PICIDX shouldBe true
                p.TL0PICIDX shouldBe 253
            }
        }

        "A VP9 Descriptor" {
            should("be the right size") {
                val descSz = DePacketizer.VP9PayloadDescriptor.getSize(packetData1, packet1.payloadOffset, packet1.payloadLength)
                // TODO
                descSz shouldBe 27
            }
        }
    }
}
