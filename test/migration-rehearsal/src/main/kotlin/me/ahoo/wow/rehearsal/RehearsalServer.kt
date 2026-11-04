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

package me.ahoo.wow.rehearsal

import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.rehearsal.example.OrderState
import me.ahoo.wow.rehearsal.example.OrderStateAccess
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * The V10 side of the blue-green rehearsal (.1): the 9.x example ported, on the Spring Boot host, its log
 * and snapshots on the PostgreSQL database `spring.datasource.*` names, the one migrate-data imported the 9.x data into:
 * the host makes its pool of those properties.
 */
@SpringBootApplication(proxyBeanMethods = false)
class RehearsalServer {
    /**
     * The rehearsal's driver names the tenant it acts in, and the owner a created order or cart belongs to, as the 9.x
     * routes named them in their paths: `X-Rehearsal-Tenant` and `X-Rehearsal-Owner`. A request without the tenant is
     * the default tenant's. A caller without an owner is tenant-level: it reaches every order of its tenant, as the 9.x
     * routes of an order did. `X-Rehearsal-Region` gives the caller the claim [REGION], whose orders alone it reaches
     * ([orderAccess]).
     */
    @Bean
    fun callerResolver(): CallerResolver = CallerResolver { request ->
        val tenant = request.headers().firstHeader(TENANT) ?: Caller.DEFAULT_TENANT
        val region = request.headers().firstHeader(REGION_HEADER)
        Caller.of(
            tenantId = tenant,
            scope = Scope(request.headers().firstHeader(OWNER), null),
            subject = "rehearsal",
            attributes = region?.let { mapOf(REGION to listOf(it)) }.orEmpty(),
        )
    }

    /**
     * Who reads and changes an order, which declares access attributes: a caller of a region the orders tagged
     * with it, an owner its own, and a tenant-level caller every order of its tenant, as the scope rule had it.
     */
    @Bean
    fun orderAccess(): AccessPolicy<OrderState> = accessPolicy {
        readWrite { caller ->
            val region = caller.claim(REGION)
            val owner = caller.scope.ownerId
            when {
                region != null -> OrderStateAccess.REGION eq region
                owner != null -> OrderStateAccess.OWNER eq owner
                else -> allowAll()
            }
        }
    }

    companion object {
        const val TENANT = "X-Rehearsal-Tenant"
        const val OWNER = "X-Rehearsal-Owner"
        const val REGION_HEADER = "X-Rehearsal-Region"

        /** The claim of the region a caller reads the orders of. */
        const val REGION = "region"
    }
}

fun main(args: Array<String>) {
    runApplication<RehearsalServer>(*args)
}
