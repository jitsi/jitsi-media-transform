package org.jitsi.nlj.rtp.bandwidthestimation

import com.nhaarman.mockitokotlin2.spy
import io.kotlintest.matchers.floats.shouldBeGreaterThan
import io.kotlintest.matchers.floats.shouldBeLessThan
import io.kotlintest.specs.ShouldSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates
import org.jitsi.nlj.test_utils.FakeScheduledExecutorService
import org.jitsi.nlj.util.NEVER
import org.jitsi.service.libjitsi.LibJitsi
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.LoggerImpl

/** A simulated packet, for bandwidth estimation testing. */
data class SimulatedPacket(
    val sendTime: Instant,
    val packetSize: Int,
    val ssrc: Long
)

/* TODO: move this to some util class */
const val NANOSECONDS_PER_SECOND = 1.0e9f

abstract class FixedRateSender(
    val executor: ScheduledExecutorService,
    val clock: Clock,
    var receiver: (SimulatedPacket) -> Unit
) {
    var nextPacket: ScheduledFuture<*>? = null
    var lastSendTime: Instant = NEVER

    var running = false

    var rate: Float by Delegates.observable(0.0f) {
        _, _, newValue ->
        nextPacket?.cancel(false)
        schedulePacket(false)
    }

    abstract fun nextPacketSize(): Int

    fun schedulePacket(justSent: Boolean) {
        if (!running || rate <= 0 || nextPacketSize() == 0) {
            nextPacket = null
        } else {
            val packetDelayTime = when (lastSendTime) {
                NEVER -> 0
                else -> {
                    var delayTime = (nextPacketSize() * Byte.SIZE_BITS * NANOSECONDS_PER_SECOND / rate).toLong()
                    if (!justSent) {
                        delayTime -= Duration.between(lastSendTime, clock.instant()).toNanos()
                    }
                    delayTime
                }
            }

            nextPacket = executor.schedule(::doSendPacket, packetDelayTime, TimeUnit.NANOSECONDS)
        }
    }

    fun doSendPacket() {
        val now = clock.instant()
        val sendNext = sendPacket(now)

        if (sendNext) {
            lastSendTime = now
            schedulePacket(true)
        }
    }

    abstract fun sendPacket(now: Instant): Boolean

    fun start() {
        running = true
        doSendPacket()
    }

    fun stop() {
        running = false
        nextPacket?.cancel(false)
    }
}

class PacketGenerator(
    executor: ScheduledExecutorService,
    clock: Clock,
    receiver: (SimulatedPacket) -> Unit,
    var packetSize: Int = 1250,
    val ssrc: Long = 0xcafebabe
) : FixedRateSender(executor, clock, receiver) {
    override fun nextPacketSize() = packetSize

    override fun sendPacket(now: Instant): Boolean {
        val packet = SimulatedPacket(now, packetSize, ssrc)
        receiver(packet)
        return true
    }
}

class PacketBottleneck(
    executor: ScheduledExecutorService,
    clock: Clock,
    receiver: (SimulatedPacket) -> Unit
) : FixedRateSender(executor, clock, receiver) {
    val queue = ArrayDeque<SimulatedPacket>()

    fun enqueue(packet: SimulatedPacket) {
        if (!running) {
            return
        }
        val queueWasEmpty = queue.isEmpty()
        queue.push(packet)
        if (queueWasEmpty) {
            schedulePacket(false)
        }
    }

    override fun sendPacket(now: Instant): Boolean {
        if (queue.isEmpty()) {
            return false
        }

        val packet = queue.pop()
        receiver(packet)
        return true
    }

    override fun nextPacketSize(): Int = queue.peek()?.packetSize ?: 0
}

class PacketDelayer(
    val executor: ScheduledExecutorService,
    val receiver: (SimulatedPacket) -> Unit,
    val delay: Duration
) {
    fun enqueue(packet: SimulatedPacket) {
        executor.schedule({ receiver(packet) }, delay.toNanos(), TimeUnit.NANOSECONDS)
    }
}

class PacketReceiver(
    val clock: Clock,
    val estimator: BandwidthEstimator,
    val rateReceiver: (Float) -> Unit
) {
    var seq = 0

    fun setRtt(rtt: Duration) = estimator.onRttUpdate(clock.instant(), rtt)

    fun receivePacket(packet: SimulatedPacket) {
        val now = clock.instant()
        estimator.processPacketArrival(now, packet.sendTime, now, seq, packet.packetSize)
        seq++
        rateReceiver(estimator.getCurrentBw(now))
    }
}

class BandwidthEstimationTest : ShouldSpec() {
    init {
        /* Internals of GoogleCc use ConfigurationService at construct time. */
        LibJitsi.start()
    }

    val ctx = DiagnosticContext()
    val logger = LoggerImpl(BandwidthEstimationTest::class.qualifiedName)
    val estimator: BandwidthEstimator = GoogleCcEstimator(ctx, logger)

    private val scheduler: FakeScheduledExecutorService = spy()
    val clock: Clock = scheduler.clock

    val rtt = Duration.ofMillis(200)
    val bottleneckRate = 1.0e6f

    val generator: PacketGenerator = PacketGenerator(scheduler, clock, { bottleneck.enqueue(it) })
    val bottleneck: PacketBottleneck = PacketBottleneck(scheduler, clock, { delayer.enqueue(it) })
    val delayer: PacketDelayer = PacketDelayer(scheduler, { receiver.receivePacket(it) }, rtt)
    val receiver: PacketReceiver = PacketReceiver(clock, estimator, { generator.rate = it })

    init {
        "Running bandwidth estimation test" {
            should("work correctly") {
                bottleneck.rate = bottleneckRate
                generator.rate = estimator.getCurrentBw(clock.instant())
                receiver.setRtt(rtt)

                bottleneck.start()
                generator.start()

                scheduler.runUntil(clock.instant().plus(30, ChronoUnit.SECONDS))

                generator.stop()
                bottleneck.stop()

                val finalBw = estimator.getCurrentBw(clock.instant())
                finalBw.shouldBeGreaterThan(bottleneckRate * 0.9f)
                finalBw.shouldBeLessThan(bottleneckRate * 1.1f)
            }
        }
    }
}
