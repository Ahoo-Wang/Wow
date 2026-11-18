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

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.module.SimpleModule
import kotlin.time.Duration

/**
 * A [Duration] is ISO-8601 text, `PT1H30M`, as a `java.time.Duration` is: written through the Kotlin module's
 * conversion (`KotlinFeature.UseJavaDurationConversion`), as a property, a nullable one, an element or a map's value,
 * and read here. A number is refused: a 10.0 beta wrote Kotlin's internal encoding, which no unit reads, and the
 * conversion would read it as seconds. A `java.time.Duration` still reads a number as seconds, as Jackson does.
 */
internal object KotlinDurations {
    val module: SimpleModule = SimpleModule("wow-kotlin-durations").addDeserializer(Duration::class.java, Reader)

    private object Reader : ValueDeserializer<Duration>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Duration {
            if (p.hasToken(JsonToken.VALUE_STRING)) {
                val text = p.string.trim()
                return Duration.parseIsoStringOrNull(text) ?: ctxt.handleWeirdStringValue(
                    Duration::class.java,
                    text,
                    "a kotlin.time.Duration is ISO-8601 text, such as PT1H30M",
                ) as Duration
            }
            return ctxt.reportInputMismatch(
                this,
                "A kotlin.time.Duration is written as ISO-8601 text, such as PT1H30M, not as %s",
                p.currentToken(),
            )
        }
    }
}
