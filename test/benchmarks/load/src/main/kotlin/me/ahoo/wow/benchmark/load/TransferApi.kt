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

/** How long a server may wait for a command's completion point before it answers: 10 s. */
val WAIT_TIMEOUT: Duration = Duration.ofSeconds(10)

/** The point a command's request waits for, the completion of a scenario (test/benchmarks/README.md). */
enum class Wait {
    /** E1: the command's events are stored (`committed`). */
    STORED,

    /** E2: the snapshot holds the commit (`snapshot`). */
    SNAPSHOT,

    /**
     * E3: the transfer saga handled the source's `Prepared` and the target's `Entry` is stored: the chain
     * `committed > handled(TransferSaga.onPrepared) > committed`.
     */
    TRANSFER_ENTERED,
}

/** The benchmark server's routes and wait headers (wow-http): `/transfer/account/{id}/<command>`, `Wow-Wait` plans. */
object TransferApi {
    const val CONTEXT = "transfer"
    const val OK = 200
    private const val JSON = "application/json"
    private const val PLAN = "Wow-Wait"
    private const val TIMEOUT = "Wow-Wait-Timeout"

    /** The client's own bound on a request: beyond the servers' wait timeout, so the server answers first. */
    val REQUEST_TIMEOUT: Duration = WAIT_TIMEOUT.plusSeconds(20)

    /** The request that opens account [id] with [balance], answered once its snapshot holds it. */
    fun openAccount(base: URI, id: String, owner: String, balance: Long): HttpRequest =
        post(command(base, id, "open-account"), """{"owner":"$owner","balance":$balance}""", *wait(Wait.SNAPSHOT))

    /** The request that deposits [amount] on account [id], answered at [wait]. */
    fun deposit(base: URI, id: String, amount: Long, wait: Wait): HttpRequest =
        post(command(base, id, "deposit"), """{"amount":$amount}""", *wait(wait))

    /** The request that transfers [amount] from account [id] to [to], answered once the target entered it. */
    fun prepare(base: URI, id: String, to: String, amount: Long): HttpRequest =
        post(command(base, id, "prepare"), """{"to":"$to","amount":$amount}""", *wait(Wait.TRANSFER_ENTERED))

    /**
     * The request that attaches [notes] to account [id], one event each, answered once stored: the large-payload
     * variant (note ⑥).
     */
    fun annotate(base: URI, id: String, notes: String): HttpRequest =
        post(command(base, id, "annotate"), """{"notes":$notes}""", *wait(Wait.STORED))

    /** The request that reads account [id]'s state from the snapshots. */
    fun state(base: URI, id: String): HttpRequest = get(base.resolve("/$CONTEXT/account/$id/state"))

    /** The request that lists [limit] accounts from the snapshots. */
    fun list(base: URI, limit: Int): HttpRequest = post(
        base.resolve("/$CONTEXT/account/snapshot/query"),
        """{"kind":"list","filter":{"op":"match-all"},"limit":$limit}""",
    )

    /** Whether a command's answer says the command reached its wait point; null when it did, else why not. */
    fun commandError(status: Int, body: String): String? = when {
        status != OK -> "http-$status${Json.string(body, "code")?.let { "-$it" } ?: ""}"
        !body.contains("\"status\":\"succeeded\"") -> "status-${Json.string(body, "status") ?: "unknown"}"
        else -> null
    }

    /**
     * Whether a read was answered; null when it was, else why not, with the problem's code when it has one:
     * `read-http-429-wow.query.busy` is a query its store's bulkhead refused.
     */
    fun readError(status: Int, body: String): String? =
        if (status == OK) null else "read-http-$status${Json.string(body, "code")?.let { "-$it" } ?: ""}"

    /** The aggregate version a command's answer reports. */
    fun commandVersion(body: String): Long? = Json.long(body, "version")

    /** The aggregate version a state read's answer reports. */
    fun stateVersion(body: String): Long? = Json.long(body, "version")

    /** The aggregate a command's answer names: for E3, the target of the transfer. */
    fun commandAggregate(body: String): String? = Json.string(body, "id")

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

    private fun post(uri: URI, body: String, vararg headers: Pair<String, String>): HttpRequest =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun get(uri: URI): HttpRequest =
        HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", JSON).GET().build()
}
