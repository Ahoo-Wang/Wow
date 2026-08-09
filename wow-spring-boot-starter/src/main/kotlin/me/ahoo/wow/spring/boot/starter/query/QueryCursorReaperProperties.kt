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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/** Explicit opt-in scheduling policy for bounded Query cursor lease cleanup. */
@ConfigurationProperties(prefix = QueryCursorReaperProperties.PREFIX)
class QueryCursorReaperProperties(
    @DefaultValue("false") override val enabled: Boolean = false,
    @DefaultValue("PT30S") val initialDelay: Duration = Duration.ofSeconds(30),
    @DefaultValue("PT1M") val interval: Duration = Duration.ofMinutes(1),
    @DefaultValue("100") val batchSize: Int = 100,
    @DefaultValue("10") val maxBatchesPerRun: Int = 10,
) : EnabledCapable {
    init {
        require(!initialDelay.isNegative) { "Query cursor reaper initial delay must not be negative." }
        require(!interval.isNegative && !interval.isZero) {
            "Query cursor reaper interval must be positive."
        }
        require(batchSize > 0) { "Query cursor reaper batch size must be positive." }
        require(maxBatchesPerRun in 1..MAX_BATCHES_PER_RUN) {
            "Query cursor reaper max batches per run must be between 1 and $MAX_BATCHES_PER_RUN."
        }
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}query.cursor.reaper"
        private const val MAX_BATCHES_PER_RUN = 1_000
    }
}
