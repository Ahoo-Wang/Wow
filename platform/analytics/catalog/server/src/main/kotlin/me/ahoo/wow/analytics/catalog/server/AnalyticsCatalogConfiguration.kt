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

package me.ahoo.wow.analytics.catalog.server

import com.clickhouse.client.api.Client
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.context.DefaultSecurityContextParser
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.analytics.catalog.domain.warehouse.TrustedWriters
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The analytics catalog's beans: who calls it and what each caller may send or read, and the catalog's ports.
 *
 * - Who calls it: with `wow.platform.trust-identity-headers=true` ([TRUST_IDENTITY_HEADERS]), the callers the request
 *   headers name ([AnalyticsCatalogCallers]), for local runs, demos and tests only; otherwise CoSec's.
 * - What each may send: [AnalyticsCatalogAuthorizer], by role, or CoSec's policies when CoSec authorizes the requests
 *   itself and `wow.cosec.authorizer.enabled` is on; what each may read: [AnalyticsCatalogQueryPolicy].
 * - The catalog (decision D241): the [WarehouseCatalog] of the ClickHouse server the host's client reaches
 *   (`wow.clickhouse.endpoint`), over `wow.analytics.catalog.databases` (`wow.clickhouse.database` when none), or
 *   [WarehouseCatalog.NONE] without one; the [TrustedWriters] of `wow.analytics.catalog.trusted-writers`; the
 *   [KnownAggregates] of the sources' snapshots; and the [CatalogWatcher] that syncs what changed every
 *   `wow.analytics.catalog.refresh-interval`, unless `wow.analytics.catalog.watch=false`.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsCatalogProperties::class)
class AnalyticsCatalogConfiguration {
    @Bean
    @ConditionalOnProperty(TRUST_IDENTITY_HEADERS, havingValue = "true")
    fun analyticsCatalogCallers(): CallerResolver {
        logger.warn {
            "$TRUST_IDENTITY_HEADERS=true: the analytics catalog takes its callers from the X-User, X-Tenant and " +
                "X-Role headers, which any client sets. For local runs, demos and tests only, never a deployment."
        }
        return AnalyticsCatalogCallers()
    }

    /** CoSec's inject mode with each token verified (the `cosec` profile), as the ops service does. */
    @Bean
    @ConditionalOnProperty(name = [COSEC_ENABLED, COSEC_INJECT_ENABLED], havingValue = "true")
    fun securityContextParser(tokenVerifier: TokenVerifier): SecurityContextParser =
        DefaultSecurityContextParser(tokenVerifier, false)

    @Bean
    fun analyticsCatalogAuthorizer(
        authorization: ObjectProvider<Authorization>,
        environment: Environment
    ): CommandAuthorizer {
        val policies = authorization.ifUnique
            ?.takeIf { environment.getProperty(COSEC_AUTHORIZER_ENABLED, Boolean::class.java, false) }
            ?: return AnalyticsCatalogAuthorizer()
        val defaultTenant = environment.getProperty(DEFAULT_TENANT).orEmpty().ifBlank { Caller.DEFAULT_TENANT }
        return AnalyticsCatalogAuthorizer(CoSecCommandAuthorizer(policies, defaultTenant))
    }

    @Bean
    fun analyticsCatalogQueryPolicy(): QueryPolicy = AnalyticsCatalogQueryPolicy()

    @Bean
    fun warehouseCatalog(
        client: ObjectProvider<Client>,
        properties: AnalyticsCatalogProperties,
        environment: Environment,
    ): WarehouseCatalog {
        val warehouse = client.ifAvailable ?: return WarehouseCatalog.NONE.also {
            logger.warn { "No ClickHouse client (wow.clickhouse.endpoint): the catalog has no warehouse table." }
        }
        val databases = properties.databases.filter { it.isNotBlank() }
            .ifEmpty { listOf(environment.getProperty(CLICKHOUSE_DATABASE, DEFAULT_DATABASE)) }
        require(properties.engine == ClickHouseWarehouseCatalog.ENGINE) {
            "${AnalyticsCatalogProperties.PREFIX}.engine is ${ClickHouseWarehouseCatalog.ENGINE}, not ${properties.engine}: " +
                "the one warehouse engine 10.0 implements."
        }
        return ClickHouseWarehouseCatalog(warehouse, properties.connection, databases)
    }

    @Bean
    fun trustedWriters(properties: AnalyticsCatalogProperties): TrustedWriters =
        TrustedWriters.of(properties.trustedWriters)

    @Bean
    fun knownAggregates(queries: ObjectProvider<QueryApi>): KnownAggregates =
        QueriedKnownAggregates { queries.getObject() }

    @Bean
    @ConditionalOnProperty(prefix = AnalyticsCatalogProperties.PREFIX, name = ["watch"], matchIfMissing = true)
    fun catalogWatcher(
        catalog: WarehouseCatalog,
        commands: ObjectProvider<CommandClient>,
        properties: AnalyticsCatalogProperties,
    ): CatalogWatcher = CatalogWatcher(catalog, { commands.getObject() }, properties.refreshInterval)

    companion object {
        /** The switch that takes the callers from the request headers ([AnalyticsCatalogCallers]): off by default. */
        const val TRUST_IDENTITY_HEADERS: String = "wow.platform.trust-identity-headers"
        const val COSEC_ENABLED: String = "cosec.enabled"
        const val COSEC_INJECT_ENABLED: String = "cosec.inject.enabled"
        const val COSEC_AUTHORIZER_ENABLED: String = "wow.cosec.authorizer.enabled"
        const val DEFAULT_TENANT: String = "wow.tenant.default"
        const val CLICKHOUSE_DATABASE: String = "wow.clickhouse.database"
        const val DEFAULT_DATABASE: String = "default"

        /** The public port's resolver first, beside CoSec's internal one ([PublicCallersFirst]). */
        @Bean
        @JvmStatic
        fun publicCallersFirst(): BeanFactoryPostProcessor = PublicCallersFirst()

        private val logger = KotlinLogging.logger {}
    }
}
