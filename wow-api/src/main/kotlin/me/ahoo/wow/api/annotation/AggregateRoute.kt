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

package me.ahoo.wow.api.annotation

import java.lang.annotation.Inherited

/**
 * Marks a class as an aggregate route, which is used to define the routing behavior for aggregate operations.
 * The annotation can specify the resource name and the ownership policy for the aggregate.
 *
 * @param resourceName the name of the resource this aggregate route is associated with. Default is an empty string.
 * @param owner the ownership policy that determines how the aggregate handles ownership. It can be one of [Owner.NEVER], [Owner.ALWAYS], or [Owner.AGGREGATE_ID].
 */
@Target(AnnotationTarget.CLASS)
@Inherited
@MustBeDocumented
annotation class AggregateRoute(
    val resourceName: String = "",
    val owner: Owner = Owner.NEVER
) {

    enum class Owner(val owned: Boolean) {
        NEVER(false),
        ALWAYS(true),

        /**
         * owner id is aggregate Id
         */
        AGGREGATE_ID(true)
    }
}
