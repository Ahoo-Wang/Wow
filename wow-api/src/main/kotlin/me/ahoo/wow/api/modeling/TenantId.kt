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

package me.ahoo.wow.api.modeling

/**
 * Interface defining a property for tenant ID and related operations.
 * Tenant ID is used to distinguish different tenants in a multi-tenant environment.
 */
interface TenantId {
    /**
     * Gets the tenant ID.
     */
    val tenantId: String

    /**
     * Companion object containing the default tenant ID and related utility functions.
     */
    companion object {
        /**
         * Default tenant ID used as a fallback when no tenant is specified.
         */
        const val DEFAULT_TENANT_ID = "(0)"

        /**
         * Returns the default tenant ID if the given string is null.
         *
         * @param this The nullable string to check.
         * @return The string itself if not null, otherwise DEFAULT_TENANT_ID.
         */
        fun String?.orDefaultTenantId(): String = this ?: DEFAULT_TENANT_ID
    }
}
