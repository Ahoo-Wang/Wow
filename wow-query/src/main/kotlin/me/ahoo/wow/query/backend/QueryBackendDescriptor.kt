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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.validation.QueryBudgetLimit
import java.util.Collections

enum class QueryPortableFeature {
    ELEMENT_MATCH
}

class QueryPlanVersion(val value: Int) {
    init {
        require(value > 0) { "Query plan version must be positive." }
    }

    override fun equals(other: Any?): Boolean = other is QueryPlanVersion && value == other.value

    override fun hashCode(): Int = value

    override fun toString(): String = "QueryPlanVersion(value=$value)"

    companion object {
        @JvmField
        val V1: QueryPlanVersion = QueryPlanVersion(1)
    }
}

class QueryBackendRouteIdentity(val value: String) {
    init {
        require(ROUTE_ID_PATTERN.matches(value)) { "Query backend route identity is invalid." }
    }

    override fun equals(other: Any?): Boolean = other is QueryBackendRouteIdentity && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "<redacted>"

    private companion object {
        val ROUTE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

class QueryBackendDescriptor(
    backendId: String,
    documentKinds: Set<QueryDocumentKind>,
    planVersions: Set<QueryPlanVersion>,
    portableOperators: Set<PortableOperator>,
    portableFeatures: Set<QueryPortableFeature>,
    stringComparisonModes: Set<StringComparisonMode>,
    capabilities: Set<QueryCapabilityId>,
    val maxBudget: QueryBudgetLimit
) {
    val backendId: String = backendId.also {
        require(BACKEND_ID_PATTERN.matches(it) && it !in RESERVED_BACKEND_IDS) {
            "Query backend id is invalid."
        }
    }
    val documentKinds: Set<QueryDocumentKind> = immutableSet(documentKinds)
    val planVersions: Set<QueryPlanVersion> = immutableSet(planVersions)
    val portableOperators: Set<PortableOperator> = immutableSet(portableOperators)
    val portableFeatures: Set<QueryPortableFeature> = immutableSet(portableFeatures)
    val stringComparisonModes: Set<StringComparisonMode> = immutableSet(stringComparisonModes)
    val capabilities: Set<QueryCapabilityId> = immutableSet(capabilities)

    init {
        require(this.documentKinds.isNotEmpty()) { "Backend document kinds cannot be empty." }
        require(this.planVersions.isNotEmpty()) { "Backend plan versions cannot be empty." }
    }

    override fun equals(other: Any?): Boolean = other is QueryBackendDescriptor &&
        backendId == other.backendId && documentKinds == other.documentKinds && planVersions == other.planVersions &&
        portableOperators == other.portableOperators && portableFeatures == other.portableFeatures &&
        stringComparisonModes == other.stringComparisonModes && capabilities == other.capabilities &&
        maxBudget == other.maxBudget

    override fun hashCode(): Int = listOf(
        backendId,
        documentKinds,
        planVersions,
        portableOperators,
        portableFeatures,
        stringComparisonModes,
        capabilities,
        maxBudget
    ).hashCode()

    override fun toString(): String =
        "QueryBackendDescriptor(backendId=$backendId, documentKinds=$documentKinds, " +
            "planVersions=$planVersions, portableOperatorCount=${portableOperators.size}, " +
            "portableFeatureCount=${portableFeatures.size}, stringComparisonModeCount=${stringComparisonModes.size}, " +
            "capabilityCount=${capabilities.size}, maxBudget=$maxBudget)"

    private fun <T> immutableSet(source: Set<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(source))

    private companion object {
        val BACKEND_ID_PATTERN = Regex("[a-z][a-z0-9-]{0,63}")
        val RESERVED_BACKEND_IDS = setOf("authority", "driver", "system")
    }
}
