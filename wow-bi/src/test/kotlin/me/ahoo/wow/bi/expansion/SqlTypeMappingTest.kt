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

package me.ahoo.wow.bi.expansion

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.expansion.column.MetadataColumn
import org.junit.jupiter.api.Test

class SqlTypeMappingTest {
    @Test
    fun `should preserve signed numeric values and characters`() {
        SqlTypeMapping[Byte::class.java].assert().isEqualTo("Int8")
        SqlTypeMapping[Byte::class.javaObjectType].assert().isEqualTo("Int8")
        SqlTypeMapping[Short::class.java].assert().isEqualTo("Int16")
        SqlTypeMapping[Short::class.javaObjectType].assert().isEqualTo("Int16")
        SqlTypeMapping[Char::class.java].assert().isEqualTo("String")
        SqlTypeMapping[Char::class.javaObjectType].assert().isEqualTo("String")
    }

    @Test
    fun `should expose correct state metadata types`() {
        MetadataColumn.SPACE_ID_COLUMN.targetName.assert().isEqualTo("__space_id")
        MetadataColumn.SPACE_ID_COLUMN.sqlType.assert().isEqualTo("String")
        MetadataColumn.VERSION_COLUMN.sqlType.assert().isEqualTo("UInt32")
    }
}
