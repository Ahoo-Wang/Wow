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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/** The id of the seeded account [index]. */
fun accountId(index: Int): String = "acc-%07d".format(index)

/** The owner of the seeded account [index]: a hundred owners share the accounts. */
fun ownerOf(index: Int): String = "owner-${index % OWNERS}"

private const val OWNERS = 100

/** What one operation targets: an account, and for a transfer the account it goes to. */
data class Target(val base: URI, val account: Int, val other: Int)

/**
 * An end-to-end scenario: what one operation sends, and what makes it complete. An operation completes with
 * null when it reached its completion point, or with why not (an error kind, counted in the results).
 */
enum class Scenario(val description: String, val transfers: Boolean = false) {
    /** E1 write: a deposit, answered once its events are stored. */
    E1("deposit, answered once stored") {
        override fun run(client: Client, target: Target): CompletableFuture<String?> =
            client.command(TransferApi.deposit(target.base, accountId(target.account), 1, Wait.STORED))
    },

    /** E2 read-your-write: a deposit answered once the snapshot holds it, then a read of the state that shows it. */
    E2("deposit answered at the snapshot, then a state read that shows it") {
        override fun run(client: Client, target: Target): CompletableFuture<String?> {
            val id = accountId(target.account)
            return client.send(TransferApi.deposit(target.base, id, 1, Wait.SNAPSHOT)).thenCompose { answer ->
                val error = TransferApi.commandError(answer.statusCode(), answer.body())
                if (error != null) return@thenCompose CompletableFuture.completedFuture(error)
                val written = TransferApi.commandVersion(answer.body())
                client.send(TransferApi.state(target.base, id)).thenApply { read ->
                    val version = TransferApi.stateVersion(read.body())
                    when {
                        read.statusCode() != TransferApi.OK -> "read-http-${read.statusCode()}"
                        written == null || version == null -> "read-no-version"
                        version < written -> "read-stale"
                        else -> null
                    }
                }
            }
        }
    },

    /** E3 cross-aggregate saga chain: a transfer, answered once the target account has entered the amount. */
    E3("transfer, answered once the saga's entry is stored on the target", transfers = true) {
        override fun run(client: Client, target: Target): CompletableFuture<String?> {
            val to = accountId(target.other)
            return client.send(TransferApi.prepare(target.base, accountId(target.account), to, 1)).thenApply {
                TransferApi.commandError(it.statusCode(), it.body())
                    ?: if (TransferApi.commandAggregate(it.body()) == to) null else "chain-not-at-target"
            }
        }
    },

    /**
     * E1 with a large payload (note ⑥): an annotation of five notes of about 2 KB, five events in one commit,
     * answered once stored. V10 only.
     */
    E1_LARGE("annotation of five ~2 KB notes, five events, answered once stored") {
        override fun run(client: Client, target: Target): CompletableFuture<String?> =
            client.command(TransferApi.annotate(target.base, accountId(target.account), LARGE_NOTES))
    },

    /** E4 read by id: the account's state from the snapshots. */
    E4_STATE("state read by id from the snapshots") {
        override fun run(client: Client, target: Target): CompletableFuture<String?> =
            client.read(TransferApi.state(target.base, accountId(target.account)))
    },

    /** E4 list query: ten accounts from the snapshots. */
    E4_LIST("list query of 10 snapshots") {
        override fun run(client: Client, target: Target): CompletableFuture<String?> =
            client.read(TransferApi.list(target.base, LIST_LIMIT))
    },
    ;

    abstract fun run(client: Client, target: Target): CompletableFuture<String?>

    /** The scenario's name in the results and on the command line: `E1`, `E4-state`. */
    val label: String get() = name.replace("_STATE", "-state").replace("_LIST", "-list").replace("_LARGE", "-large")

    companion object {
        const val LIST_LIMIT = 10
        private const val NOTES = 5
        private const val NOTE_BYTES = 2_048
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** The large-payload notes as a JSON array: fixed pseudo-random text, so no store compresses it away. */
        val LARGE_NOTES: String = List(NOTES) { index ->
            val random = java.util.Random(index.toLong())
            String(CharArray(NOTE_BYTES) { ALPHABET[random.nextInt(ALPHABET.length)] })
        }.joinToString(",", "[", "]") { "\"$it\"" }

        fun of(label: String): Scenario = entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown scenario '$label': one of ${entries.map { it.label }}.")
    }
}

/** The HTTP client of a run, speaking [TransferApi]. */
class Client(val http: HttpClient) {
    fun send(request: HttpRequest): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())

    /** A command: null once it reached its wait point. */
    fun command(request: HttpRequest): CompletableFuture<String?> =
        send(request).thenApply { TransferApi.commandError(it.statusCode(), it.body()) }

    /** A read: null once answered 200. */
    fun read(request: HttpRequest): CompletableFuture<String?> =
        send(request).thenApply { if (it.statusCode() == TransferApi.OK) null else "read-http-${it.statusCode()}" }
}
