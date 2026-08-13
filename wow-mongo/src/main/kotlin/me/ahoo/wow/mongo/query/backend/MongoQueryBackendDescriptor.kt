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

@file:JvmSynthetic

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.validation.QueryBudgetLimit

internal fun mongoQueryBackendDescriptor(maxBudget: QueryBudgetLimit): QueryBackendDescriptor = QueryBackendDescriptor(
    backendId = MongoQueryBackendFactory.BACKEND_ID,
    documentKinds = QueryDocumentKind.entries.toSet(),
    planVersions = setOf(QueryPlanVersion.V1),
    portableOperators = PortableOperator.entries.toSet(),
    portableFeatures = setOf(QueryPortableFeature.ELEMENT_MATCH),
    stringComparisonModes = StringComparisonMode.entries.toSet(),
    capabilities = setOf(
        QueryCapabilityId(MongoQueryBackendFactory.FULL_TEXT_CAPABILITY),
        QueryCapabilityId(MongoQueryBackendFactory.NATIVE_CAPABILITY)
    ),
    maxBudget = maxBudget
)
