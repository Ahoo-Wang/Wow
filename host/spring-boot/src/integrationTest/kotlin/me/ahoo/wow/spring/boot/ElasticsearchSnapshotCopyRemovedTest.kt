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


package me.ahoo.wow.spring.boot

import co.elastic.clients.transport.rest5_client.low_level.Request
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A copy of an aggregate type's snapshots on Elasticsearch (`query.snapshot.store`), beside the log's own, whose index
 * is deleted under the store while its table's record in `wow_snapshot_table` is kept (#7251): the table was removed,
 * and holds nothing the log committed before. It is never served empty: its queries are refused by name
 * (`wow.query.dataset-unavailable`), nothing is written to it, and the type's commands still run.
 */
class ElasticsearchSnapshotCopyRemovedTest {
    private val prefix = "rmvd" + UUID.randomUUID().toString().take(SUFFIX)

    @Test
    fun `a snapshot copy removed under its record should be refused by name after a restart, never served empty`() {
        val ids = writeAndRemove()
        startHost(*properties(), sources = sources()).use { host ->
            host.refused()
            // Its loads read the log: the type's commands still run, and write nothing to the copy.
            host.post("/bank/account/${ids.first()}/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
            host.refused()
            host.indexExists().assert().isFalse()
        }
    }

    @Test
    fun `a rebuild request should write nothing to a snapshot copy removed under its record`() {
        val ids = writeAndRemove()
        startHost(*properties(), sources = sources()).use { host ->
            val commands = host.context.getBean(CommandClient::class.java).of(Caller.ofService("host-test"))
            // To the context's snapshot rebuild aggregate, by the aggregate type's name (Rebuild and Replay).
            val rebuild = CommandRequest.of(RebuildSnapshot.of(listOf(ids.first())), "account", REBUILDS)
            runBlocking { commands.wait(rebuild) }
            host.refused()
            host.indexExists().assert().isFalse()
        }
    }

    @Test
    fun `a snapshot copy removed under a running node should be refused once the write that misses it finds it`() {
        startHost(*properties(), sources = sources()).use { host ->
            val id = host.open()
            host.eventually { host.query().status == OK && host.listed() == listOf(id) }
            host.removeIndex()
            // The copy's subscription misses the index with the next commit's snapshot, and the round after reads it.
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
            host.eventually { host.query().status == CONFLICT }
            host.refused()
            host.indexExists().assert().isFalse()
        }
    }

    /** Opens [ACCOUNTS] accounts, waits until the copy lists them, then deletes the copy's index; their ids. */
    private fun writeAndRemove(): List<String> = startHost(*properties(), sources = sources()).use { host ->
        val ids = (1..ACCOUNTS).map { host.open() }
        host.eventually { host.query().status == OK && host.listed().toSet() == ids.toSet() }
        host.removeIndex()
        ids
    }

    private fun Host.open(): String {
        val opened = post("/bank/account/open-account", """{"owner":"$prefix"}""")
        opened.status.assert().isEqualTo(OK)
        return opened.body!!["ref"]["id"].asString()
    }

    /** As Operate › Elasticsearch empties an index: the concrete index, which Elasticsearch deletes through no alias. */
    private fun Host.removeIndex() {
        context.getBean(Rest5Client::class.java).performRequest(Request("DELETE", "/$INDEX".format(prefix)))
            .statusCode.assert().isEqualTo(OK)
    }

    private fun Host.indexExists(): Boolean =
        context.getBean(Rest5Client::class.java).performRequest(Request("HEAD", "/$INDEX".format(prefix)))
            .statusCode == OK

    private fun Host.query(): Answer = post(
        "/bank/account/snapshot/query",
        """{"kind":"list","filter":{"op":"eq","field":"state.owner","value":"$prefix"}}""",
    )

    private fun Host.listed(): List<String> = query().body?.values()?.map { it["aggregateId"].asString() }.orEmpty()

    /** The copy's snapshot query is refused, naming why, and lists nothing. */
    private fun Host.refused() {
        val answer = query()
        answer.status.assert().describedAs(answer.body.toString()).isEqualTo(CONFLICT)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.QUERY_DATASET_UNAVAILABLE)
        answer.body!!["detail"].asString().assert().contains("removed from their store")
    }

    private fun Host.eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun properties(): Array<Pair<String, Any>> = arrayOf(
        "wow.log.partitions" to 2,
        "wow.snapshot.maintenance-interval" to "1s",
        "wow.stores.mongo.type" to "mongo",
        "wow.stores.mongo.database" to "removed_$prefix",
        "test.mongo.url" to WowContainers.mongoUrl,
        "wow.log.store" to "mongo",
        "wow.stores.es.type" to "elasticsearch",
        "wow.stores.es.index-prefix" to prefix,
        "wow.aggregates.[$BANK.account].query.snapshot.store" to "es",
    )

    private fun sources(): List<Class<*>> = listOf(
        HostTestApp::class.java,
        ElasticsearchOwnSnapshotsTest.ElasticsearchClientConfiguration::class.java,
        MongoClientConfiguration::class.java,
    )

    private companion object {
        val REBUILDS = AggregateType(BANK, "snapshot")
        const val INDEX = "%s_wow_snapshot__bank__account--1"
        const val OK = 200
        const val CONFLICT = 409
        const val ACCOUNTS = 2
        const val SUFFIX = 8
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
