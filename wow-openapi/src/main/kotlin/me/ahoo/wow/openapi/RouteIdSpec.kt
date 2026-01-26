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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.NAMED_AGGREGATE_DELIMITER
import me.ahoo.wow.modeling.toStringWithAlias

class RouteIdSpec {

    private var prefix: String = ""
    private var appendTenant: Boolean = false
    private var appendSpace: Boolean = false
    private var appendOwner: Boolean = false
    private var resourceName: String = ""
    private var operation: String = ""

    fun aggregate(namedAggregate: NamedAggregate): RouteIdSpec {
        return prefix(namedAggregate.toStringWithAlias())
    }

    fun prefix(prefix: String): RouteIdSpec {
        this.prefix = prefix
        return this
    }

    fun appendTenant(appendTenant: Boolean): RouteIdSpec {
        this.appendTenant = appendTenant
        return this
    }

    fun appendSpace(appendSpace: Boolean): RouteIdSpec {
        this.appendSpace = appendSpace
        return this
    }

    fun appendOwner(appendOwner: Boolean): RouteIdSpec {
        this.appendOwner = appendOwner
        return this
    }

    fun resourceName(resourceName: String): RouteIdSpec {
        this.resourceName = resourceName
        return this
    }

    fun operation(operation: String): RouteIdSpec {
        this.operation = operation
        return this
    }

    fun build(): String {
        return buildString {
            if (prefix.isNotBlank()) {
                append(prefix)
            }
            if (appendTenant) {
                append(NAMED_AGGREGATE_DELIMITER)
                append("tenant")
            }
            if (appendSpace) {
                append(NAMED_AGGREGATE_DELIMITER)
                append("space")
            }
            if (appendOwner) {
                append(NAMED_AGGREGATE_DELIMITER)
                append("owner")
            }
            if (resourceName.isNotBlank()) {
                append(NAMED_AGGREGATE_DELIMITER)
                append(resourceName)
            }
            if (operation.isNotBlank()) {
                append(NAMED_AGGREGATE_DELIMITER)
                append(operation)
            }
        }
    }
}
