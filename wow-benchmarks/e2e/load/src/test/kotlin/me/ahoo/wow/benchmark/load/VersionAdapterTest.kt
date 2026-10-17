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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.net.URI

/** The wait mapping of each scenario, on the wire (wow-benchmarks/e2e/README.md). */
class VersionAdapterTest {
    private val base = URI.create("http://127.0.0.1:8080")

    @Test
    fun `9x should wait for PROCESSED, SNAPSHOT and the saga's tail`() {
        val stored = VersionAdapter.NineX.deposit(base, "acc-1", 1, Wait.STORED)
        stored.uri().path.assert().isEqualTo("/account/acc-1/deposit")
        stored.headers().firstValue("Command-Wait-Stage").get().assert().isEqualTo("PROCESSED")
        VersionAdapter.NineX.deposit(base, "acc-1", 1, Wait.SNAPSHOT).headers().firstValue("Command-Wait-Stage").get()
            .assert().isEqualTo("SNAPSHOT")
        val transfer = VersionAdapter.NineX.prepare(base, "acc-1", "acc-2", 1).headers()
        transfer.firstValue("Command-Wait-Stage").get().assert().isEqualTo("SAGA_HANDLED")
        transfer.firstValue("Command-Wait-Processor").get().assert().isEqualTo("TransferSaga")
        transfer.firstValue("Command-Wait-Function").get().assert().isEqualTo("onPrepared")
        transfer.firstValue("Command-Wait-Tail-Stage").get().assert().isEqualTo("PROCESSED")
        VersionAdapter.NineX.state(base, "acc-1").uri().path.assert().isEqualTo("/account/acc-1/snapshot")
    }

    @Test
    fun `V10 should wait for committed, snapshot and the chain to the target's commit`() {
        val stored = VersionAdapter.V10.deposit(base, "acc-1", 1, Wait.STORED)
        stored.uri().path.assert().isEqualTo("/transfer/account/acc-1/deposit")
        stored.headers().firstValue("Wow-Wait").get().assert().isEqualTo("committed")
        VersionAdapter.V10.deposit(base, "acc-1", 1, Wait.SNAPSHOT).headers().firstValue("Wow-Wait").get()
            .assert().isEqualTo("snapshot")
        VersionAdapter.V10.prepare(base, "acc-1", "acc-2", 1).headers().firstValue("Wow-Wait").get()
            .assert().isEqualTo("committed > handled(TransferSaga.onPrepared) > committed")
        VersionAdapter.V10.state(base, "acc-1").uri().path.assert().isEqualTo("/transfer/account/acc-1/state")
    }

    @Test
    fun `answers should be read as each version writes them`() {
        val nineX = """{"stage":"PROCESSED","aggregateId":"acc-2","aggregateVersion":3,"errorCode":"Ok"}"""
        VersionAdapter.NineX.commandError(200, nineX).assert().isNull()
        VersionAdapter.NineX.commandVersion(nineX).assert().isEqualTo(3)
        VersionAdapter.NineX.commandAggregate(nineX).assert().isEqualTo("acc-2")
        VersionAdapter.NineX.commandError(200, """{"errorCode":"CommandWaitTimeout"}""").assert()
            .isEqualTo("error-CommandWaitTimeout")
        val v10 = """{"commandId":"c","ref":{"tenantId":"(0)","id":"acc-2"},"status":"SUCCEEDED","version":3}"""
        VersionAdapter.V10.commandError(200, v10).assert().isNull()
        VersionAdapter.V10.commandVersion(v10).assert().isEqualTo(3)
        VersionAdapter.V10.commandAggregate(v10).assert().isEqualTo("acc-2")
        VersionAdapter.V10.commandError(
            409,
            """{"code":"wow.command.busy"}"""
        ).assert().isEqualTo("http-409-wow.command.busy")
    }

    @Test
    fun `results should be written as JSON`() {
        Json.write(mapOf("a" to listOf(1, 2), "b" to "x\"y", "c" to null)).assert()
            .isEqualTo("{\n  \"a\": [1, 2],\n  \"b\": \"x\\\"y\",\n  \"c\": null\n}")
    }
}
