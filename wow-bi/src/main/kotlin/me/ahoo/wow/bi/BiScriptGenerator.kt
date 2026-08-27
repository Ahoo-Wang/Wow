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

package me.ahoo.wow.bi

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import java.util.Collections

/**
 * Opaque immutable expansion plan for one BI script request.
 *
 * Create it with [BiScriptGenerator.prepare], pass it to [BiDeploymentInspector.inspect], and render it with
 * [BiScriptGenerator.generate]. The same generator options must be used throughout the request.
 */
class BiScriptPreparation private constructor(
    internal val options: BiScriptOptions,
    namedAggregates: Set<NamedAggregate>,
    plannedAggregates: List<PlannedAggregate>,
    desiredObjects: List<DesiredBiObject>,
) {
    internal val namedAggregates: Set<NamedAggregate> =
        Collections.unmodifiableSet(LinkedHashSet(namedAggregates))
    internal val plannedAggregates: List<PlannedAggregate> =
        Collections.unmodifiableList(ArrayList(plannedAggregates))
    internal val desiredObjects: List<DesiredBiObject> =
        Collections.unmodifiableList(ArrayList(desiredObjects))
    internal val desiredObjectKeys: Set<BiObjectKey> = Collections.unmodifiableSet(
        desiredObjects.mapTo(linkedSetOf(), DesiredBiObject::key)
    )

    internal companion object {
        fun create(
            options: BiScriptOptions,
            namedAggregates: Set<NamedAggregate>,
            plannedAggregates: List<PlannedAggregate>,
            desiredObjects: List<DesiredBiObject>,
        ): BiScriptPreparation {
            return BiScriptPreparation(options, namedAggregates, plannedAggregates, desiredObjects)
        }
    }
}

internal data class PlannedAggregate(
    val namedAggregate: NamedAggregate,
    val plan: StateExpansionPlan,
)

internal data class DesiredBiObject(
    val key: BiObjectKey,
    val aggregate: String?,
    val kind: BiObjectKind,
    val expectedEngine: String,
    val expectedQuery: ExpectedBiQuery? = null,
)

class BiScriptGenerator(private val options: BiScriptOptions = BiScriptOptions()) {
    private val preparationPlanner = BiPreparationPlanner(options)
    private val assembler = BiScriptAssembler(options)

    fun generate(
        namedAggregates: Set<NamedAggregate>,
        operation: BiScriptOperation = BiScriptOperation.Deploy,
        inspection: BiDeploymentInspection = BiDeploymentInspection.Unavailable,
    ): BiScriptResult {
        validateGenerationRequest(namedAggregates, operation, inspection)
        return assembler.assemble(preparationPlanner.plan(namedAggregates), operation, inspection)
    }

    fun generate(
        preparation: BiScriptPreparation,
        operation: BiScriptOperation = BiScriptOperation.Deploy,
        inspection: BiDeploymentInspection = BiDeploymentInspection.Unavailable,
    ): BiScriptResult {
        require(preparation.options == options) {
            "BI script preparation was prepared with different BI script options"
        }
        validateGenerationRequest(preparation.namedAggregates, operation, inspection)
        return assembler.assemble(preparation, operation, inspection)
    }

    /**
     * Builds an immutable, request-scoped expansion plan that can be shared by catalog inspection and rendering.
     */
    fun prepare(namedAggregates: Set<NamedAggregate>): BiScriptPreparation {
        validateConsumerGroupNamespace(namedAggregates)
        return preparationPlanner.plan(namedAggregates)
    }

    private fun validateGenerationRequest(
        namedAggregates: Set<NamedAggregate>,
        operation: BiScriptOperation,
        inspection: BiDeploymentInspection,
    ) {
        validateConsumerGroupNamespace(namedAggregates)
        require(operation !is BiScriptOperation.Reset || inspection is BiDeploymentInspection.Available) {
            "RESET requires an available BI deployment inspection"
        }
    }

    private fun validateConsumerGroupNamespace(namedAggregates: Set<NamedAggregate>) {
        if (namedAggregates.isNotEmpty()) {
            requireNotNull(options.consumerGroupNamespace) {
                "consumerGroupNamespace must be configured before generating BI Kafka consumers"
            }
        }
    }

    internal fun desiredObjectKeys(namedAggregates: Set<NamedAggregate>): Set<BiObjectKey> =
        prepare(namedAggregates).desiredObjectKeys
}
