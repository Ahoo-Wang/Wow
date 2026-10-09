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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiDeploymentPhase
import me.ahoo.wow.bi.BiDurableEntry
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiOwnedObject
import me.ahoo.wow.bi.layout.BiLayout

internal class ClickHouseLifecycleRenderer(private val context: ClickHouseRenderContext) {
    fun renderGlobal(): List<String> = with(context) {
        immutableStatements(
            "CREATE DATABASE IF NOT EXISTS ${identifier(options.database)}${scopeClause()};",
            "CREATE DATABASE IF NOT EXISTS ${identifier(options.consumerDatabase)}${scopeClause()};",
        )
    }

    fun renderDropOwned(objects: List<BiOwnedObject>): List<String> = with(context) {
        immutableStatements(
            objects.sortedWith(
                compareBy<BiOwnedObject> { it.kind == BiObjectKind.STORE }
                    .thenBy { it.kind == BiObjectKind.STORE && BiLayout.isLocalStore(it.key.name) }
                    .thenByDescending { it.key.name.length }
                    .thenBy { it.key.database }
                    .thenBy { it.key.name }
            ).map { owned ->
                when (owned.kind) {
                    BiObjectKind.ANCHOR, BiObjectKind.VIEW, BiObjectKind.CONSUMER ->
                        dropView(owned.key.database, owned.key.name)
                    BiObjectKind.STORE, BiObjectKind.QUEUE -> drop(owned.key.database, owned.key.name)
                }
            }
        )
    }

    fun renderAnchor(phase: BiDeploymentPhase, durableInventory: List<BiDurableEntry>): String = with(context) {
        val comment = anchorComment(phase, durableInventory)
        "$viewCreateClause ${qualified(layout.anchor.database, layout.anchor.name)}" +
            "${scopeClause()} AS (SELECT 1 AS ${identifier("alive")} WHERE 0) COMMENT $comment;"
    }

    fun renderPauseIngress(namedAggregate: NamedAggregate): List<String> = with(context) {
        val aggregateLayout = layout.of(namedAggregate)
        immutableStatements(
            listOf(aggregateLayout.command.consumer, aggregateLayout.state.consumer)
                .filter { consumerTable -> replaces(layout.ingressKey(consumerTable)) }
                .map { consumerTable -> dropView(options.consumerDatabase, consumerTable) }
        )
    }
}
