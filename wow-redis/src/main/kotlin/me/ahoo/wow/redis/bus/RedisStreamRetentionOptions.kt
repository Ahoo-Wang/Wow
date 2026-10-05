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

package me.ahoo.wow.redis.bus

import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.connection.stream.RecordId
import java.time.Duration

/**
 * What the Redis stream bus keeps: stream entries and consumers.
 *
 * **Entries.** Trimming is off by default: an entry stays until it is deleted by hand, so a consumer group that falls
 * behind, or a new group that replays from the start, still finds it. Set at most one of:
 * - [maxLength]: every `XADD` trims the stream to about this many entries (`MAXLEN ~`);
 * - [maxAge]: every `XADD` trims entries older than this (`MINID ~`, from the sender's clock). It must be at least
 *   [MIN_MAX_AGE] and far larger than the clock skew between senders: a sender whose clock runs ahead trims younger
 *   entries.
 * Trimming applies to every consumer group: an entry a lagging group has not read yet is lost to it, so size the
 * limit to the slowest group's worst lag. With [approximate] (the default) Redis trims whole macro nodes, which is
 * much cheaper; the stream may hold a few more entries than the limit. Trimming needs Redis 7.0 or later: it can remove
 * entries still pending for a consumer, and before 7.0 claiming such an entry (`XCLAIM`) keeps failing.
 *
 * **Consumers.** Each subscription start joins its group under a new consumer name, so a restarted node leaves its old
 * consumer behind. When a receiver starts, it deletes (`XGROUP DELCONSUMER`) the consumers of its group that have no
 * pending entries and have been idle for at least [consumerIdleTimeout]: deleting a consumer with nothing pending loses
 * nothing, and a live consumer that is deleted is re-created by its next read. `null` keeps every consumer.
 */
data class RedisStreamRetentionOptions(
    val maxLength: Long? = null,
    val maxAge: Duration? = null,
    val approximate: Boolean = true,
    val consumerIdleTimeout: Duration? = DEFAULT_CONSUMER_IDLE_TIMEOUT,
) {
    init {
        require(maxLength == null || maxLength > 0) {
            "maxLength must be positive."
        }
        require(maxAge == null || maxAge >= MIN_MAX_AGE) {
            "maxAge must be at least $MIN_MAX_AGE: MINID uses the sender's clock, so the age must dwarf clock skew."
        }
        require(maxLength == null || maxAge == null) {
            "Set at most one of maxLength and maxAge: Redis trims a stream by one strategy."
        }
        require(consumerIdleTimeout == null || consumerIdleTimeout >= MIN_DURATION) {
            "consumerIdleTimeout must be at least 1 millisecond."
        }
    }

    /** Whether `XADD` trims the stream. */
    val trims: Boolean
        get() = maxLength != null || maxAge != null

    /** The `XADD` options for an entry added at [nowMillis]. */
    internal fun addOptions(nowMillis: Long): XAddOptions {
        val trim = when {
            maxLength != null -> TrimOptions.maxLen(maxLength)
            maxAge != null -> TrimOptions.minId(RecordId.of(nowMillis - maxAge.toMillis(), 0))
            else -> return XAddOptions.none()
        }
        return XAddOptions.trim(if (approximate) trim.approximate() else trim.exact())
    }

    companion object {
        private val MIN_DURATION = Duration.ofMillis(1)

        /** The shortest [maxAge]: `MINID` comes from the sender's clock, so a shorter age is mostly clock skew. */
        @JvmField
        val MIN_MAX_AGE: Duration = Duration.ofMinutes(1)

        @JvmField
        val DEFAULT_CONSUMER_IDLE_TIMEOUT: Duration = Duration.ofMinutes(30)

        /** No trimming; consumers idle for [DEFAULT_CONSUMER_IDLE_TIMEOUT] with nothing pending are deleted. */
        @JvmField
        val DEFAULT: RedisStreamRetentionOptions = RedisStreamRetentionOptions()
    }
}
