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

import me.ahoo.wow.example.eshop.e2e.json
import java.io.File

/**
 * What a run did, kept across the test's runs (the rc drill's: test/rc-drill/lib/verdict.sh runs the suite once per
 * step, between operations it makes from outside): the stock each product received, each checkout, the numbers the
 * checkouts' answers named and the orders shipped. It is the one place a verdict reads the drill from: the flows
 * ([ShopFlows]) are made from it and add to it, and [ClusterVerdict] judges the stores against the flows. One JSON
 * object per line in [file], appended as the run records it, read back whole when a later run starts.
 *
 * What a later run may assume of it:
 * - **Order.** The entries are in the order they were recorded. One run's appends are serialized, and the drill's runs
 *   come one after another, never two at once, so the file is never appended by two processes.
 * - **Whole lines.** Every line is one whole entry, except possibly the last line of a run that was killed while it
 *   wrote. That line is cut short, and it is skipped when read.
 * - **Completeness.** An entry is appended after the action it records succeeded: the stock received, the checkout's
 *   answer read. A run killed between the two loses that one entry. The stores are then ahead of the record, and the
 *   verdict reports it as a violation of the invariant that entry fed (`stock.conserved`, `orders.numbered`). The
 *   killed run's own report and log explain it. Nothing is ever recorded that did not happen.
 */
class RunLedger(private val file: File) {
    /** One record: its [kind] and its [key], with a [quantity] for a product's received stock. */
    data class Entry(val kind: String, val key: String, val quantity: Int = 0)

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun append(entry: Entry) {
        val line = json.writeValueAsString(
            mapOf("kind" to entry.kind, "key" to entry.key, "quantity" to entry.quantity),
        )
        file.appendText(line + "\n")
    }

    /** Every whole record so far, in the order they were appended. */
    @Synchronized
    fun entries(): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val node = json.readTree(line)
                Entry(node["kind"].asString(), node["key"].asString(), node["quantity"]?.asInt() ?: 0)
            }.getOrNull()
        }
    }

    companion object {
        const val RECEIVED = "received"
        const val CHECKOUT = "checkout"
        const val NUMBER = "number"
        const val SHIPPED = "shipped"

        /** The ledger in `ga.state`, when the run keeps one; null for a run in one test run (the GA end-to-end's). */
        fun fromSystem(): RunLedger? = System.getProperty("ga.state")?.takeIf { it.isNotBlank() }?.let {
            RunLedger(File(it, "ledger.jsonl"))
        }
    }
}
