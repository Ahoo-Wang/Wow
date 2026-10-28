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

import me.ahoo.wow.example.eshop.e2e.Answer
import me.ahoo.wow.example.eshop.e2e.json
import java.time.Duration

/**
 * The catalog's products on Elasticsearch beside MongoDB, on the mongo arm (the catalog's `search` profile): the
 * products' snapshots go to MongoDB, the store their commands load from and their state is read from, and to
 * Elasticsearch, which serves their snapshot and `events` queries (decision D329).
 *
 * - [served]: the products' snapshot and `events` queries answer from the Elasticsearch indices, which hold them.
 * - [outage]: with Elasticsearch scaled to zero, the products' commands and their states keep answering from MongoDB
 *   (the window of a price change and its state read, bounded by zero failures), while their snapshot and `events`
 *   queries do not. Once Elasticsearch is back, both find a product created during the outage: the snapshot copy and
 *   the events sink caught up from their checkpoints.
 */
class SearchStore(
    private val kube: Kube,
    private val network: PodNetwork,
    private val flows: ShopFlows,
    private val report: ClusterReport,
) {
    private val shop = flows.shop
    private var prices = 0

    fun served(): String {
        val products = snapshots(MATCH_ALL)
        check(
            products.status == ShopFlows.OK
        ) { "the product search answered ${products.status}: ${products.body.take(BODY)}" }
        val listed = json.readTree(products.body).values().map { it["aggregateId"].asString() }.toSet()
        check(
            listed.containsAll(Deployment.PRODUCTS.keys)
        ) { "the product search lists $listed, not ${Deployment.PRODUCTS.keys}" }
        val events = events("mug")
        check(events.status == ShopFlows.OK && json.readTree(events.body).size() > 0) {
            "the product events search answered ${events.status}: ${events.body.take(BODY)}"
        }
        val indices = indices()
        check(indices.getOrDefault(SNAPSHOT_INDEX, 0) >= Deployment.PRODUCTS.size) { "Elasticsearch holds $indices" }
        check(indices.getOrDefault(EVENTS_INDEX, 0) > 0) { "Elasticsearch holds $indices" }
        return "${listed.size} products found by their search, their events too, from Elasticsearch: $indices"
    }

    fun outage(): String {
        flows.product(PROBE, "Search probe", "3.00")
        var downSnapshots = 0
        var downEvents = 0
        val window = Outages.measure(
            report,
            "scale Elasticsearch to zero and back",
            OUTAGE_BOUND,
            "a product's price change and its state",
            ::probe,
            settled = { found(snapshots(byId(DURING)), DURING) && found(events(DURING), DURING) },
            settleTimeout = CATCH_UP,
        ) {
            kube.require("scale", "statefulset/elasticsearch", "--replicas=0")
            flows.eventually("Elasticsearch gone", DOWN) { kube.pods("elasticsearch").isEmpty() }
            // Commands and states go on: a product created and published while the search is down.
            flows.product(DURING, "Made during the search outage", "4.00")
            check(flows.state("/eshop-catalog/product/$DURING/state")?.get("status")?.asString() == "PUBLISHED") {
                "$DURING's state is not served from MongoDB while Elasticsearch is down"
            }
            downSnapshots = snapshots(MATCH_ALL).status
            downEvents = events(PROBE).status
            kube.require("scale", "statefulset/elasticsearch", "--replicas=1")
            check(kube.awaitRollout("statefulset/elasticsearch", DOWN)) { "Elasticsearch did not come back" }
        }
        check(downSnapshots != ShopFlows.OK) { "the product search answered $downSnapshots with Elasticsearch down" }
        check(downEvents != ShopFlows.OK) { "the product events search answered $downEvents with Elasticsearch down" }
        check(found(snapshots(byId(DURING)), DURING)) { "$DURING is not found by the product search after the outage" }
        return "commands and states served throughout (${window.failures} of ${window.probes} probes failed); " +
            "with Elasticsearch down the product search answered $downSnapshots, its events search $downEvents; " +
            "$DURING, made during the outage, found by both once it was back"
    }

    /** A price change of the probe product, and its state read back from MongoDB with that price. */
    private fun probe(): Boolean {
        val price = "${PROBE_BASE + prices++ % PRICES}.00"
        val changed = shop.post(
            "/eshop-catalog/product/$PROBE/change-product-price",
            """{"price":$price}""",
            Who.EMPLOYEE
        )
        if (changed.status != ShopFlows.OK) return false
        val state = flows.state("/eshop-catalog/product/$PROBE/state") ?: return false
        return state["price"].decimalValue().compareTo(price.toBigDecimal()) == 0
    }

    private fun snapshots(filter: String): Answer = shop.post(
        "/eshop-catalog/product/snapshot/query",
        """{"kind":"list","filter":$filter,"limit":100}""",
        Who.EMPLOYEE,
    )

    private fun events(id: String): Answer = shop.post(
        "/eshop-catalog/product/events/query",
        """{"kind":"list","filter":${byId(id)},"limit":100}""",
        Who.EMPLOYEE,
    )

    private fun found(answer: Answer, id: String): Boolean = answer.status == ShopFlows.OK &&
        json.readTree(answer.body).values().any { it["aggregateId"]?.asString() == id }

    /** The documents of each index Elasticsearch holds, as the shop's probe pod reads them. */
    private fun indices(): Map<String, Int> =
        network.http("insider", "elasticsearch", ELASTICSEARCH, "GET", "/_cat/indices?h=index,docs.count")
            .lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 2 && it[1].all(Char::isDigit) }
            .associate { (index, count) -> index to count.toInt() }

    private fun byId(id: String): String = """{"op":"aggregate-id","value":"$id"}"""

    private companion object {
        const val ELASTICSEARCH = 9200
        const val PROBE = "search-probe"
        const val DURING = "search-outage-1"
        const val MATCH_ALL = """{"op":"match-all"}"""
        const val SNAPSHOT_INDEX = "eshop_wow_snapshot__eshop_catalog__product"
        const val EVENTS_INDEX = "eshop_wow_events__eshop_catalog__product"
        const val PROBE_BASE = 3
        const val PRICES = 5
        const val BODY = 600

        /** Commands and states must never wait on the search: no probe may fail. */
        val OUTAGE_BOUND: Duration = Duration.ZERO
        val DOWN: Duration = Duration.ofMinutes(3)
        val CATCH_UP: Duration = Duration.ofMinutes(5)
    }
}
