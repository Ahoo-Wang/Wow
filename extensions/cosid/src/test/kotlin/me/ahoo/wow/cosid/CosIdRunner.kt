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

package me.ahoo.wow.cosid

import me.ahoo.cosid.spring.autoconfigure.CosIdAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * An application that runs CosId as its starter does, from `cosid.*`: a static machine slot (no store), a `default`
 * cosid80 generator with a radix-62 codec, as the guide recommends for the global ids, `order`, a cosid80 of its own,
 * `counter`, a snowflake with decimal text, and `shop.ledger`, a snowflake named after the aggregate `shop.ledger`
 * (a dotted name, in brackets).
 */
val COSID_PROPERTIES: Array<String> = arrayOf(
    "spring.application.name=wow-cosid-test",
    "cosid.machine.static.slot=3",
    "cosid.generators.default.strategy=cosid80",
    "cosid.generators.default.codec[0].radix.radix=62",
    "cosid.generators.order.strategy=cosid80",
    "cosid.generators.order.codec[0].radix.radix=62",
    "cosid.generators.counter.strategy=snowflake",
    "cosid.generators.[shop.ledger].strategy=snowflake",
)

/** [runner] with CosId's auto-configuration and, unless [properties] says otherwise, [COSID_PROPERTIES]. */
fun cosIdRunner(vararg properties: String = COSID_PROPERTIES): ApplicationContextRunner = ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(CosIdAutoConfiguration::class.java))
    .withPropertyValues(*properties)
