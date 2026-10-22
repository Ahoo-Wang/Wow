package me.ahoo.wow.benchmark.transfer

import io.netty.util.concurrent.EventExecutor
import me.ahoo.wow.spring.boot.InternalServer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.netty.http.HttpResources
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Diagnostics of the signal timeouts: event-loop lag, JVM hiccups, CPU and GC, a line a second. */
class BenchDiag : AutoCloseable {
    private val ticker = Executors.newScheduledThreadPool(2) { task -> Thread(task, "bench-diag").apply { isDaemon = true } }
    private val hiccup = AtomicLong()
    private val loopLag = ConcurrentHashMap<String, AtomicLong>()
    private var gcLast = 0L
    @Volatile private var running = true

    fun start() {
        Thread({
            while (running) {
                val t = System.nanoTime()
                Thread.sleep(5)
                val over = (System.nanoTime() - t) / 1_000_000 - 5
                hiccup.accumulateAndGet(over, ::maxOf)
            }
        }, "bench-hiccup").apply { isDaemon = true }.start()
        ticker.scheduleAtFixedRate({ probeLoops() }, 1000, 50, TimeUnit.MILLISECONDS)
        ticker.scheduleAtFixedRate({ report() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun probeLoops() {
        HttpResources.get().onServer(true).forEach { executor: EventExecutor ->
            val name = executor.toString().substringAfterLast('@')
            val at = System.nanoTime()
            executor.execute {
                loopLag.computeIfAbsent(name) { AtomicLong() }
                    .accumulateAndGet((System.nanoTime() - at) / 1_000_000, ::maxOf)
            }
        }
    }

    private fun report() {
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val gc = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
        val gcDelta = gc - gcLast
        gcLast = gc
        val lags = loopLag.entries.sortedBy { it.key }.joinToString(",") { "${it.value.getAndSet(0)}" }
        val versions = InternalServer.DIAG_VERSIONS.entries.sortedBy { it.key }
            .joinToString(",") { "\"${it.key}\":${it.value.get()}" }
        log.warn(
            "bench-diag {\"hiccupMs\":${hiccup.getAndSet(0)},\"loopLagMs\":[$lags],\"gcMs\":$gcDelta," +
                "\"cpuProc\":${"%.2f".format(os.processCpuLoad)},\"cpuSys\":${"%.2f".format(os.cpuLoad)}," +
                "\"threads\":${ManagementFactory.getThreadMXBean().threadCount},\"internal\":{$versions}}",
        )
    }

    override fun close() {
        running = false
        ticker.shutdownNow()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(BenchDiag::class.java)
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.signals", name = ["enabled"], havingValue = "true")
class BenchDiagConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchDiag(): BenchDiag = BenchDiag()
}
