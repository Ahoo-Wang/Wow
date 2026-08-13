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
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import reactor.core.publisher.Flux
import tools.jackson.databind.JavaType

@JvmSynthetic
internal fun <S : Any> legacySnapshotType(namedAggregate: NamedAggregate): JavaType =
    JsonSerializer.typeFactory.constructParametricType(
        MaterializedSnapshot::class.java,
        namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
    )

@JvmSynthetic
internal fun <S : Any> materializeLegacySnapshot(
    document: DynamicDocument,
    snapshotType: Lazy<JavaType>
): MaterializedSnapshot<S> = materializeLegacyDocument {
    document.convert(snapshotType.value)
}

@JvmSynthetic
internal fun materializeLegacyEvent(document: DynamicDocument): DomainEventStream = materializeLegacyDocument {
    document.convert(DomainEventStream::class.java)
}

@JvmSynthetic
internal fun <T : Any> materializeLegacyList(
    source: Flux<DynamicDocument>,
    materialize: (DynamicDocument) -> T
): Flux<T> = Flux.defer {
    var emitted = false
    source.map { document ->
        try {
            materialize(document).also { emitted = true }
        } catch (error: QueryException) {
            if (!emitted || error.code != QueryErrorCode.RESULT_VALIDATION_FAILED) {
                throw error
            }
            throw QueryException(
                QueryErrorCode.INCOMPLETE_RESULT,
                QueryStage.EXECUTION,
                QueryErrorReason.INCOMPLETE_STREAM,
                error.code
            )
        }
    }
}

private inline fun <T> materializeLegacyDocument(convert: () -> T): T = try {
    convert()
} catch (error: QueryException) {
    throw error
} catch (_: RuntimeException) {
    throw QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.EXECUTION,
        QueryErrorReason.RESULT_INVALID
    )
}
