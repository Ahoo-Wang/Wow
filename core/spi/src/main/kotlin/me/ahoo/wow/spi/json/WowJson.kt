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

package me.ahoo.wow.spi.json

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.schedule.ScheduleRule
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * The one JSON mapper configuration of Wow (decision D122): the kernel's event codec, the cluster codec and every
 * backend build their mappers from [builder], so a value means the same thing wherever it is read.
 *
 * - It registers a fixed set of modules, the Kotlin module only, and never discovers others on the class path
 *   (decision D48): what Wow stores and sends must not depend on what a host happens to bring.
 * - JSON numbers keep their exact value. A decimal read into an untyped value (`Any`, `Map<String, Any?>`) or a tree
 *   is a [java.math.BigDecimal] with all its digits and its scale, never a `Double`, so `2.50` stays `2.50`; trees keep
 *   trailing zeros. An integer is an `Int`, a `Long` or, beyond `Long`, a [java.math.BigInteger], whichever holds it,
 *   which is exact already. Typed properties (`Double`, `BigDecimal`, ...) are read as their type says.
 *
 * - An [AggregateRef] is written as its type, tenant and id: its derived [AggregateRef.isAssigned] stays off the wire
 *   (decision D267 ⑮), and a payload that still carries it, from an older writer, is read as if it did not.
 * - A read ignores the properties it does not know, at every level, as Jackson 3 does by default; stated here so that
 *   the cluster codec's lenient reading (decision D48) and the event codec's reading of older payloads do not rest on
 *   a library default.
 */
object WowJson {
    /** A new builder with Wow's configuration, for a reader or writer that adds settings of its own. */
    fun builder(): JsonMapper.Builder = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .addMixIn(ScheduleRule::class.java, ScheduleRuleJson::class.java)
        .addMixIn(AggregateRef::class.java, AggregateRefJson::class.java)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)

    /** The mapper of Wow's configuration, as [builder] makes it. */
    val mapper: JsonMapper = builder().build()

    /**
     * How a [ScheduleRule] is written (decision D198): its kind in `type` (`at`, `after`, `cron`) beside its fields, as
     * the schedule events, the snapshots and the timers store it. The API has no JSON dependency, so the mapping lives
     * here.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = ScheduleRule.At::class, name = "at"),
        JsonSubTypes.Type(value = ScheduleRule.After::class, name = "after"),
        JsonSubTypes.Type(value = ScheduleRule.Cron::class, name = "cron"),
    )
    private abstract class ScheduleRuleJson

    /** How an [AggregateRef] is written: without its derived [AggregateRef.isAssigned], read or not (D267 ⑮). */
    @JsonIgnoreProperties(ASSIGNED)
    private interface AggregateRefJson

    /** The property name Jackson gives [AggregateRef.isAssigned]. */
    private const val ASSIGNED = "isAssigned"
}
