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

package me.ahoo.wow.api.wire

/**
 * The identity headers of a development caller, which stand in for an identity provider: the host's development
 * callers read the caller from them in a development profile (`wow.dev-profiles`), with CoSec off and no
 * `CallerResolver` of the application's, and a client's development credential writes a caller as them. Any client
 * sets them, so a host reads them in a development profile alone, never in a deployment.
 *
 * - [USER]: the caller's subject. Without it, the request is the anonymous caller of the default tenant, and the other
 *   identity headers are ignored but [APP].
 * - [OWNER]: the owner of its scope, an owner-level caller; without it, a tenant-level caller.
 * - [ROLES]: its roles, comma-separated, the caller attribute [ROLES_ATTRIBUTE].
 * - [TENANT]: its tenant; the host's default tenant (`wow.tenant.default`) without it.
 * - [APP]: the application it calls in, its standard attribute `app` (`Caller.APP`).
 */
object DevelopmentHeaders {
    /** The header naming the caller's subject. */
    const val USER: String = "Wow-User"

    /** The header naming the owner of the caller's scope. */
    const val OWNER: String = "Wow-Owner"

    /** The header listing the caller's roles, comma-separated. */
    const val ROLES: String = "Wow-Roles"

    /** The header naming the caller's tenant. */
    const val TENANT: String = "Wow-Tenant"

    /** The header naming the application the caller calls in. */
    const val APP: String = "Wow-App"

    /** The caller attribute listing its roles, which [ROLES] gives, as CoSec's resolver names it. */
    const val ROLES_ATTRIBUTE: String = "roles"
}
