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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * The V10 side of the blue-green rehearsal (roadmap 8c.1): the 9.x example ported, on the Spring Boot host, its log
 * and snapshots on the PostgreSQL database `spring.datasource.*` names, the one migrate-data imported the 9.x data into.
 */
@SpringBootApplication(proxyBeanMethods = false)
class RehearsalServer {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
            username = environment.getProperty("spring.datasource.username")
            password = environment.getProperty("spring.datasource.password")
        },
    )

    /**
     * The rehearsal's driver names the tenant it acts in, and the owner a created order or cart belongs to, as the 9.x
     * routes named them in their paths: `X-Rehearsal-Tenant` and `X-Rehearsal-Owner`. A request without the tenant is
     * the default tenant's. A caller without an owner is tenant-level: it reaches every order of its tenant, as the 9.x
     * routes of an order did.
     */
    @Bean
    fun callerResolver(): CallerResolver = CallerResolver { request ->
        val tenant = request.headers().firstHeader(TENANT) ?: Caller.DEFAULT_TENANT
        Caller.of(tenantId = tenant, scope = Scope(request.headers().firstHeader(OWNER), null), subject = "rehearsal")
    }

    companion object {
        const val TENANT = "X-Rehearsal-Tenant"
        const val OWNER = "X-Rehearsal-Owner"
    }
}

fun main(args: Array<String>) {
    runApplication<RehearsalServer>(*args)
}
