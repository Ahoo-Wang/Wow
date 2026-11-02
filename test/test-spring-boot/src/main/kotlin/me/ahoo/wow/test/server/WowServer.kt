/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.test.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.client.Port
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.internal.InternalPort
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.spi.json.WowJson
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.client.ReactorResourceFactory
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.function.Supplier

/**
 * Starts the Wow application [App] for a test, with [profiles] active, as [wowServer] of its class does.
 *
 * ```kotlin
 * wowServer<BankServer>("dev").use { server ->
 *     val alice = Caller.of(subject = "alice")
 *     server.commands(alice).wait(OpenAccount("Alice"), "alice")
 *     server.queries(alice).snapshots(AccountState::class.java).state("alice")!!.state.owner.assert().isEqualTo("Alice")
 * }
 * ```
 */
inline fun <reified App : Any> wowServer(
    vararg profiles: String,
    properties: Map<String, Any?> = emptyMap(),
    sources: List<Class<*>> = emptyList(),
    clock: Clock? = null,
): WowServer = wowServer(App::class.java, profiles.toList(), properties, sources, clock)

/**
 * Starts the Wow application of [application] and [sources] for a test, with [profiles] active, and gives it back
 * running: both its ports bound on the loopback interface, at ports the system chooses, and its drain, stop and wait
 * timeouts short ([TEST_PROPERTIES]), so the test reaches it at once and closing it ([WowServer.close]) is quick.
 * [properties] add to them and override them, as command-line arguments, over the application's own configuration;
 * a null value leaves a key out. A [clock] is the node's: every wall time it and its stores read, so a test fixes or
 * moves time in one place.
 */
fun wowServer(
    application: Class<*>,
    profiles: List<String> = emptyList(),
    properties: Map<String, Any?> = emptyMap(),
    sources: List<Class<*>> = emptyList(),
    clock: Clock? = null,
): WowServer {
    val arguments = (TEST_PROPERTIES + properties).mapNotNull { (key, value) -> value?.let { "--$key=$it" } }
    val builder = SpringApplicationBuilder(application, *sources.toTypedArray()).profiles(*profiles.toTypedArray())
        .initializers(OWN_EVENT_LOOPS)
    clock?.let { given ->
        val register = ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
            context.beanFactory.registerSingleton(CLOCK, given)
        }
        builder.initializers(register)
    }
    val context = builder.run(*arguments.toTypedArray())
    return WowServer(context)
}

/**
 * The properties a test server starts with: its public and internal ports on the loopback interface, each at a port the
 * system chooses, so no other process takes a port picked before it binds it, and the timeouts a node waits on at
 * its close short, as a test's few commands need.
 */
val TEST_PROPERTIES: Map<String, Any> = mapOf(
    "spring.main.banner-mode" to "off",
    "server.port" to 0,
    "server.address" to LOOPBACK,
    "wow.internal.port" to 0,
    "wow.internal.host" to LOOPBACK,
    "wow.cluster.drain-timeout" to "1s",
    "wow.subscription.stop-timeout" to "2s",
    "wow.subscription.checkpoint-interval" to "100ms",
    "wow.wait.max-timeout" to "15s",
)

private const val LOOPBACK = "127.0.0.1"

/** The name of a test's clock bean, which takes the place of the node's. */
private const val CLOCK = "wowTestClock"

/**
 * Gives the server's public port event loops of its own, which closing it disposes, after the quiet period Spring Boot
 * reads (`spring.reactor.netty.shutdown-quiet-period`). Spring Boot's default, reactor-netty's global loops, are every
 * server's of the JVM, and closing one disposes them: a test that closed one server would stop every other one's ports,
 * a peer's or a long-lived fixture's.
 */
private val OWN_EVENT_LOOPS = ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
    val own = Supplier {
        ReactorResourceFactory().apply {
            isUseGlobalResources = false
            Binder.get(context.environment).bind(QUIET_PERIOD, Duration::class.java).ifBound(::setShutdownQuietPeriod)
        }
    }
    (context as GenericApplicationContext).registerBean(ReactorResourceFactory::class.java, own)
}

private const val QUIET_PERIOD = "spring.reactor.netty.shutdown-quiet-period"

/**
 * A Wow application a test started ([wowServer]): its [context], the [publicPort] and [internalPort] it bound, and
 * clients of them. A test drives the service as its users do, with the typed clients of the public port: [commands]
 * sends command objects and [queries] reads states, both as a caller, through the `wow-client` a peer calls with. The
 * JSON clients ([http], [internal]) are for what the wire itself must show: a route's shape, a header, a problem's
 * members ([Answer.problem]). Closing it closes the application and every client.
 *
 * ```kotlin
 * val alice = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
 * server.commands(alice).wait(Deposit(100), "alice").version.assert().isEqualTo(2)
 * val account = server.queries(alice).snapshots(AccountState::class.java).state("alice")!!
 * assertThrows<CommandRejectedException> { server.commands(alice).wait(Withdraw(150), "alice") }
 *     .code.assert().isEqualTo("bank.account.insufficient-balance")
 * ```
 */
