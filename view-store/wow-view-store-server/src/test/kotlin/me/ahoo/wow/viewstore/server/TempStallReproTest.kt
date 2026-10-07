package me.ahoo.wow.viewstore.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.netty.channel.Channel
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.netty.http.client.HttpClient
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArraySet

/** TEMPORARY diagnostic for the CI-only /v3/api-docs stall; not for merge. */
class TempStallReproTest {
    @Configuration
    class Diag {
        @Bean
        fun sharedBoardReferences(): SharedBoardReferences = SharedBoardReferences { _, _, _ -> Flux.empty() }

        @Bean
        fun recordServerChannels(): NettyServerCustomizer = NettyServerCustomizer { server ->
            server.doOnConnection { SERVER_CHANNELS.add(it.channel()) }
        }
    }

    private val props = arrayOf(
        "server.port=0",
        "wow.eventsourcing.store.storage=in_memory",
        "wow.eventsourcing.snapshot.storage=in_memory",
        "wow.mongo.enabled=false",
        "wow.kafka.enabled=false",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
        "wow.view-store.system-views[0].definition-id=orders",
        "wow.view-store.system-views[0].id=orders-open",
        "wow.view-store.system-views[0].title=Open orders",
        "wow.view-store.system-views[0].config={\"kind\":\"record\"}",
        "logging.level.root=WARN",
    )

    private val httpClient = HttpClient.create().compress(true).doOnConnected { CLIENT_CHANNELS.add(it.channel()) }

    private fun client(port: String, timeout: Duration) = WebTestClient.bindToServer(ReactorClientHttpConnector(httpClient))
        .baseUrl("http://localhost:$port")
        .responseTimeout(timeout)
        .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
        .build()

    @Test
    fun contexts() {
        val n = (System.getenv("REPRO_CONTEXTS") ?: "40").toInt()
        for (i in 0 until n) {
            SERVER_CHANNELS.clear()
            CLIENT_CHANNELS.clear()
            EVENTS.clear()
            val ctx = SpringApplicationBuilder(ViewStoreServer::class.java, Diag::class.java)
                .run(*props.map { "--$it" }.toTypedArray())
            captureNettyDebug()
            try {
                val port = ctx.environment.getRequiredProperty("local.server.port")
                val c = { client(port, Duration.ofSeconds(30)) }
                c().get().uri("/view-store/tenant/t1/owner/(shared)/system-views/orders-open")
                    .header(ViewStoreService.APP_ID_HEADER, "console").exchange().expectStatus().isOk
                c().get().uri("/view-store/tenant/t1/owner/(shared)/system-views?definitionId=orders")
                    .header(ViewStoreService.APP_ID_HEADER, "console").exchange().expectStatus().isOk
                val cc = c()
                cc.post().uri("/view-store/tenant/t1/owner/alice/view")
                    .header(ViewStoreService.APP_ID_HEADER, "console")
                    .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"definitionId":"orders","title":"Mine","config":{"kind":"record"}}""")
                    .exchange().expectStatus().isOk
                cc.get().uri("/view-store/tenant/t1/owner/alice/definitions/orders/preferences")
                    .header(ViewStoreService.APP_ID_HEADER, "console").exchange().expectStatus().isOk
                val dump = Thread {
                    try {
                        Thread.sleep(System.getenv("REPRO_STALL_MS")?.toLong() ?: 15_000)
                        report("context $i: /v3/api-docs has not answered in 15 s")
                    } catch (_: InterruptedException) {
                    }
                }.apply { isDaemon = true; start() }
                val t = System.nanoTime()
                try {
                    c().get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody().returnResult()
                } finally {
                    dump.interrupt()
                }
                System.err.println("CTX $i api-docs ${(System.nanoTime() - t) / 1_000_000} ms")
            } finally {
                ctx.close()
            }
        }
    }
}

private val SERVER_CHANNELS = CopyOnWriteArraySet<Channel>()
private val CLIENT_CHANNELS = CopyOnWriteArraySet<Channel>()
private val EVENTS = ConcurrentLinkedDeque<String>()

private fun captureNettyDebug() {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            EVENTS.add("${Instant.ofEpochMilli(event.timeStamp)} [${event.threadName}] ${event.loggerName}: ${event.formattedMessage}")
            while (EVENTS.size > 400) EVENTS.pollFirst()
        }
    }
    appender.context = context
    appender.start()
    listOf("reactor.netty.http.server", "reactor.netty.http.client", "reactor.netty.resources", "reactor.netty.channel")
        .forEach { name ->
            (context.getLogger(name) as Logger).apply {
                level = Level.DEBUG
                isAdditive = false
                addAppender(appender)
            }
        }
}

private fun field(target: Any, name: String): Any? = runCatching {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let {
            it.isAccessible = true
            return@runCatching it.get(target)
        }
        type = type.superclass
    }
    "<no field>"
}.getOrElse { "<$it>" }

private fun describe(channel: Channel): String = buildString {
    append(channel).append(" active=").append(channel.isActive).append(" open=").append(channel.isOpen)
        .append(" autoRead=").append(channel.config().isAutoRead).append(" loop=").append(channel.eventLoop())
        .append(" pipeline=").append(channel.pipeline().names())
    channel.pipeline().toMap().forEach { (name, handler) ->
        if (handler.javaClass.simpleName == "HttpTrafficHandler") {
            append("\n    HttpTrafficHandler pendingResponses=").append(field(handler, "pendingResponses"))
                .append(" persistentConnection=").append(field(handler, "persistentConnection"))
                .append(" pipelined=").append(field(handler, "pipelined"))
                .append(" overflow=").append(field(handler, "overflow"))
                .append(" read=").append(field(handler, "read"))
                .append(" finalizingResponse=").append(field(handler, "finalizingResponse"))
                .append(" nonInformationalResponse=").append(field(handler, "nonInformationalResponse"))
                .append(" (").append(name).append(')')
        }
    }
    reactor.netty.Connection.from(channel).let { conn ->
        append("\n    ops=").append(conn).append(' ').append(conn.javaClass.name)
        (conn as? reactor.netty.channel.ChannelOperations<*, *>)?.let { ops ->
            append(" inbound=").append(field(ops, "inbound"))
            append(" isDisposed=").append(ops.isDisposed)
        }
    }
}

private fun report(title: String) {
    val out = PrintStream(FileOutputStream(FileDescriptor.err), true)
    val text = buildString {
        append("==== STALL DIAG: ").append(title).append(" ====\n")
        append("-- server channels --\n")
        SERVER_CHANNELS.forEach { append(describe(it)).append('\n') }
        append("-- client channels --\n")
        CLIENT_CHANNELS.forEach { append(describe(it)).append('\n') }
        append("-- netty debug events --\n")
        EVENTS.forEach { append(it).append('\n') }
        append("-- threads --\n")
        java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).forEach { info ->
            append('"').append(info.threadName).append("\" ").append(info.threadState)
            info.lockName?.let { append(" on ").append(it) }
            info.lockOwnerName?.let { append(" owned by \"").append(it).append('"') }
            append('\n')
            info.stackTrace.take(60).forEach { append("    at ").append(it).append('\n') }
            append('\n')
        }
        append("==== END STALL DIAG ====\n")
    }
    out.print(text)
    runCatching { java.io.File("build/stall-diag-" + System.currentTimeMillis() + ".txt").writeText(text) }
}
