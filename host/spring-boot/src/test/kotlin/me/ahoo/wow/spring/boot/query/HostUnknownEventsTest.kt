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

package me.ahoo.wow.spring.boot.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spring.boot.BANK
import me.ahoo.wow.spring.boot.Host
import me.ahoo.wow.spring.boot.HostQueryFixtures.ACCOUNT
import me.ahoo.wow.spring.boot.HostQueryFixtures.openAccount
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test

/**
 * What the node finds no schema for is never answered raw (decision D485 ⑩), on the host as it is wired: an event a
 * newer node wrote during a rolling upgrade, under a name this node's Manifest does not know or at a newer revision of
 * one it knows, and a schedule's command of a type no Manifest names, are masked whole (`null`) by the `events` query;
 * the state's history, which replays the events, refuses rather than answer them. Through 10.0.0-beta.21 the events
 * query returned them as stored.
 */
class HostUnknownEventsTest {
    /** Appends, after [id]'s opening, the commit a newer node of the service wrote. */
    private fun Host.newerCommit(id: String) = runBlocking {
        val log = node.context(BANK).backends.log
        val opened = log.read(ACCOUNT.ref(Caller.DEFAULT_TENANT, id)).toList().single()
        val newer = opened.copyWith(
            version = 2,
            commandId = "newer-2",
            requestId = "newer-2",
            events = listOf(
                EventRecord("bank.account.Frozen", 1, """{"reason":"$SECRET"}"""),
                EventRecord("bank.account.Deposited", 9, """{"amount":5,"card":"$SECRET"}"""),
                EventRecord(
                    Schedules.SCHEDULE_SET,
                    1,
                    """{"key":"k","commandType":"bank.account.Notify","command":{"phone":"$SECRET"}}""",
                ),
            ),
        )
        log.append(newer).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `the events query masks what this node's Manifest does not know whole, and answers the rest`() {
        startHost().use { host ->
            val id = host.openAccount("mona")
            host.newerCommit(id)
            val answer = host.post(
                "/bank/account/events/query",
                """{"kind":"list","filter":{"op":"id","value":"$id"}}""",
            )
            answer.status.assert().isEqualTo(200)
            answer.body.toString().assert().doesNotContain(SECRET)
            val events = answer.body!![1]["events"]
            events.values().map { it["name"].asString() }.assert()
                .containsExactly("bank.account.Frozen", "bank.account.Deposited", Schedules.SCHEDULE_SET)
            events[0]["body"].isNull.assert().isTrue()
            events[1]["revision"].asInt().assert().isEqualTo(9)
            events[1]["body"].isNull.assert().isTrue()
            events[2]["body"]["key"].asString().assert().isEqualTo("k")
            events[2]["body"]["command"].isNull.assert().isTrue()
            // The event this node knows, at its revision, is answered as it is.
            answer.body!![0]["events"][0]["body"]["owner"].asString().assert().isEqualTo("mona")
        }
    }

    @Test
    fun `the state's history refuses a commit it cannot replay rather than answer it raw`() {
        startHost().use { host ->
            val id = host.openAccount("mona")
            host.newerCommit(id)
            val trace = host.get("/bank/account/$id/state/trace")
            trace.status.assert().isNotEqualTo(200)
            trace.body.toString().assert().doesNotContain(SECRET)
        }
    }

    private companion object {
        const val SECRET = "s3cr3t-6222021234567890"
    }
}
