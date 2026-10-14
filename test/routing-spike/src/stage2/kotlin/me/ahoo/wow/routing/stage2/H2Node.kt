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

package me.ahoo.wow.routing.stage2

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.routing.accountModel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import javax.sql.DataSource

/** The bounded context of stage 2: stage 1's account, with a small state. */
const val H2_CONTEXT = "h2"

/** The service the three nodes form: its lease group of commands is `h2-stage2/h2`. */
const val H2_SERVICE = "h2-stage2"

/** Stage 1's account model (a small state: no operations kept), served by the host. */
class H2Models : ModelProvider {
    override val context: String = H2_CONTEXT
    override val fragment: String = "META-INF/wow/manifest/h2-stage2.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(accountModel(H2_CONTEXT, 0))
}

/**
 * One node of stage 2: the Spring Boot host on PostgreSQL, serving [H2Models]. The driver starts three of these, each
 * in a JVM of its own ([NodeProcess]); `h2.datasource.*` names the database and the pool.
 */
@SpringBootApplication(proxyBeanMethods = false)
class H2Node {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("h2.datasource.url")
            username = environment.getRequiredProperty("h2.datasource.username")
            password = environment.getRequiredProperty("h2.datasource.password")
            maximumPoolSize = environment.getProperty("h2.datasource.pool", Int::class.java, DEFAULT_POOL)
            minimumIdle = maximumPoolSize
        },
    )

    private companion object {
        /** Stage 1's pool per node. */
        const val DEFAULT_POOL = 16
    }
}

fun main(args: Array<String>) {
    runApplication<H2Node>(*args)
}
