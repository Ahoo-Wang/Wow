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

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.TransportCodec
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * The one JSON mapper configuration of Wow: the kernel's event codec, the cluster codec and every
 * backend build their mappers from [builder], so a value means the same thing wherever it is read.
 *
 * - It registers a fixed set of modules, the Kotlin module and Wow's own, and never discovers others on the class path:
 * what Wow stores and sends must not depend on what a host happens to bring.
 * - A [me.ahoo.wow.spi.log.Transport] is written in its one form ([TransportCodec]), wherever a
 *   message embeds one.
 * - JSON numbers keep their exact value. A decimal read into an untyped value (`Any`, `Map<String, Any?>`) or a tree
 *   is a [java.math.BigDecimal] with all its digits and its scale, never a `Double`, so `2.50` stays `2.50`; trees keep
 *   trailing zeros. An integer is an `Int`, a `Long` or, beyond `Long`, a [java.math.BigInteger], whichever holds it,
 *   which is exact already. Typed properties (`Double`, `BigDecimal`, ...) are read as their type says.
 *
 * - An [AggregateRef] is written as its type, tenant and id: its derived [AggregateRef.isAssigned] stays off the wire,
 * and a payload that still carries it, from an older writer, is read as if it did not.
 * - A read ignores the properties it does not know, at every level, as Jackson 3 does by default; stated here so that
 *   the cluster codec's lenient reading and the event codec's reading of older payloads do not rest on
 *   a library default.
 */
object WowJson {
    /** A new builder with Wow's configuration, for a reader or writer that adds settings of its own. */
    fun builder(): JsonMapper.Builder = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .addMixIn(ScheduleRule::class.java, ScheduleRuleJson::class.java)
        .addMixIn(AggregateRef::class.java, AggregateRefJson::class.java)
        .addModule(TransportCodec.module)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)

    /** The mapper of Wow's configuration, as [builder] makes it. */
    val mapper: JsonMapper = builder().build()

    /**
     * A new builder of a reader that may be older than the writer of what it reads, Wow's [builder] reading an
     * unknown value of a result's status, a hop's stage or a function's kind as `UNKNOWN`: the client of a peer
     * that was upgraded first, or a node of the minor version before its cluster's. A reader of requests keeps
     * refusing an unknown value: it is newer than its client, and taking a typo for a value would change its meaning.
     */
    fun readerBuilder(): JsonMapper.Builder = builder()
        .addMixIn(CommandResult.Status::class.java, StatusJson::class.java)
        .addMixIn(Stage::class.java, StageJson::class.java)
        .addMixIn(FunctionKind::class.java, FunctionKindJson::class.java)
        .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)

    /**
     * How a [ScheduleRule] is written: its kind in `type` (`at`, `after`, `cron`) beside its fields, as
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

    /** How an [AggregateRef] is written: without its derived [AggregateRef.isAssigned], read or not. */
    @JsonIgnoreProperties(ASSIGNED)
    private interface AggregateRefJson

    /** [CommandResult.Status.UNKNOWN] as the value an unknown status is read as. */
    private enum class StatusJson { @JsonEnumDefaultValue UNKNOWN }

    /** [Stage.UNKNOWN] as the value an unknown stage is read as. */
    private enum class StageJson { @JsonEnumDefaultValue UNKNOWN }

    /** [FunctionKind.UNKNOWN] as the value an unknown kind is read as. */
    private enum class FunctionKindJson { @JsonEnumDefaultValue UNKNOWN }

    /** The property name Jackson gives [AggregateRef.isAssigned]. */
    private const val ASSIGNED = "isAssigned"
}
