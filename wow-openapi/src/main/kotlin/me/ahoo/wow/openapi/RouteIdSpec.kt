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

/**
 * Builds a route id, the OpenAPI `operationId` of a route: `{context}.{aggregate}[.tenant][.owner][.{resource}][.{operation}]`,
 * for example `example.cart.snapshot_state.single`.
 *
 * Route ids are a contract, not just labels. `@ahoo-wang/wow-generator` reads a service's `/v3/api-docs` and finds
 * operations by them (`typescript/wow-generator/src/wow/conventions.ts`): `wow.command.send`, and the suffixes
 * `.snapshot_state.single`, `.event.list_query` and `.snapshot.count`; a command's id is
 * `{context}.{aggregate}.{command}`. So the resource names and operation names the route contributors pass here
 * (`snapshot`, `snapshot_state`, `event`, `list_query`, `single`, `count`, …) change only with the generator, in a
 * major version. The committed contract snapshot (`example-domain-contract.snapshot.json`) pins every id.
 */
internal class RouteIdSpec {

    private var prefix: String = ""
    private var appendTenant: Boolean = false
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
