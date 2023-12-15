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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.annotation.DEFAULT_AGGREGATE_ID_NAME
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.serialization.MessageRecords

object RoutePaths {
    const val ID_KEY = DEFAULT_AGGREGATE_ID_NAME

    const val HEAD_VERSION_KEY = "headVersion"
    const val TAIL_VERSION_KEY = "tailVersion"

    const val BATCH_CURSOR_ID = "cursorId"
    const val BATCH_LIMIT = "limit"

    val VERSION = Parameter()
        .name(MessageRecords.VERSION)
        .`in`(ParameterIn.PATH.toString())
        .schema(IntegerSchema())
        .example(EventStore.DEFAULT_HEAD_VERSION)

    val HEAD_VERSION = Parameter()
        .name(HEAD_VERSION_KEY)
        .`in`(ParameterIn.PATH.toString())
        .schema(IntegerSchema())
        .example(EventStore.DEFAULT_HEAD_VERSION).let {
            ParameterRef("${Wow.WOW_PREFIX}$HEAD_VERSION_KEY", it)
        }
    val TAIL_VERSION = Parameter()
        .name(TAIL_VERSION_KEY)
        .`in`(ParameterIn.PATH.toString())
        .schema(IntegerSchema())
        .example(Int.MAX_VALUE).let {
            ParameterRef("${Wow.WOW_PREFIX}$TAIL_VERSION_KEY", it)
        }
    val BATCH_CURSOR_ID_PARAMETER = Parameter()
        .name(BATCH_CURSOR_ID)
        .`in`(ParameterIn.PATH.toString())
        .schema(StringSchema())
        .example(AggregateIdScanner.FIRST_CURSOR_ID)
        .description("The cursor id of batch.").let {
            ParameterRef("${Wow.WOW_PREFIX}$BATCH_CURSOR_ID", it)
        }
    val BATCH_LIMIT_PARAMETER = Parameter()
        .name(BATCH_LIMIT)
        .`in`(ParameterIn.PATH.toString())
        .schema(IntegerSchema())
        .example(Int.MAX_VALUE)
        .description("The size of batch.").let {
            ParameterRef("${Wow.WOW_PREFIX}$BATCH_LIMIT", it)
        }
}
