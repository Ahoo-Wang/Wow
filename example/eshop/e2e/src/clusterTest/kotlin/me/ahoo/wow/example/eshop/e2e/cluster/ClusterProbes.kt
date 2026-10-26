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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.eshop.e2e.json
import me.ahoo.wow.example.eshop.e2e.soak.SoakDatabase
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.spi.log.Partitioner
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cluster scenarios' probes and lookups: the checkouts that measure a fault's window, the ordering's leases (who
 * owns a partition, whether every node holds some), and remote_ip's evidence on the service's side.
 */
class ClusterProbes(
    private val kube: Kube,
    private val report: ClusterReport,
    private val flows: ShopFlows,
    private val verdict: ClusterVerdict,
    private val network: PodNetwork,
    private val partitions: Int,
    /** Whether the arm is PostgreSQL, whose leases the runner reads. */
    private val postgres: Boolean,
) {
    private val probes = AtomicInteger()
    private val probeFailures = AtomicInteger()

    /**
     * The service's side of remote_ip alone, as evidence: from a gateway pod (the only pods the public port admits)
     * straight to the basket, with an X-Forwarded-For the service takes as the client's address.
     */
    fun serviceSideEvidence(body: String, tokens: Tokens) {
        val gateway = kube.pods("cosec-gateway").first { it.ready }.name
        val direct = network.http(
            gateway,
            "eshop-basket",
            80,
            "POST",
            "/eshop-basket/basket/ivy-direct/add-basket-item",
            body,
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${tokens.of(Who.buyer("ivy-direct"))}",
            "X-Forwarded-For" to "198.51.100.7",
        )
        report.note(
            "remote_ip, the service alone: $gateway -> eshop-basket with X-Forwarded-For 198.51.100.7 answered " +
                "${direct.take(3)} and recorded ${lastHeaders("ivy-direct")}",
        )
    }

    /** The headers (request facts) of the last commit of [basket] in the basket's log, read from PostgreSQL. */
    fun lastHeaders(basket: String): tools.jackson.databind.JsonNode = verdict.connect(
        "basket"
    ).use { connection ->
        val table = SoakDatabase(connection, "basket").logTables().getValue("basket")
        connection.prepareStatement(
            "SELECT headers FROM \"$table\" WHERE aggregate_id = ? ORDER BY version DESC LIMIT 1"
        )
            .use { statement ->
                statement.setString(1, basket)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        json.readTree(
                            rows.getString(1)
                        )
                    } else {
                        json.nullNode()
                    }
                }
            }
    }

    /** A checkout until its order is committed in the ordering context: whether it was. */
    fun probeToOrdering(): Boolean = probe(ShopFlows.TO_ORDERING, "10s")

    /** A checkout through the full chain, the inventory's Kafka hops included: whether its order was paid. */
    fun probeFullChain(): Boolean = probe(ShopFlows.FULL_CHAIN, "20s")

    fun probe(chain: String, timeout: String): Boolean {
        val buyer = "probe-${probes.incrementAndGet()}"
        val added = flows.addItem(buyer, "sock", "5.00", 1)
        val answer = if (added.status == ShopFlows.OK) {
            flows.checkout(buyer, mapOf(ShopFlows.WAIT to chain, ShopFlows.WAIT_TIMEOUT to timeout))
        } else {
            added
        }
        val served = answer.status == ShopFlows.OK && json.readTree(answer.body)["status"]?.asString() == "SUCCEEDED"
        // The first failures, with why, for the report: a window is only as meaningful as its probes.
        if (!served && probeFailures.incrementAndGet() <= LISTED * 2) {
            report.note("probe $buyer failed: ${answer.status} ${answer.body.take(ShopFlows.BODY)}")
        }
        return served
    }

    /** Whether every one of the ordering's [replicas] nodes holds some of its command partitions' leases. */
    fun rebalanced(replicas: Int): Boolean {
        if (!postgres) return true
        val owners = verdict.connect("ordering").use { SoakDatabase(it, "ordering").leases() }
            .filterKeys { it.startsWith("$ORDERING_GROUP|") }
            .values.filter { (_, expires) -> expires > System.currentTimeMillis() }.map { it.first }.toSet()
        return owners.size == replicas
    }

    /**
     * The node holding the ordering's command lease of [order]'s partition, once one of the current pods does: right
     * after a rolling restart, a replaced pod's lease lives until it expires.
     */
    fun partitionOwner(order: String): String {
        val partition = Partitioner.partitionOf(AggregateRef(ORDER, Caller.DEFAULT_TENANT, order), partitions)
        var owner: String? = null
        flows.eventually("a running owner of partition $partition", ShopFlows.DEADLINE.multipliedBy(2)) {
            val running = kube.pods("eshop-ordering").filter { it.ready && !it.deleting }.map { it.name }
            owner = verdict.connect("ordering").use { SoakDatabase(it, "ordering").leases() }["$ORDERING_GROUP|$partition"]
                ?.takeIf { (_, expires) -> expires > System.currentTimeMillis() }?.first
            owner in running
        }
        return checkNotNull(owner)
    }

    private companion object {
        const val LISTED = 10
        const val ORDERING_GROUP = "eshop-ordering-service/eshop-ordering"
        val ORDER = EshopOrderingContract.Order.TYPE
    }
}
