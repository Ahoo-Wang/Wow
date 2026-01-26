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

package me.ahoo.wow.openapi.metadata

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.modeling.matedata.AggregateMetadata

data class AggregateRouteMetadata<C : Any>(
    override val enabled: Boolean,
    val aggregateMetadata: AggregateMetadata<C, *>,
    val resourceName: String,
    val spaced: Boolean,
    val owner: AggregateRoute.Owner
) : EnabledCapable, me.ahoo.wow.metadata.Metadata {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AggregateRouteMetadata<*>

        return aggregateMetadata == other.aggregateMetadata
    }

    override fun hashCode(): Int {
        return aggregateMetadata.hashCode()
    }
}
