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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.tags.Tag
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Tags.toTags
import org.junit.jupiter.api.Test

internal class TagsTest {

    @Test
    fun `should convert single tag annotation to tags`() {
        val tags = SingleTag::class.java.toTags()
        tags.map { it.name }.assert().contains("test")
    }

    @Test
    fun `should convert multiple tag annotations to tags`() {
        val tags = MultiTag::class.java.toTags()
        tags.map { it.name }.assert().contains("test", "test2")
    }

    @Test
    fun `should return empty tags for class without annotations`() {
        val tags = NoTag::class.java.toTags()
        tags.assert().isEmpty()
    }
}

@Tag(name = "test")
@Tag(name = "test2")
private interface MultiTag

@Tag(name = "test")
private interface SingleTag

private interface NoTag
