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

package me.ahoo.wow.benchmark.transfer

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.mongo.MongoPoolGauge
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

// region mongo
/**
 * The benchmark's V10 server: the account and the transfer saga on MongoDB (the `mongo` profile) or PostgreSQL (the
 * `postgres` profile), its state stream published to Kafka (`application.yaml`). A node of a cluster takes its ports
 * and node id from the command line (test/benchmarks/README.md).
 */
@SpringBootApplication(proxyBeanMethods = false)
class TransferServer

/**
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. A
 * bean of its own, not the one the host makes of the property, because it takes the application's
 * driver [CommandListener]s, which no property can add: none in the server, the round-trip counter in ComponentBench.
 * It is built with the stores' [MongoPoolGauge], as an application's own client is, so the stores bound their queries
 * by its pool and read their write pressure from it. The `postgres` profile needs no bean: the host makes its pool of
 * `spring.datasource.*`.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
class MongoBackend {
    @Bean(destroyMethod = "close")
    fun mongoClient(
        environment: Environment,
        listeners: ObjectProvider<CommandListener>,
        gauge: MongoPoolGauge,
    ): MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri")))
            .applyToConnectionPoolSettings(gauge::applyTo)
            .apply { listeners.orderedStream().forEach(::addCommandListener) }
            .build(),
    )
}
// endregion

/**
 * Who the benchmark's clients are; outside development the host wants that said in a bean. Anonymous, unless
 * `benchmark.access` says otherwise: `claims` makes every request a teller's, a caller with a subject and a role whose
 * claims travel with each command, and `policy` also holds the accounts to an access policy the teller passes, so the
 * two compare the write path's cost under a policy. `dataset` makes every request an analyst's, whose claims name the
 * owners it reads, and holds the accounts to a policy that compares each account's owner with them: the access scale
 * check's (bench.sh measure), where a dataset of the accounts' rows reads them by that comparison.
 */
@Configuration(proxyBeanMethods = false)
class BenchCallers {
    @Bean
    fun benchCallerResolver(environment: Environment): CallerResolver =
        when (environment.getProperty(ACCESS, NONE)) {
            NONE -> CallerResolver.ANONYMOUS
            DATASET -> CallerResolver { ANALYST }
            else -> CallerResolver { TELLER }
        }

    @Bean
    @ConditionalOnProperty(ACCESS, havingValue = POLICY)
    fun accountAccess(): AccessPolicy<AccountState> = accessPolicy {
        // The owner's or a teller's: the teller is never an account's owner, so both comparisons run.
        readWrite { caller -> anyOf(AccessAttribute.OWNER eq caller.subject, allowIf(caller.hasRole("teller"))) }
    }

    @Bean
    @ConditionalOnProperty(ACCESS, havingValue = DATASET)
    fun accountOwnersAccess(): AccessPolicy<AccountState> = accessPolicy {
        // The analyst owns no account: the second comparison decides, one of a disjunction, as a policy's often is.
        readWrite { caller ->
            anyOf(AccessAttribute.OWNER eq caller.subject, AccessAttribute.OWNER isIn caller.claims(OWNERS))
        }
    }

    private companion object {
        const val ACCESS = "benchmark.access"
        const val NONE = "none"
        const val POLICY = "policy"
        const val DATASET = "dataset"
        const val OWNERS = "owners"
        val TELLER = Caller.of(subject = "bench-teller", attributes = mapOf("roles" to listOf("teller")))

        /** Reads the accounts of 10 of the scale check's 100 owners (`u0` to `u9`): a tenth of them. */
        val ANALYST = Caller.of(subject = "bench-analyst", attributes = mapOf(OWNERS to (0 until 10).map { "u$it" }))
    }
}

fun main(args: Array<String>) {
    runApplication<TransferServer>(*args)
}
