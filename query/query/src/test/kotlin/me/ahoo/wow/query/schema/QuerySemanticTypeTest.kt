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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class QuerySemanticTypeTest {
    @Test
    fun `duration and reference keep their facts`() {
        TimeSpan(TimeUnit.SECONDS).timeUnit.assert().isEqualTo(TimeUnit.SECONDS)
        Reference(contextName = "example", aggregateName = "member").assert()
            .isEqualTo(Reference("example", "member"))
        Reference(contextNameField = "contextName", aggregateNameField = "@aggregateName").aggregateNameField.assert()
            .isEqualTo("@aggregateName")
    }

    @Test
    fun `a reference names its aggregate or the sibling fields that hold it, never both`() {
        assertThrows<IllegalArgumentException> { Reference() }
        assertThrows<IllegalArgumentException> { Reference(aggregateName = "member") }
        assertThrows<IllegalArgumentException> { Reference(contextName = "example") }
        assertThrows<IllegalArgumentException> { Reference(contextNameField = "contextName") }
        assertThrows<IllegalArgumentException> { Reference(aggregateNameField = "aggregateName") }
        assertThrows<IllegalArgumentException> {
            Reference("example", "member", "contextName", "aggregateName")
        }
        assertThrows<IllegalArgumentException> { Reference(contextName = "example", aggregateName = "a member") }
        assertThrows<IllegalArgumentException> {
            Reference(contextNameField = "id.contextName", aggregateNameField = "aggregateName")
        }
        Reference(contextName = "example-service", aggregateName = "execution_failed").aggregateName.assert()
            .isEqualTo("execution_failed")
    }

    @Test
    fun `unknown is a semantic type of its own`() {
        QuerySemanticType.Unknown.assert().isNotEqualTo(Temporal.Date)
        QuerySemanticType.Unknown.toString().assert().isEqualTo("Unknown")
    }
}
