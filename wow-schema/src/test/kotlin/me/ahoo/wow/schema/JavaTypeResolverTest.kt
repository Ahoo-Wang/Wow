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

package me.ahoo.wow.schema

import com.fasterxml.jackson.databind.type.TypeFactory
import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.JavaTypeResolver.toResolvedType
import org.junit.jupiter.api.Test

class JavaTypeResolverTest {
    @Test
    fun stringToResolvedType() {
        val resolvedType = TypeFactory.defaultInstance().constructType(String::class.java)
            .toResolvedType()
        resolvedType.erasedType.assert().isEqualTo(String::class.java)
        resolvedType.typeParameters.assert().isEmpty()
    }

    @Test
    fun listToResolvedType() {
        val resolvedType = TypeFactory.defaultInstance().constructCollectionLikeType(
            List::class.java,
            String::class.java
        ).toResolvedType()
        resolvedType.erasedType.assert().isEqualTo(List::class.java)
        resolvedType.typeParameters.assert().hasSize(1)
    }
}
