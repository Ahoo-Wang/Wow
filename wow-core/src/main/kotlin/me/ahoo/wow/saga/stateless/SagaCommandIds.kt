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

package me.ahoo.wow.saga.stateless

import me.ahoo.cosid.Decorator
import me.ahoo.cosid.IdConverter
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.converter.DatePrefixIdConverter
import me.ahoo.cosid.converter.SnowflakeFriendlyIdConverter
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.cosid.CosIdState
import me.ahoo.cosid.cosid.RadixCosIdStateParser
import me.ahoo.cosid.snowflake.SecondSnowflakeId
import me.ahoo.cosid.snowflake.SnowflakeId
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.AggregateIdGeneratorRegistrar
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The IDs of the commands a saga function sends for an event, derived from that event so that handling the same event
 * again (a retry, a redelivery) sends the same commands rather than new ones (B7, since 9.3.0):
 *
 * - the request ID of the `index`-th command is `"<event ID>-<index>"` (unchanged since 9.2);
 * - a command that names no aggregate gets [aggregateId] instead of a random one, so a retried create targets the
 *   aggregate the first attempt created and is rejected as a duplicate request instead of creating a second one.
 *
 * The derived aggregate ID has the format of the target aggregate's own ID generator: its timestamp is the event's
 * creation time and the bits a generator spends on its machine ID and sequence come from a hash of the event ID, the
 * saga function, the command's index and the target aggregate type. It parses, sorts and looks like an ID that
 * generator made. This works for the time-based CosId generators, the default CosId and Snowflake (incl. their
 * clock-sync and string decorators). A segment generator (database-allocated ranges, which a derived ID could collide
 * with), a custom generator, and a generator whose string form depends on the current date or the time zone (a date
 * prefix, a friendly ID) keep random IDs.
 *
 * The aggregate ID is set on the command builder before any `CommandBuilderRewriter` runs, so a rewriter sees it and
 * may replace it.
 */
internal object SagaCommandIds {
    private const val COSID_MACHINE_BIT = 20
    private const val COSID_SEQUENCE_BIT = 16

    fun requestId(event: DomainEvent<*>, index: Int): String = "${event.id}-$index"

    /**
     * The aggregate ID of the [index]-th command [producer] sends for [event] to an aggregate of type [target], or
     * `null` when the target's generator is not time-based.
     */
    fun aggregateId(event: DomainEvent<*>, producer: FunctionInfo, index: Int, target: NamedAggregate): String? {
        val generator = AggregateIdGeneratorRegistrar.getOrInitialize(target)
        val seed = listOf(
            event.id,
            producer.contextName,
            producer.processorName,
            producer.name,
            index.toString(),
            target.contextName,
            target.aggregateName,
        ).joinToString("|")
        return derive(generator, event.createTime, hash(seed))
    }

    /** An ID in [generator]'s format for [timestampMillis], whose machine and sequence bits come from [hash]. */
    fun derive(generator: IdGenerator, timestampMillis: Long, hash: Long): String? =
        when (val idGenerator = timeBasedGenerator(generator)) {
            // A string decorator around a CosId generator formats another way: keep random IDs.
            is CosIdGenerator -> deriveCosId(idGenerator, timestampMillis, hash).takeIf { generator is CosIdGenerator }
            is SnowflakeId -> deriveSnowflake(generator, idGenerator, timestampMillis, hash)
            else -> null
        }

    /**
     * The first time-based generator in [generator]'s decorator chain (the outermost one keeps its string converter),
     * or `null` when there is none.
     */
    private fun timeBasedGenerator(generator: IdGenerator): IdGenerator? {
        var current: Any? = generator
        while (current != null) {
            if (current is CosIdGenerator || current is SnowflakeId) {
                return current as IdGenerator
            }
            current = (current as? Decorator<*>)?.actual
        }
        return null
    }

    private fun deriveCosId(generator: CosIdGenerator, timestampMillis: Long, hash: Long): String? {
        val machineId = (hash and ((1L shl COSID_MACHINE_BIT) - 1)).toInt()
        val sequence = ((hash ushr COSID_MACHINE_BIT) and ((1L shl COSID_SEQUENCE_BIT) - 1)).toInt()
        val parser = generator.stateParser
        // A friendly (date-formatted) parser depends on the time zone, which may differ between nodes.
        if (parser !is RadixCosIdStateParser) {
            return null
        }
        val id = parser.asString(timestampMillis, machineId, sequence)
        // Parse it back: a parser with narrower machine or sequence fields than the default 20/16 bits drops some of
        // the hash bits, so the state no longer matches and the generator keeps random IDs.
        val roundTrip = runCatching { parser.asState(id) }.getOrNull()
        return id.takeIf { roundTrip == CosIdState(timestampMillis, machineId, sequence) }
    }

    private fun deriveSnowflake(outer: IdGenerator, snowflake: SnowflakeId, timestampMillis: Long, hash: Long): String? {
        val inSeconds = Decorator.getActual(snowflake) is SecondSnowflakeId
        val timestamp = if (inSeconds) TimeUnit.MILLISECONDS.toSeconds(timestampMillis) else timestampMillis
        val diff = timestamp - snowflake.epoch
        if (diff < 0 || diff > snowflake.maxTimestamp) {
            return null
        }
        val machineId = hash and ((1L shl snowflake.machineBit) - 1)
        val sequence = (hash ushr snowflake.machineBit) and ((1L shl snowflake.sequenceBit) - 1)
        val id = (diff shl (snowflake.machineBit + snowflake.sequenceBit)) or
            (machineId shl snowflake.sequenceBit) or
            sequence
        val converter = outer.idConverter()
        if (converter.dependsOnClockOrZone()) {
            return null
        }
        return converter.asString(id)
    }

    /**
     * A converter that formats with the current date ([DatePrefixIdConverter]) or a time zone
     * ([SnowflakeFriendlyIdConverter]) would make the same ID differ between nodes or days: those keep random IDs.
     */
    private fun IdConverter.dependsOnClockOrZone(): Boolean {
        var current: Any? = this
        while (current != null) {
            if (current is DatePrefixIdConverter || current is SnowflakeFriendlyIdConverter) {
                return true
            }
            current = (current as? Decorator<*>)?.actual
        }
        return false
    }

    private fun hash(seed: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest).long
    }
}
