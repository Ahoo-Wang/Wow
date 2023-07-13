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
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.openapi.RoutePaths.BATCH_CURSOR_ID
import me.ahoo.wow.openapi.RoutePaths.BATCH_LIMIT

abstract class BatchRouteSpec : AggregateRouteSpec() {
    override val ignoreTenant: Boolean
        get() = true
    override val responseType: Class<*>
        get() = BatchResult::class.java

    override fun build(): RouteSpec {
        super.build()
        addParameter(BATCH_CURSOR_ID, ParameterIn.PATH, StringSchema()) {
            it.description("The cursor id of batch.")
            it.example(AggregateIdScanner.FIRST_CURSOR_ID)
        }
        addParameter(BATCH_LIMIT, ParameterIn.PATH, IntegerSchema()) {
            it.description("The size of batch.")
            it.example(Int.MAX_VALUE)
        }
        return this
    }
}
