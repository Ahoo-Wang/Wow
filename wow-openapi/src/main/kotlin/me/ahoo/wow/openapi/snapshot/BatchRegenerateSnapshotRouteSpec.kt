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

package me.ahoo.wow.openapi.snapshot

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.BatchRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RoutePaths.BATCH_CURSOR_ID
import me.ahoo.wow.openapi.RoutePaths.BATCH_LIMIT

class BatchRegenerateSnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : BatchRouteSpec() {
    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.batchRegenerateSnapshot"
    override val summary: String
        get() = "Batch regenerate aggregate snapshot"

    override val method: String
        get() = Https.Method.PUT
    override val appendPathSuffix: String
        get() = "snapshot/{$BATCH_CURSOR_ID}/{$BATCH_LIMIT}"
}
