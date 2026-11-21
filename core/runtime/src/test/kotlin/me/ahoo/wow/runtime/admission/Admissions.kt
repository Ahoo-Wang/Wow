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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.tenant.TenantPolicy

/**
 * A context's admission for a test that builds its executor by hand: fresh creation ids, no validators and the callers'
 * tenants unless it gives its own. Main code takes the context's one admission ([me.ahoo.wow.runtime.node.ContextNode]).
 */
internal fun admissionOf(
    creations: CreationIds = CreationIds(IdGenerators.DEFAULT, CommittedRequests.NONE),
    validators: List<CommandValidator> = emptyList(),
    tenants: TenantPolicy = TenantPolicy.CALLERS,
): ServiceCommandAdmission = ServiceCommandAdmission(validators, creations, tenants)
