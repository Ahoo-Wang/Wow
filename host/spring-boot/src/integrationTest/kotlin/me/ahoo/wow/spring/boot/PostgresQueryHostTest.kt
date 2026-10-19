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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import javax.sql.DataSource

/**
 * A host on PostgreSQL whose snapshots are queried by the PostgreSQL query backend (6.2a), chosen by the classpath
 * as its stores are (decision D110), and whose `x-wow-index` indexes are created at startup (decision D151).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresQueryHostTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val host = startHost(
        "wow.log.partitions" to 4,
        "test.postgres.url" to postgres.jdbcUrl,
        "test.postgres.username" to postgres.username,
        "test.postgres.password" to postgres.password,
        sources = listOf(HostTestApp::class.java, PostgresDataSource::class.java, QueryOutcomes::class.java),
    )
    private val account = AggregateType(BANK, "account")

    @AfterAll
    fun close() {
        host.close()
        postgres.stop()
    }

    @Test
    fun `the declared index should be created at startup and kept by a validating start`() {
        val report = host.context.getBean(QueryIndexesLifecycle::class.java).reports.getValue(account)
        report.created.assert().containsExactly("wow_snapshot__bank__account_ix_account_owner")
        host.context.getBean(DataSource::class.java).connection.use { connection ->
            connection.prepareStatement("SELECT indexname FROM pg_indexes WHERE tablename = 'wow_snapshot__bank__account'")
                .executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }.assert().contains("wow_snapshot__bank__account_ix_account_owner")
        startHost(
            "wow.log.partitions" to 4,
            "wow.query.index.ddl" to "validate",
            "test.postgres.url" to postgres.jdbcUrl,
            "test.postgres.username" to postgres.username,
            "test.postgres.password" to postgres.password,
            sources = listOf(HostTestApp::class.java, PostgresDataSource::class.java),
        ).use { validating ->
            val validated = validating.context.getBean(QueryIndexesLifecycle::class.java).reports.getValue(account)
            validated.assert().isEqualTo(QueryIndexReport.NONE)
        }
    }

    @Test
    fun `counts should be cached on PostgreSQL until a snapshot is written`() {
        host.assertCountCache()
    }

    @Test
    fun `a state without a snapshot should be read from the log on PostgreSQL (decision D246)`() {
        val opened = host.post("/bank/account/open-account", """{"owner":"log-pat"}""")
        opened.status.assert().isEqualTo(200)
        val id = opened.body!!["ref"]["id"].asString()
        host.post("/bank/account/$id/deposit", """{"amount":4}""").status.assert().isEqualTo(200)
        host.context.getBean(DataSource::class.java).connection.use { connection ->
            connection.prepareStatement("DELETE FROM wow_snapshot__bank__account WHERE aggregate_id = ?").use {
                it.setString(1, id)
                it.executeUpdate().assert().isEqualTo(1)
            }
        }
        val state = host.get("/bank/account/$id/state")
        state.status.assert().isEqualTo(200)
        state.headers.eTag.assert().isEqualTo("\"2\"")
        state.body!!["state"]["balance"].asLong().assert().isEqualTo(4)
        host.get("/bank/account/none-$id/state").status.assert().isEqualTo(404)
    }

    @Test
    fun `the snapshots should be queried over HTTP on PostgreSQL`() {
        host.context.getBean(HttpQueries::class.java).snapshots(account).assert().isNotNull()
        val ids = listOf("pia", "ola").map { owner ->
            val opened = host.post("/bank/account/open-account", """{"owner":"$owner"}""")
            opened.status.assert().isEqualTo(200)
            opened.body!!["ref"]["id"].asString()
        }
        host.post("/bank/account/${ids[0]}/deposit", """{"amount":5}""").status.assert().isEqualTo(200)
        val listed = host.post(
            "/bank/account/snapshot/query",
            """{"kind":"list","filter":{"op":"IN","field":"aggregateId","values":${ids.map { "\"$it\"" }}},
               "sort":[{"field":"state.owner","direction":"ASC"}]}""",
        )
        listed.status.assert().isEqualTo(200)
        listed.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("ola", "pia")
        listed.body!![1]["state"]["balance"].asLong().assert().isEqualTo(5)
        listed.body!![1]["version"].asInt().assert().isEqualTo(2)
        val counted = host.post(
            "/bank/account/snapshot/query",
            """{"kind":"count","filter":{"op":"EQ","field":"state.owner","value":"pia"}}""",
        )
        counted.body!!["count"].asLong().assert().isEqualTo(1)
        val described = host.get("/bank/account/snapshot/query")
        described.status.assert().isEqualTo(200)
        described.headers.eTag.assert().isEqualTo("\"${described.body!!["version"].asString()}\"")
    }

    @Test
    fun `the events should be queried over HTTP from the log in place (decision D235)`() {
        val opened = host.post("/bank/account/open-account", """{"owner":"events-pg"}""")
        opened.status.assert().isEqualTo(200)
        val id = opened.body!!["ref"]["id"].asString()
        host.post("/bank/account/$id/deposit", """{"amount":7}""").status.assert().isEqualTo(200)
        val listed = host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"ID","value":"$id"}}""")
        listed.status.assert().isEqualTo(200)
        listed.body!!.values().map { it["version"].asInt() }.assert().containsExactly(1, 2)
        // Per-event analytics expand the events; a commit's events are counted one by one.
        val expanded = host.post(
            "/bank/account/events/query",
            """{"kind":"aggregate","filter":{"op":"ID","value":"$id"},"elements":[{"path":"events"}],""" +
                """"metrics":[{"type":"COUNT","alias":"n"}]}""",
        )
        expanded.status.assert().isEqualTo(200)
        expanded.body!![0]["n"].asLong().assert().isEqualTo(
            listed.body!!.values().sumOf { it["events"].size() }.toLong(),
        )
        val counted = host.post("/bank/account/events/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
        counted.body!!["count"].asLong().assert().isGreaterThanOrEqualTo(2)
        host.get("/bank/account/events/query").body!!["model"].asString().assert().isEqualTo("EVENTS")
    }
}
