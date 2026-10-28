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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.TransportCodec
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * The one JSON mapper configuration of Wow: the kernel's event codec, the cluster codec and every
 * backend build their mappers from [builder], so a value means the same thing wherever it is read.
 *
 * - It registers a fixed set of modules, the Kotlin module and Wow's own, and never discovers others on the class path:
 * what Wow stores and sends must not depend on what a host happens to bring.
 * - Wow's own enumerations are written by their lower-kebab wire names, and read by them alone
 *   ([WowEnums]); a domain's enumerations are written as its Jackson writes them.
 * - A [me.ahoo.wow.spi.log.Transport] is written in its one form ([TransportCodec]), wherever a
 *   message embeds one.
 * - JSON numbers keep their exact value. A decimal read into an untyped value (`Any`, `Map<String, Any?>`) or a tree
 *   is a [java.math.BigDecimal] with all its digits and its scale, never a `Double`, so `2.50` stays `2.50`; trees keep
 *   trailing zeros. An integer is an `Int`, a `Long` or, beyond `Long`, a [java.math.BigInteger], whichever holds it,
 *   which is exact already. Typed properties (`Double`, `BigDecimal`, ...) are read as their type says.
 *
 * - An [AggregateRef] is written as `{type: {context, name}, tenantId, id}`, its derived [AggregateRef.isAssigned]
 *   left out, and an id not chosen yet ([AggregateRef.UNASSIGNED]) written `null`: `(none)` is never an id on the wire,
 *   and a ref that names it is refused when read. A ref whose `id` is null or left out reads as not assigned.
 * - A read ignores the properties it does not know, at every level, as Jackson 3 does by default; stated here so that
 *   the cluster codec's lenient reading and the event codec's reading of older payloads do not rest on
 *   a library default.
 */
object WowJson {
    /** A new builder with Wow's configuration, for a reader or writer that adds settings of its own. */
    fun builder(): JsonMapper.Builder = base().addModule(WowEnums.module(tolerant = false))

    /** Wow's configuration but for its enumerations ([WowEnums]), which a writer and an older reader read apart. */
    private fun base(): JsonMapper.Builder = JsonMapper.builder()
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
     * unknown value of a result's status, a hop's stage or a function's kind as `UNKNOWN` ([WowEnums]): the client of
     * a peer that was upgraded first, or a node of the minor version before its cluster's. A reader of requests keeps
     * refusing an unknown value: it is newer than its client, and taking a typo for a value would change its meaning.
     */
    fun readerBuilder(): JsonMapper.Builder = base().addModule(WowEnums.module(tolerant = true))

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

    /** How an [AggregateRef] is written and read ([AggregateRefWriter], [AggregateRefReader]). */
    @JsonSerialize(using = AggregateRefWriter::class)
    @JsonDeserialize(using = AggregateRefReader::class)
    private interface AggregateRefJson

    /** Writes a ref's type, tenant and id, an id not assigned as `null`. */
    private class AggregateRefWriter : ValueSerializer<AggregateRef>() {
        override fun serialize(value: AggregateRef, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeStartObject()
            gen.writeName(TYPE)
            gen.writeStartObject()
            gen.writeStringProperty(CONTEXT, value.type.context)
            gen.writeStringProperty(NAME, value.type.name)
            gen.writeEndObject()
            gen.writeStringProperty(TENANT_ID, value.tenantId)
            gen.writeName(ID)
            if (value.isAssigned) gen.writeString(value.id) else gen.writeNull()
            gen.writeEndObject()
        }
    }

    /** Reads a ref; an `id` null or left out is not assigned, and an id of `(none)` is refused. */
    private class AggregateRefReader : ValueDeserializer<AggregateRef>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AggregateRef {
            val node = ctxt.readTree(p)
            val type = node.path(TYPE)
            val id = node.path(ID)
            if (id.isString && id.asString() == AggregateRef.UNASSIGNED) {
                throw MismatchedInputException.from(
                    p,
                    AggregateRef::class.java,
                    "An aggregate ref's id is never ${AggregateRef.UNASSIGNED}: one not assigned is null.",
                )
            }
            return try {
                AggregateType(type.text(p, CONTEXT), type.text(p, NAME)).ref(
                    node.text(p, TENANT_ID),
                    if (id.isMissingNode || id.isNull) AggregateRef.UNASSIGNED else node.text(p, ID),
                )
            } catch (invalid: IllegalArgumentException) {
                throw MismatchedInputException.from(p, AggregateRef::class.java, invalid.message)
            }
        }

        private fun JsonNode.text(p: JsonParser, name: String): String =
            path(name).takeIf { it.isString }?.asString()
                ?: throw MismatchedInputException.from(p, AggregateRef::class.java, "An aggregate ref names its $name.")
    }

    private const val TYPE = "type"
    private const val CONTEXT = "context"
    private const val NAME = "name"
    private const val TENANT_ID = "tenantId"
    private const val ID = "id"
}
