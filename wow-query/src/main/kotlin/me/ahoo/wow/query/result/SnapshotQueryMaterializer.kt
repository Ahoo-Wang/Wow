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

package me.ahoo.wow.query.result

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.query.QueryException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

internal class SnapshotQueryMaterializer<S : Any>(private val objectMapper: ObjectMapper, stateType: Class<S>) {
    private val snapshotType = objectMapper.typeFactory.constructParametricType(
        MaterializedSnapshot::class.java,
        stateType
    )

    fun snapshot(record: ObjectNode): MaterializedSnapshot<S> =
        try {
            objectMapper.convertValue(record, snapshotType)
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            throw QueryException(QueryErrorCode.MATERIALIZATION_FAILED, QueryStage.MATERIALIZATION)
        }

    fun record(record: ObjectNode): ObjectNode = record
}
