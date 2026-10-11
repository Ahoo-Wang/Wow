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

package me.ahoo.wow.analytics.server

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer

class ViewStoreAccessTest {
    private val access = ViewStoreAccess(sharedViewRole = "editor", systemViewRole = "admin")

    private fun principal(id: String, tenant: String, vararg roles: String): CoSecPrincipal =
        SimpleTenantPrincipal(SimplePrincipal(id, roles = roles.toSet()), SimpleTenant(tenant))

    @ParameterizedTest(name = "{0} {1} as {2}@{3} [{4}] -> {5}")
    @CsvSource(
        delimiter = '|',
        value = [
            "GET    | /view-store/tenant/t1/owner/u1/view                   | u1 | t1          |        | ALLOW",
            "POST   | /view-store/tenant/t1/owner/u1/view                   | u1 | t1          |        | ALLOW",
            "GET    | /view-store/tenant/t1/owner/u2/view                   | u1 | t1          |        | FORBIDDEN",
            "GET    | /view-store/tenant/t2/owner/u1/view                   | u1 | t1          |        | FORBIDDEN",
            "PUT    | /view-store/tenant/t1/owner/u1/view/v1/share          | u1 | t1          |        | FORBIDDEN",
            "PUT    | /view-store/tenant/t1/owner/u1/view/v1/share          | u1 | t1          | editor | ALLOW",
            "PUT    | /view-store/tenant/t1/owner/u1/view/v1/claim          | u1 | t1          | editor | ALLOW",
            "GET    | /view-store/tenant/t1/owner/(shared)/view             | u1 | t1          |        | ALLOW",
            "POST   | /view-store/tenant/t1/owner/(shared)/view/snapshot/list | u1 | t1        |        | ALLOW",
            "POST   | /view-store/tenant/t1/owner/(shared)/view             | u1 | t1          |        | FORBIDDEN",
            "DELETE | /view-store/tenant/t1/owner/(shared)/view/v1          | u1 | t1          | editor | ALLOW",
            "GET    | /view-store/tenant/t2/owner/(shared)/view             | u1 | t1          |        | FORBIDDEN",
            "PUT    | /view-store/tenant/(platform)/owner/(system)/view/v1  | u1 | t1          | admin  | FORBIDDEN",
            "PUT    | /view-store/tenant/(platform)/owner/(system)/view/v1  | u1 | (platform)  |        | FORBIDDEN",
            "PUT    | /view-store/tenant/(platform)/owner/(system)/view/v1  | u1 | (platform)  | admin  | ALLOW",
            "PUT    | /view-store/tenant/t1/owner/(system)/view/v1          | u1 | t1          | admin  | FORBIDDEN",
            "GET    | /view-store/view/v1/state                             | u1 | t1          |        | FORBIDDEN",
        ],
    )
    fun `applies the view store's gateway rules`(
        method: String,
        path: String,
        id: String,
        tenant: String,
        role: String?,
        expected: String,
    ) {
        val roles = listOfNotNull(role).toTypedArray()
        val decision = access.decide(
            HttpMethod.valueOf(method),
            PathContainer.parsePath(path),
            principal(id, tenant, *roles)
        )
        decision.assert().isEqualTo(if (expected == "ALLOW") null else HttpStatus.valueOf(expected))
    }
}
