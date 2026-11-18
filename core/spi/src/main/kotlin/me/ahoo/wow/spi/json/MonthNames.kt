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

import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule
import java.time.Month

/**
 * A [Month] is written by its name, `JANUARY`, as every enumeration is and as 9.x wrote it. Jackson 3 writes it by its
 * number (`DateTimeFeature.ONE_BASED_MONTHS`, on by default), which only a month has. Reading is Jackson's: a name,
 * or a number one-based, as the feature reads it, so the `1` a 10.0 beta wrote reads as `JANUARY`, never `FEBRUARY`.
 */
internal object MonthNames {
    val module: SimpleModule = SimpleModule("wow-month-names").addSerializer(Month::class.java, Writer)

    private object Writer : ValueSerializer<Month>() {
        override fun serialize(value: Month, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeString(value.name)
        }
    }
}
