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

package me.ahoo.wow.api.query

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(FieldType.Temporal::class),
)
@Schema(oneOf = [FieldType.Temporal::class])
sealed interface FieldType {
    @JsonSubTypes(
        JsonSubTypes.Type(Temporal.Date::class, name = "DATE"),
        JsonSubTypes.Type(Temporal.NumericEpoch::class, name = "TEMPORAL_NUMBER"),
        JsonSubTypes.Type(Temporal.FormattedString::class, name = "TEMPORAL_STRING"),
    )
    @Schema(
        oneOf = [
            FieldType.Temporal.Date::class,
            FieldType.Temporal.NumericEpoch::class,
            FieldType.Temporal.FormattedString::class,
        ],
        discriminatorProperty = "type",
    )
    sealed interface Temporal : FieldType {
        @JsonTypeName("DATE")
        data object Date : Temporal

        @JsonTypeName("TEMPORAL_NUMBER")
        data class NumericEpoch(
            val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ) : Temporal

        @JsonTypeName("TEMPORAL_STRING")
        data class FormattedString(
            @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            val datePattern: String? = null,
            @get:JsonIgnore
            @get:Schema(hidden = true)
            val dateFormatter: DateTimeFormatter? = null,
        ) : Temporal {
            init {
                require((datePattern == null) != (dateFormatter == null)) {
                    "FormattedString requires exactly one of datePattern or dateFormatter."
                }
                datePattern?.let {
                    require(it.isNotBlank()) { "datePattern cannot be blank." }
                    DateTimeFormatter.ofPattern(it)
                }
            }

            @get:JsonIgnore
            val formatter: DateTimeFormatter
                get() = dateFormatter ?: DateTimeFormatter.ofPattern(requireNotNull(datePattern))
        }
    }
}
