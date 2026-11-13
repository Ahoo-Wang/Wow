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

package me.ahoo.wow.postgres

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class PostgresLogAccessColumnTest {
    @Test
    fun `the built-in attributes without a value are left out, and read back as null, first`() {
        val access = linkedMapOf<String, Any?>("owner" to null, "space" to null, "region" to null, "level" to 3L)
        val text = PostgresLogAccessColumn.text(access)
        text.assert().isEqualTo("""{"region":null,"level":3}""")
        val read = checkNotNull(PostgresLogAccessColumn.of(text))
        read.keys.assert().containsExactly("owner", "space", "region", "level")
        read.assert().isEqualTo(access)
    }

    @Test
    fun `built-in attributes with a value are kept where they are`() {
        val both = linkedMapOf<String, Any?>("owner" to "o", "space" to "s", "region" to "east")
        PostgresLogAccessColumn.text(both).assert().isEqualTo("""{"owner":"o","space":"s","region":"east"}""")
        PostgresLogAccessColumn.of(PostgresLogAccessColumn.text(both)).assert().isEqualTo(both)
        val owner = linkedMapOf<String, Any?>("owner" to "o", "space" to null)
        PostgresLogAccessColumn.text(owner).assert().isEqualTo("""{"owner":"o"}""")
        checkNotNull(PostgresLogAccessColumn.of("""{"owner":"o"}""")).keys.assert().containsExactly("owner", "space")
    }

    @Test
    fun `a stream not stamped has no attributes`() {
        PostgresLogAccessColumn.of(null).assert().isNull()
    }
}
