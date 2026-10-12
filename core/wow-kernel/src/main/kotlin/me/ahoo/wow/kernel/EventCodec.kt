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

package me.ahoo.wow.kernel

import me.ahoo.wow.spi.log.EventRecord
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest

/**
 * Turns events and commands into what the log stores, and back. Events are named by the model, never by class name,
 * so renaming or moving a class does not orphan its history.
 */
class EventCodec(private val mapper: JsonMapper = DEFAULT_MAPPER) {
    /** [event] as a record of [model]. */
    fun encode(model: AggregateModel<*>, event: Any): EventRecord {
        val type = model.eventType(event.javaClass)
        return EventRecord(type.name, type.revision, mapper.writeValueAsString(event))
    }

    /**
     * The event [record] holds, read as [model]'s current schema of its type: a record of an older revision goes
     * through the type's upgrades one revision at a time (D22); a record named by a former name is still found.
     */
    fun decode(model: AggregateModel<*>, record: EventRecord): Any {
        val type = model.eventType(record.name)
        check(record.revision <= type.revision) {
            "${record.name} revision ${record.revision} is newer than revision ${type.revision} this model reads."
        }
        if (record.revision == type.revision) {
            return mapper.readValue(record.payload, type.type)
        }
        var payload = mapper.readTree(record.payload)
        for (revision in record.revision until type.revision) {
            payload = type.upgrades.getValue(revision)(payload)
        }
        return mapper.treeToValue(payload, type.type)
    }

    /** [state] as text, to tell whether something changed it. */
    fun stateText(state: Any): String = mapper.writeValueAsString(state)

    /**
     * Identifies a command: its type and a hash of its body. A request id reused by a command with another
     * fingerprint is a different command, not a retry (decision D46).
     */
    fun fingerprint(body: Any): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body))
        return body.javaClass.name + ":" + digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FINGERPRINT_BYTES = 16
        val DEFAULT_MAPPER: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    }
}