class WowServer(val context: ConfigurableApplicationContext) : AutoCloseable {
    /** The port of the public server: the HTTP API. */
    val publicPort: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)

    /** The port of the internal server: the cluster channel, the wait signals, the services' calls. */
    val internalPort: Int = context.getBean(InternalPort::class.java).boundPort

    /** A client of the public port. */
    val http: WowHttp = WowHttp(URI.create("http://$LOOPBACK:$publicPort"))

    private var internalHttp: WowHttp? = null

    /** A client of the internal port, made on first use. */
    val internal: WowHttp
        get() = internalHttp ?: WowHttp(URI.create("http://$LOOPBACK:$internalPort")).also { internalHttp = it }

    /**
     * The typed clients of the public port, made on first use: they name the commands and models of the contracts the
     * node read, and send to its own contexts alone.
     */
    private val client: Lazy<WowClient> = lazy {
        val public = http.base
        val contracts = bean<ContractDirectory>()
        val served = contracts.localContexts
        WowClient.builder()
            .endpoints { context, port -> public.takeIf { port == Port.PUBLIC && context in served } }
            .user(UserCredential.DEVELOPMENT)
            .contracts(contracts.fragments)
            .build()
    }

    /**
     * The command client of the public port, sending as [caller]: the `wow-client` a peer calls with, in user mode. It
     * sends the commands of the contexts the server serves as objects, waits as their wait plans say, and throws a
     * rejection as a `CommandRejectedException`, any other problem as a `WowProblemException`, each with its error
     * code; a command of another context fails, as no endpoint is known for it. The caller goes as the development
     * identity headers ([UserCredential.DEVELOPMENT]), so the server runs a development profile (`dev`) with no
     * `CallerResolver` of the application's and CoSec off; [Caller.ANONYMOUS] sends none.
     */
    fun commands(caller: Caller): CommandClient = client.value.commands.of(caller)

    /**
     * The queries of the public port as [caller], sent as [commands] sends: the states, snapshots, events and datasets
     * of the contexts the server serves, by their state classes, read as that caller may.
     */
    fun queries(caller: Caller): Queries = client.value.queries.of(caller)

    /** The application's bean of [type]. */
    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    /** The application's bean of [T]. */
    inline fun <reified T : Any> bean(): T = bean(T::class.java)

    override fun close() {
        try {
            context.close()
        } finally {
            http.close()
            internalHttp?.close()
            if (client.isInitialized()) client.value.close()
        }
    }
}

/**
 * A JSON client of a server's port at [base], over HTTP/1.1: each call answers with the status, headers and body the
 * server gave, waiting at most [timeout] for it.
 */
class WowHttp(val base: URI, private val timeout: Duration = Duration.ofSeconds(30)) : AutoCloseable {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build()

    /** `GET` [path] with [headers]. */
    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer = request("GET", path, null, headers)

    /** `POST` [json] to [path] with [headers]; an empty object by default. */
    fun post(path: String, json: String? = "{}", headers: Map<String, String> = emptyMap()): Answer =
        request("POST", path, json, headers)

    /** [method] [path] with [headers], sending [json] as `application/json` when it is not null. */
    fun request(method: String, path: String, json: String? = null, headers: Map<String, String> = emptyMap()): Answer {
        val body = json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).method(method, body).apply {
            if (json != null) header("Content-Type", "application/json")
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.headers().map(), response.body())
    }

    override fun close() {
        client.shutdownNow()
    }
}

/**
 * A server's answer: its [status], [headers] and body, as [text] and, when it is JSON, as [body]; when it is an RFC 9457
 * problem, as [problem] too.
 */
class Answer(val status: Int, val headers: Map<String, List<String>>, val text: String) {
    /** The body as JSON; null when it is empty or not JSON. */
    val body: JsonNode? by lazy {
        text.takeIf { it.isNotBlank() }?.let { runCatching { WowJson.mapper.readTree(it) }.getOrNull() }
    }

    /**
     * The body as the problem the server answered (`application/problem+json`), its members typed: the [Problem.status],
     * the error [Problem.code], the [Problem.violations], the rejected command's [Problem.result], the aggregate's
     * [Problem.version], what a retry may do ([Problem.retry], with `Retry-After` as [Problem.retryAfter]) and every
     * other member as an extension. Null when the answer is not a problem.
     *
     * ```kotlin
     * val answer = server.http.post("/bank/account/alice/withdraw", """{"amount":0}""")
     * answer.problem!!.violations.single().path.assert().isEqualTo("amount")
     * ```
     */
    val problem: Problem? by lazy {
        body?.takeIf { it.isObject && header(CONTENT_TYPE)?.contains(PROBLEM_JSON, ignoreCase = true) == true }
            ?.let(::problemOf)
    }

    /** The first value of the header [name], whatever its case; null when the answer has none. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun toString(): String = "Answer($status, $text)"

    private fun problemOf(members: JsonNode): Problem = Problem(
        status = members.get("status")?.takeIf { it.isInt }?.asInt() ?: status,
        title = members.text("title").orEmpty(),
        detail = members.text("detail"),
        code = members.text("code"),
        violations = members.get("violations")?.takeIf { it.isArray }?.values()?.map {
            Violation.of(it.text("path").orEmpty(), it.text("code").orEmpty(), it.text("message").orEmpty())
        }.orEmpty(),
        result = members.get("result")?.takeIf { it.isObject }?.let {
            ANSWERS.treeToValue(it, CommandResult::class.java)
        },
        extensions = members.properties().filter { it.key !in MEMBERS }
            .associate { (name, value) -> name to ANSWERS.treeToValue(value, Any::class.java) },
        retryAfter = header(RETRY_AFTER)?.trim()?.toLongOrNull(),
        version = members.get(Problem.VERSION)?.takeIf { it.isInt }?.asInt(),
        retry = members.text(Problem.RETRY)?.let { WireNames.entryOf<Retry>(it) },
    )

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
        const val PROBLEM_JSON = "application/problem+json"
        const val RETRY_AFTER = "Retry-After"

        /** The members a [Problem] has a property of; every other one is an extension. */
        val MEMBERS = setOf(
            "type", "title", "status", "detail", "code", "violations", "result", "instance", Problem.VERSION,
            Problem.RETRY,
        )

        /** Reads what a server wrote, as a client does: an enum value it does not know is no failure. */
        val ANSWERS = WowJson.readerBuilder().build()
    }
}
