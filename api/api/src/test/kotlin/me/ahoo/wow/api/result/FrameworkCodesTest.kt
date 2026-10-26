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

package me.ahoo.wow.api.result

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class FrameworkCodesTest {
    @Test
    fun `the framework codes should be listed once each, in the wow namespace`() {
        // Every code FrameworkCodes declares is in ALL, once: a code added without listing it fails here.
        val declared = FrameworkCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name != "PREFIX" }
            .map { it.get(null) as String }
        declared.assert().doesNotHaveDuplicates()
        FrameworkCodes.ALL.assert().containsExactlyInAnyOrderElementsOf(declared)
        FrameworkCodes.ALL.forEach { it.assert().startsWith(FrameworkCodes.PREFIX).matches("""wow\.[a-z]+\.[a-z-]+""") }
    }
}
