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

package me.ahoo.wow.spring.boot.starter.webflux

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = WebFluxProperties.PREFIX)
class WebFluxProperties
@Autowired(required = false)
constructor(
    @DefaultValue("true") override var enabled: Boolean = true,
    var globalError: GlobalError = GlobalError(),
    var batch: Batch = Batch(),
) : EnabledCapable {
    var query: Query = Query()

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}webflux"
        const val COMMAND_REQUEST_APPENDER_PREFIX = "$PREFIX.command.request.appender"
        const val GLOBAL_ERROR_ENABLED = "$PREFIX.global-error$ENABLED_SUFFIX_KEY"
    }

    data class GlobalError(
        @DefaultValue("true")
        override var enabled: Boolean = true
    ) : EnabledCapable

    data class Batch(
        @DefaultValue("1")
        var concurrency: Int = 1,
        @DefaultValue("1")
        var prefetch: Int = 1
    )

    data class Query(
        @DefaultValue("1000")
        var maxListSize: Int = 1000,
        @DefaultValue("32")
        var maxAggregationMetrics: Int = 32,
        @DefaultValue("100")
        var maxPageSize: Int = 100,
        @DefaultValue("10000")
        var maxPageWindow: Long = 10_000,
        @DefaultValue("64")
        var maxConditionNodes: Int = 64,
        @DefaultValue("1000")
        var maxConditionValues: Int = 1000,
        var allowedSortFields: Set<String> = emptySet(),
        var allowedConditionFields: Set<String> = emptySet(),
        @DefaultValue("false")
        var allowRaw: Boolean = false,
        @DefaultValue("false")
        var allowExpensiveOperators: Boolean = false,
        @DefaultValue("10s")
        var idleTimeout: Duration = Duration.ofSeconds(10),
    ) {
        init {
            require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
            require(maxAggregationMetrics >= 0) { "maxAggregationMetrics must be greater than or equal to 0." }
            require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
            require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
            require(maxConditionNodes >= 0) { "maxConditionNodes must be greater than or equal to 0." }
            require(maxConditionValues >= 0) { "maxConditionValues must be greater than or equal to 0." }
            require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
        }
    }
}
