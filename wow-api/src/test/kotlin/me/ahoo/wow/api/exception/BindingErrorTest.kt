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

package me.ahoo.wow.api.exception

import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryErrorCodes
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class BindingErrorTest {
    @Test
    fun `published code values are exactly the query error codes`() {
        val codes = QueryErrorCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        val published = BindingError::class.java.getMethod("getCode").getAnnotation(Schema::class.java).allowableValues
        published.toSet().assert().isEqualTo(codes.toSet())
        codes.toSet().assert().hasSize(codes.size)
    }
}
