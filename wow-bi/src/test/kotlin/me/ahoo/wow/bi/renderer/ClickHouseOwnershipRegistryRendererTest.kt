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

package me.ahoo.wow.bi.renderer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiOwnershipRegistration
import me.ahoo.wow.bi.BiOwnershipRegistry
import me.ahoo.wow.bi.BiRegistryEntryStatus
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import org.junit.jupiter.api.Test

class ClickHouseOwnershipRegistryRendererTest {
    @Test
    fun `should render a standalone replacing registry and an immutable revision snapshot`() {
        val registry = activeRegistry()
        val renderer = ClickHouseOwnershipRegistryRenderer(
            BiScriptOptions(
                consumerGroupNamespace = "orders",
                topology = ClickHouseTopology.Standalone,
            ),
            registry.deploymentId,
        )

        val createStatements = renderer.renderCreateStatements(registry.name)
        createStatements.size.assert().isEqualTo(1)
        createStatements.single().assert().contains(
            "CREATE TABLE IF NOT EXISTS \"bi_db_consumer\".\"${registry.name}\"",
            "ENGINE = ReplacingMergeTree(\"revision\")",
            "ORDER BY (\"deployment_id\", \"row_kind\",",
            "\"object_database\",",
            "\"object_name\")",
            "wow-bi-registry:${registry.deploymentId}",
        )
        renderer.renderSnapshotStatement(registry).assert().contains(
            "INSERT INTO \"bi_db_consumer\".\"${registry.name}\"",
            "'wow_db', 'orders_order_state_store'",
            "'STORE', 'orders.order'",
            "'ACTIVE'",
            "'HEAD'",
            "'OBJECT'",
        )
    }

    @Test
    fun `should render replicated local registry and distributed facade for a cluster`() {
        val registry = activeRegistry()
        val renderer = ClickHouseOwnershipRegistryRenderer(
            BiScriptOptions(
                consumerGroupNamespace = "orders",
                topology = ClickHouseTopology.Cluster(name = "analytics", installation = "prod"),
            ),
            registry.deploymentId,
        )

        renderer.renderCreateStatements(registry.name).size.assert().isEqualTo(1)
        renderer.renderCreateStatements(registry.name).joinToString("\n").assert().contains(
            "\"${registry.name}\" ON CLUSTER 'analytics'",
            "ReplicatedReplacingMergeTree",
            "/clickhouse/prod/analytics/control/wow-bi/${registry.deploymentId}",
            "{shard}-{replica}",
        ).doesNotContain(
            "${registry.name}_local",
            "ENGINE = Distributed",
        )
    }

    @Test
    fun `should persist tombstones instead of deleting registry history`() {
        val active = activeRegistry()
        val tombstoned = active.beginDrop(active.entries.single().key)
            .markAbsenceVerified(active.entries.single().key)
        val statement = ClickHouseOwnershipRegistryRenderer(
            BiScriptOptions(
                consumerGroupNamespace = "orders",
                topology = ClickHouseTopology.Standalone,
            ),
            active.deploymentId,
        ).renderSnapshotStatement(tombstoned)

        statement.assert().contains(
            "'${BiRegistryEntryStatus.TOMBSTONE}'",
            ", ${tombstoned.revision}, '${BiRegistryEntryStatus.TOMBSTONE}'",
        )
    }

    private fun activeRegistry(): BiOwnershipRegistry {
        val key = BiObjectKey("wow_db", "orders_order_state_store")
        return BiOwnershipRegistry.empty("a".repeat(32))
            .beginCreate(
                BiOwnershipRegistration(
                    key = key,
                    kind = BiObjectKind.STORE,
                    aggregate = "orders.order",
                    consumerIdentity = "b".repeat(32),
                    definitionFingerprint = "c".repeat(32),
                )
            )
            .markMutationVerified(key)
    }
}
