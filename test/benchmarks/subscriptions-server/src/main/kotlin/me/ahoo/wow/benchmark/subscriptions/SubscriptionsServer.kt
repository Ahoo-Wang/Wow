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

package me.ahoo.wow.benchmark.subscriptions

import me.ahoo.wow.benchmark.transfer.TransferServer
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The benchmark server ([TransferServer]) with [BenchProjections.count] no-op projections of the account
 * ([NoOpProjections]), `benchmark.projections`, 0 by default: the cost of a subscription, measured at one, two and
 * four of them against none on the same distribution (README.md). The benchmark server's own distribution has none,
 * so no other measurement changes.
 */
fun main(args: Array<String>) {
    SpringApplication(TransferServer::class.java, ProjectionsConfiguration::class.java).run(*args)
}

/** How many no-op projections of the account a node binds: `benchmark.projections`, from 0 to [MAX]. */
data class BenchProjections(val count: Int) {
    init {
        require(count in 0..MAX) { "$PROPERTY is $count: from 0 to $MAX." }
    }

    companion object {
        const val PROPERTY: String = "benchmark.projections"
        const val MAX: Int = 16
    }
}

/** [BenchProjections] from `benchmark.projections`, which [NoOpProjections] finds among the node's dependencies. */
@Configuration(proxyBeanMethods = false)
class ProjectionsConfiguration {
    @Bean
    fun benchProjections(environment: Environment): BenchProjections =
        BenchProjections(environment.getProperty(BenchProjections.PROPERTY, Int::class.java, 0))
}
