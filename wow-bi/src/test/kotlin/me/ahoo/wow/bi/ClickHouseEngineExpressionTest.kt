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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ClickHouseEngineExpressionTest {
    @Test
    fun `should preserve nested and quoted function arguments`() {
        val expression = "Kafka('broker-a:9092,broker-b:9092', concat('topic,', tuple(1, 2)), 'group', 'JSON')"

        expression.functionArguments("Kafka").assert().containsExactly(
            "'broker-a:9092,broker-b:9092'",
            "concat('topic,', tuple(1, 2))",
            "'group'",
            "'JSON'",
        )
    }

    @Test
    fun `should reject malformed function expressions`() {
        "Kafka('broker', tuple(1, 2)".functionArguments("Kafka").assert().isNull()
        "Kafka('broker']".functionArguments("Kafka").assert().isNull()
        "MergeTree()".functionArguments("Kafka").assert().isNull()
    }

    @Test
    fun `should read escaped setting literals`() {
        "SETTINGS kafka_group_name = 'wow\\'s-group', kafka_format = 'JSONAsString'"
            .settingLiteral("kafka_group_name")
            .assert()
            .isEqualTo("'wow\\'s-group'")
    }
}
