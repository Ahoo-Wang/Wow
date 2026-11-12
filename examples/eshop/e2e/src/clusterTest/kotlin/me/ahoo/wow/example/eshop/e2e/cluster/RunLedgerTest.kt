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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The rc drill's record across its test runs ([RunLedger]): what one run of the flows recorded, the next run's flows
 * start with, so its verdict judges both. No cluster: the flows are made, never driven.
 */
@Suppress("FunctionNaming") // detekt names test functions so only under src/test; this is src/clusterTest.
class RunLedgerTest {
    private fun flows(ledger: RunLedger) = ShopFlows(Shop("http://127.0.0.1:1", Tokens("not-a-secret")), ledger)

    @Test
    fun `a later run's flows start with what the earlier runs recorded`(@TempDir dir: File) {
        val ledger = RunLedger(File(dir, "state/ledger.jsonl"))
        ledger.append(RunLedger.Entry(RunLedger.RECEIVED, "mug", 50))
        ledger.append(RunLedger.Entry(RunLedger.RECEIVED, "mug", 5))
        ledger.append(RunLedger.Entry(RunLedger.CHECKOUT, "alice"))
        ledger.append(RunLedger.Entry(RunLedger.CHECKOUT, "alice"))
        ledger.append(RunLedger.Entry(RunLedger.NUMBER, "SO-261112-1"))
        ledger.append(RunLedger.Entry(RunLedger.SHIPPED, "SO-261112-1"))

        val later = flows(RunLedger(File(dir, "state/ledger.jsonl")))

        later.received.assert().isEqualTo(mapOf("mug" to 55))
        later.checkouts.getValue("alice").get().assert().isEqualTo(2)
        later.numbers.toList().assert().containsExactly("SO-261112-1")
        later.shipped.assert().containsExactly("SO-261112-1")
    }

    @Test
    fun `a line cut short by a killed run is skipped, the others kept`(@TempDir dir: File) {
        val file = File(dir, "ledger.jsonl")
        val ledger = RunLedger(file)
        ledger.append(RunLedger.Entry(RunLedger.NUMBER, "SO-261112-2"))
        file.appendText("""{"kind":"number","key":"SO-2611""")

        ledger.entries().assert().containsExactly(RunLedger.Entry(RunLedger.NUMBER, "SO-261112-2"))
    }

    @Test
    fun `no ledger yet is an empty record`(@TempDir dir: File) {
        RunLedger(File(dir, "none/ledger.jsonl")).entries().assert().isEmpty()
        flows(RunLedger(File(dir, "none/ledger.jsonl"))).received.assert().isEmpty()
    }
}
