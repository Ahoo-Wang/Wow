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

package me.ahoo.wow.benchmark.load

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** How long a server may wait for a command's completion point before it answers (both versions: 10 s). */
val WAIT_TIMEOUT: Duration = Duration.ofSeconds(10)

/** The point a command's request waits for, the completion of a scenario (wow-benchmarks/e2e/README.md). */
enum class Wait {
    /** E1: the command's events are stored. 9.x `PROCESSED`, V10 `committed`. */
    STORED,

    /** E2: the snapshot holds the commit. 9.x `SNAPSHOT`, V10 `snapshot`. */
    SNAPSHOT,

    /**
     * E3: the transfer saga handled the source's `Prepared` and the target's `Entry` is stored. 9.x waits
     * `SAGA_HANDLED` of `TransferSaga.onPrepared` with the tail `PROCESSED`, V10 the chain
     * `committed > handled(TransferSaga.onPrepared) > committed`.
     */
    TRANSFER_ENTERED,
}

/**
 * The routes and wait headers of one version: the same request on both versions reaches the same completion point
 * (wow-benchmarks/e2e/README.md lists the mapping and where it is not exactly the same).
 */
sealed class VersionAdapter(val name: String) {
    /** The request that opens account [id] with [balance], answered once its snapshot holds it. */
    abstract fun openAccount(base: URI, id: String, owner: String, balance: Long): HttpRequest

    /** The request that deposits [amount] on account [id], answered at [wait]. */
    abstract fun deposit(base: URI, id: String, amount: Long, wait: Wait): HttpRequest

    /** The request that transfers [amount] from account [id] to [to], answered once the target entered it. */
    abstract fun prepare(base: URI, id: String, to: String, amount: Long): HttpRequest

    /**
     * The request that attaches [notes] to account [id], one event each, answered once stored: the large-payload
     * variant (D224 note ⑥). V10 only; 9.x's domain has no such command.
     */
    open fun annotate(base: URI, id: String, notes: String): HttpRequest =
        throw UnsupportedOperationException("$name has no large-payload command.")

    /** Whether this version's domain has what [scenario] sends. */
    open fun supports(scenario: Scenario): Boolean = scenario != Scenario.E1_LARGE

    /** The request that reads account [id]'s state from the snapshots. */
    abstract fun state(base: URI, id: String): HttpRequest

    /** The request that lists [limit] accounts from the snapshots. */
    abstract fun list(base: URI, limit: Int): HttpRequest

    /** Whether a command's answer says the command reached its wait point; null when it did, else why not. */
    abstract fun commandError(status: Int, body: String): String?

    /** The aggregate version a command's answer reports. */
    abstract fun commandVersion(body: String): Long?

    /** The aggregate version a state read's answer reports. */
    abstract fun stateVersion(body: String): Long?

    /** The aggregate a command's answer names: for E3, the target of the transfer. */
    abstract fun commandAggregate(body: String): String?

    protected fun post(uri: URI, body: String, vararg headers: Pair<String, String>): HttpRequest =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    protected fun get(uri: URI): HttpRequest =
        HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", JSON).GET().build()

    /** 9.x (wow-webflux): `/account/{id}/<action>`, `Command-Wait-*` headers, `CommandResult` answers. */
    object NineX : VersionAdapter("9x") {
        private const val STAGE = "Command-Wait-Stage"
        private const val TIMEOUT = "Command-Wait-Timeout"

        override fun openAccount(base: URI, id: String, owner: String, balance: Long): HttpRequest =
            post(command(base, id, "open-account"), """{"owner":"$owner","balance":$balance}""", *wait(Wait.SNAPSHOT))

        override fun deposit(base: URI, id: String, amount: Long, wait: Wait): HttpRequest =
            post(command(base, id, "deposit"), """{"amount":$amount}""", *wait(wait))

        override fun prepare(base: URI, id: String, to: String, amount: Long): HttpRequest =
            post(command(base, id, "prepare"), """{"to":"$to","amount":$amount}""", *wait(Wait.TRANSFER_ENTERED))

        override fun state(base: URI, id: String): HttpRequest = get(base.resolve("/account/$id/snapshot"))

        override fun list(base: URI, limit: Int): HttpRequest =
            post(base.resolve("/account/snapshot/list"), """{"condition":{"operator":"ALL"},"limit":$limit}""")

