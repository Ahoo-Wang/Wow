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

package me.ahoo.wow.openapi.aggregate

class TenantOwnerRouteSummarySpec {

    private var operationSummary: String = ""
    private var appendTenant: Boolean = false
    private var appendOwner: Boolean = false

    fun operationSummary(operationSummary: String): TenantOwnerRouteSummarySpec {
        this.operationSummary = operationSummary
        return this
    }

    fun appendTenant(tenant: Boolean): TenantOwnerRouteSummarySpec {
        this.appendTenant = tenant
        return this
    }

    fun appendOwner(owner: Boolean): TenantOwnerRouteSummarySpec {
        this.appendOwner = owner
        return this
    }

    fun build(): String {
        return buildString {
            append(operationSummary)
            if (appendTenant || appendOwner) {
                append(" Within")
                if (appendTenant) {
                    append(" Tenant")
                }
                if (appendOwner) {
                    append(" Owner")
                }
            }
        }
    }
}
