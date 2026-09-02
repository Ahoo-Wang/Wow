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
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuerySchemaValidationModeTest {
    @Test
    fun `validation modes should accept only their documented levels`() {
        QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.EXACT).assert().isTrue()
        QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.COMPATIBLE).assert().isTrue()
        QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.INCOMPATIBLE).assert().isFalse()
        QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.EXACT).assert().isTrue()
        QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.COMPATIBLE).assert().isFalse()
        QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.INCOMPATIBLE).assert().isFalse()
    }

    @Test
    fun `rejected compatibility should use the query schema validation exception contract`() {
        val exception = assertThrows<QuerySchemaValidationException> {
            QuerySchemaResolution(Unit, QueryCompatibilityLevel.COMPATIBLE)
                .requireAccepted(QuerySchemaValidationMode.STRICT)
        }

        exception.errorCode.assert().isEqualTo(QuerySchemaValidationException.ERROR_CODE)
    }
}
