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
import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDeploymentPhase
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiOwnedObject
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ExpectedBiQuery
import me.ahoo.wow.bi.ObservedBiObject
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan

internal enum class CatalogMutationMode {
    RECONCILE,
    CREATE_ONLY,
}

internal data class ClickHouseStreamRenderPlan(
    val storage: List<String>,
    val ingress: List<String>,
    val publicViews: List<String>,
)

internal data class ClickHouseAggregateRenderPlan(
    val aggregate: String,
    val command: ClickHouseStreamRenderPlan,
    val state: ClickHouseStreamRenderPlan,
    val stateLast: List<String>,
    val expansion: List<String>,
)

/**
 * Composes the focused renderers that produce the current BI SQL graph.
 */
@Suppress("TooManyFunctions")
internal class ClickHouseScriptRenderer(
    options: BiScriptOptions = BiScriptOptions(consumerGroupNamespace = "test"),
    consumerIdentity: BiConsumerIdentity =
        BiConsumerIdentity.deterministic(BiDeploymentDescriptor.from(options)),
    deployment: BiDeploymentDescriptor = BiDeploymentDescriptor.from(options),
    catalogMutationMode: CatalogMutationMode = CatalogMutationMode.RECONCILE,
    retainedQueueKeys: Set<BiObjectKey> = emptySet(),
) {
    private val context = ClickHouseRenderContext(
        options = options,
        consumerIdentity = consumerIdentity,
        deployment = deployment,
        catalogMutationMode = catalogMutationMode,
        retainedQueueKeys = retainedQueueKeys,
    )
    private val lifecycle = ClickHouseLifecycleRenderer(context)
    private val command = ClickHouseCommandRenderer(context)
    private val stateEvent = ClickHouseStateEventRenderer(context)
    private val stateLast = ClickHouseStateLastRenderer(context)
    private val expansion = ClickHouseExpansionRenderer(context)

    fun renderGlobalStatements(): List<String> = lifecycle.renderGlobal()

    fun renderDropObservedStatements(objects: List<ObservedBiObject>): List<String> =
        lifecycle.renderDropObserved(objects)

    fun renderDropOwnedStatements(objects: List<BiOwnedObject>): List<String> =
        lifecycle.renderDropOwned(objects)

    fun renderAnchorStatement(
        phase: BiDeploymentPhase,
        registryRevision: Long? = null,
    ): String = lifecycle.renderAnchor(phase, registryRevision)

    fun renderCommandStorageStatements(namedAggregate: NamedAggregate): List<String> =
        command.render(namedAggregate).storage

    fun renderCommandPublicStatements(namedAggregate: NamedAggregate): List<String> =
        command.render(namedAggregate).publicViews

    fun renderCommandIngressStatements(namedAggregate: NamedAggregate): List<String> =
        command.render(namedAggregate).ingress

    fun renderStateStorageStatements(namedAggregate: NamedAggregate): List<String> =
        stateEvent.render(namedAggregate).storage

    fun renderStatePublicStatements(namedAggregate: NamedAggregate): List<String> =
        stateEvent.render(namedAggregate).publicViews

    fun renderStateIngressStatements(namedAggregate: NamedAggregate): List<String> =
        stateEvent.render(namedAggregate).ingress

    fun renderPauseIngressStatements(namedAggregate: NamedAggregate): List<String> =
        lifecycle.renderPauseIngress(namedAggregate)

    fun renderStateLastStatements(namedAggregate: NamedAggregate): List<String> = stateLast.render(namedAggregate)

    fun renderExpansionStatements(plan: StateExpansionPlan, aggregate: String = "test.aggregate"): List<String> =
        expansion.render(plan, aggregate)

    fun renderAggregate(
        namedAggregate: NamedAggregate,
        plan: StateExpansionPlan,
        aggregate: String,
    ): ClickHouseAggregateRenderPlan = ClickHouseAggregateRenderPlan(
        aggregate = aggregate,
        command = command.render(namedAggregate),
        state = stateEvent.render(namedAggregate),
        stateLast = stateLast.render(namedAggregate),
        expansion = expansion.render(plan, aggregate),
    )

    fun expectedComputedQueries(
        namedAggregate: NamedAggregate,
        plan: StateExpansionPlan,
    ): Map<BiObjectKey, ExpectedBiQuery> = buildMap {
        putAll(command.expectedQueries(namedAggregate))
        putAll(stateEvent.expectedQueries(namedAggregate))
        putAll(stateLast.expectedQueries(namedAggregate))
        putAll(expansion.expectedQueries(plan))
    }

    companion object {
        const val DEPLOYMENT_ANCHOR = "__wow_bi_deployment"
        const val COMMAND_SUFFIX = "command"
        const val STATE_SUFFIX = "state"
        const val STATE_LAST_SUFFIX = "state_last"
        const val STATE_COLUMN = "state"
        const val STATE_TARGET = "__state"
        const val PATH_TARGET = "__path"
        const val INDEX_TARGET = "__index"
        const val SOURCE_ALIAS = "__source"
    }
}
