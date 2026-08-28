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

package me.ahoo.wow.api.query.schema

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.QueryProtocol
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(Temporal.Date::class, name = "TEMPORAL_DATE"),
    JsonSubTypes.Type(Temporal.Epoch::class, name = "TEMPORAL_EPOCH"),
    JsonSubTypes.Type(Temporal.Formatted::class, name = "TEMPORAL_FORMATTED"),
)
@Schema(
    oneOf = [Temporal.Date::class, Temporal.Epoch::class, Temporal.Formatted::class],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
interface QuerySemanticType

sealed interface Temporal : QuerySemanticType {
    @JsonTypeName("TEMPORAL_DATE")
    data object Date : Temporal

    @JsonTypeName("TEMPORAL_EPOCH")
    data class Epoch(
        val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) : Temporal

    @JsonTypeName("TEMPORAL_FORMATTED")
    data class Formatted(
        val pattern: String,
    ) : Temporal {
        init {
            require(pattern.isNotBlank()) { "Temporal pattern cannot be blank." }
            DateTimeFormatter.ofPattern(pattern)
        }
    }
}

@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryTemporal(
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
)