        override fun commandError(status: Int, body: String): String? = when {
            status != OK -> "http-$status"
            !body.contains("\"errorCode\":\"Ok\"") -> "error-${Json.string(body, "errorCode") ?: "unknown"}"
            else -> null
        }

        override fun commandVersion(body: String): Long? = Json.long(body, "aggregateVersion")

        override fun stateVersion(body: String): Long? = Json.long(body, "version")

        override fun commandAggregate(body: String): String? = Json.string(body, "aggregateId")

        private fun command(base: URI, id: String, action: String): URI = base.resolve("/account/$id/$action")

        private fun wait(wait: Wait): Array<Pair<String, String>> {
            val timeout = TIMEOUT to WAIT_TIMEOUT.toMillis().toString()
            return when (wait) {
                Wait.STORED -> arrayOf(STAGE to "PROCESSED", timeout)
                Wait.SNAPSHOT -> arrayOf(STAGE to "SNAPSHOT", timeout)
                Wait.TRANSFER_ENTERED -> arrayOf(
                    STAGE to "SAGA_HANDLED",
                    "Command-Wait-Context" to CONTEXT,
                    "Command-Wait-Processor" to "TransferSaga",
                    "Command-Wait-Function" to "onPrepared",
                    "Command-Wait-Tail-Stage" to "PROCESSED",
                    timeout,
                )
            }
        }
    }

    /** V10 (wow-http): `/transfer/account/{id}/<command>`, `Wow-Wait` plans, problem+json errors. */
    object V10 : VersionAdapter("v10") {
        private const val PLAN = "Wow-Wait"
        private const val TIMEOUT = "Wow-Wait-Timeout"

        override fun openAccount(base: URI, id: String, owner: String, balance: Long): HttpRequest =
            post(command(base, id, "open-account"), """{"owner":"$owner","balance":$balance}""", *wait(Wait.SNAPSHOT))

        override fun deposit(base: URI, id: String, amount: Long, wait: Wait): HttpRequest =
            post(command(base, id, "deposit"), """{"amount":$amount}""", *wait(wait))

        override fun prepare(base: URI, id: String, to: String, amount: Long): HttpRequest =
            post(command(base, id, "prepare"), """{"to":"$to","amount":$amount}""", *wait(Wait.TRANSFER_ENTERED))

        override fun annotate(base: URI, id: String, notes: String): HttpRequest =
            post(command(base, id, "annotate"), """{"notes":$notes}""", *wait(Wait.STORED))

        override fun supports(scenario: Scenario): Boolean = true

        override fun state(base: URI, id: String): HttpRequest = get(base.resolve("/$CONTEXT/account/$id/state"))

        override fun list(base: URI, limit: Int): HttpRequest = post(
            base.resolve("/$CONTEXT/account/snapshot/query"),
            """{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":$limit}""",
        )

        override fun commandError(status: Int, body: String): String? = when {
            status != OK -> "http-$status${Json.string(body, "code")?.let { "-$it" } ?: ""}"
            !body.contains("\"status\":\"SUCCEEDED\"") -> "status-${Json.string(body, "status") ?: "unknown"}"
            else -> null
        }

        override fun commandVersion(body: String): Long? = Json.long(body, "version")

        override fun stateVersion(body: String): Long? = Json.long(body, "version")

        override fun commandAggregate(body: String): String? = Json.string(body, "id")

        private fun command(base: URI, id: String, name: String): URI = base.resolve("/$CONTEXT/account/$id/$name")

        private fun wait(wait: Wait): Array<Pair<String, String>> {
            val timeout = TIMEOUT to "${WAIT_TIMEOUT.toMillis()}ms"
            return when (wait) {
                Wait.STORED -> arrayOf(PLAN to "committed", timeout)
                Wait.SNAPSHOT -> arrayOf(PLAN to "snapshot", timeout)
                Wait.TRANSFER_ENTERED -> arrayOf(
                    PLAN to "committed > handled(TransferSaga.onPrepared) > committed",
                    timeout
                )
            }
        }
    }

    companion object {
        const val CONTEXT = "transfer"
        const val OK = 200
        private const val JSON = "application/json"

        /** The client's own bound on a request: beyond the servers' wait timeout, so the server answers first. */
        val REQUEST_TIMEOUT: Duration = WAIT_TIMEOUT.plusSeconds(20)

        fun of(name: String): VersionAdapter = when (name) {
            NineX.name -> NineX
            V10.name -> V10
            else -> throw IllegalArgumentException("Unknown version '$name': 9x or v10.")
        }
    }
}
