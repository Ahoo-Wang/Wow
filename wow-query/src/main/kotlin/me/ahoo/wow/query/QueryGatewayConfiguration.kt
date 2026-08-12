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

package me.ahoo.wow.query

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import java.time.Clock
import java.time.ZoneId
import java.util.Collections

class QueryGatewayConfiguration(
    val admission: QueryAdmission,
    val schemaResolver: QuerySchemaResolver,
    val backendResolver: QueryBackendResolver,
    customPolicies: List<QueryPolicy>,
    resultPolicies: List<ResultPolicy>,
    val clock: Clock,
    val zoneId: ZoneId,
    val structureLimits: QueryStructureLimits,
    val systemBudgetLimit: QueryBudgetLimit,
    enabledCapabilities: Set<QueryCapabilityId>,
    val meterRegistry: MeterRegistry?
) {
    val customPolicies: List<QueryPolicy> = Collections.unmodifiableList(ArrayList(customPolicies))
    val resultPolicies: List<ResultPolicy> = Collections.unmodifiableList(ArrayList(resultPolicies))
    val enabledCapabilities: Set<QueryCapabilityId> =
        Collections.unmodifiableSet(LinkedHashSet(enabledCapabilities))
}
