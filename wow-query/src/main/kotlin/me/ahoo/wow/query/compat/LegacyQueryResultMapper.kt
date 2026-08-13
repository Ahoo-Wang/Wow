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

package me.ahoo.wow.query.compat

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import tools.jackson.databind.JavaType

internal class LegacySnapshotResultMapper<S : Any> private constructor(
    private val namedAggregate: NamedAggregate
) {
    private val snapshotType: JavaType by lazy {
        JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
        )
    }

    companion object {
        @JvmSynthetic
        internal fun <S : Any> create(namedAggregate: NamedAggregate): LegacySnapshotResultMapper<S> =
            LegacySnapshotResultMapper(namedAggregate)
    }

    fun map(document: DynamicDocument): MaterializedSnapshot<S> = safely {
        document.convert(snapshotType)
    }
}

internal class LegacyEventResultMapper private constructor() {
    companion object {
        @JvmSynthetic
        internal fun create(): LegacyEventResultMapper = LegacyEventResultMapper()
    }

    fun map(document: DynamicDocument): DomainEventStream = safely {
        document.convert(DomainEventStream::class.java)
    }
}

private inline fun <T> safely(convert: () -> T): T = try {
    convert()
} catch (error: QueryException) {
    throw error
} catch (_: RuntimeException) {
    LegacyQueryErrorMapper.resultInvalid()
}
