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
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiOwnedObject
import me.ahoo.wow.bi.ObservedBiObject

internal class ClickHouseLifecycleRenderer(private val context: ClickHouseRenderContext) {
    fun renderGlobal(): List<String> = with(context) {
        immutableStatements(
            "CREATE DATABASE IF NOT EXISTS ${identifier(options.database)}${scopeClause()};",
            "CREATE DATABASE IF NOT EXISTS ${identifier(options.consumerDatabase)}${scopeClause()};",
        )
    }

    fun renderDropObserved(objects: List<ObservedBiObject>): List<String> =
        renderDropOwned(
            objects.map { observed ->
                val kind = checkNotNull(observed.metadata?.kind) {
                    "Cannot drop an unowned BI catalog object: ${observed.database}.${observed.name}"
                }
                BiOwnedObject(observed.key, kind)
            }
        )

    fun renderDropOwned(objects: List<BiOwnedObject>): List<String> = with(context) {
        immutableStatements(
            objects.sortedWith(
                compareBy<BiOwnedObject> { it.kind == BiObjectKind.STORE }
                    .thenBy { it.kind == BiObjectKind.STORE && it.key.name.endsWith("_local") }
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

    fun renderAnchor(phase: BiDeploymentPhase, registryRevision: Long? = null): String = with(context) {
        val comment = metadataComment(BiObjectKind.ANCHOR, null, phase, registryRevision)
        "$viewCreateClause ${qualified(options.consumerDatabase, ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR)}" +
            "${scopeClause()} AS (SELECT 1 AS ${identifier("alive")} WHERE 0) COMMENT $comment;"
    }

    fun renderPauseIngress(namedAggregate: NamedAggregate): List<String> = with(context) {
        val commandTable = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.COMMAND_SUFFIX)
        val stateTable = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        immutableStatements(
            dropView(options.consumerDatabase, "${commandTable}_consumer"),
            dropView(options.consumerDatabase, "${stateTable}_consumer"),
        )
    }
}
