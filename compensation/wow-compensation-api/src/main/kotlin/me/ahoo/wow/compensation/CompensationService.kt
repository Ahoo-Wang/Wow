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

package me.ahoo.wow.compensation

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.api.annotation.BoundedContext.Aggregate
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.compensation.api.CreateExecutionFailed

@BoundedContext(
    name = CompensationService.SERVICE_NAME,
    alias = CompensationService.SERVICE_ALIAS,
    aggregates = [
        Aggregate(
            name = CompensationService.EXECUTION_FAILED_AGGREGATE_NAME,
            tenantId = TenantId.DEFAULT_TENANT_ID,
            packageScopes = [CreateExecutionFailed::class]
        )
    ],
)
object CompensationService {
    const val SERVICE_ALIAS = "compensation"
    const val SERVICE_NAME = "${SERVICE_ALIAS}-service"
    const val EXECUTION_FAILED_AGGREGATE_NAME = "execution_failed"
}
