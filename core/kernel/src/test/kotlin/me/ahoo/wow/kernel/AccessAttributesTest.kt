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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.access.AccessAttribute
import org.junit.jupiter.api.Test

/** The access attributes a model derives from its state (`@Access`). */
class AccessAttributesTest {
    enum class Level { LOW, HIGH }

    class Document(val id: String) {
        var department: String? = null
        val sharedWith: MutableList<String> = mutableListOf()
        var level: Level = Level.LOW
        var pages: Long = 0
        var published: Boolean = false
    }

    private val department = AccessAttribute.single("department", String::class.java)
    private val sharedWith = AccessAttribute.multiple("sharedWith", String::class.java)
    private val level = AccessAttribute.single("level", Level::class.java)
    private val pages = AccessAttribute.single("pages", Long::class.javaObjectType)
    private val published = AccessAttribute.single("published", Boolean::class.javaObjectType)

    private val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        access(department) { it.department }
        access(sharedWith) { it.sharedWith }
        access(level) { it.level }
        access(pages) { it.pages }
        access(published) { it.published }
    }

    @Test
    fun `a model derives its access attributes from the state, by name in declaration order`() {
        model.accessAttributes.assert().containsExactly(department, sharedWith, level, pages, published)
        val document = Document("d-1").apply {
            department = "sales"
            sharedWith += listOf("bob", "carol", "bob")
            level = Level.HIGH
            pages = 12
        }
        val access = model.accessOf(document)
        access.keys.assert().containsExactly("department", "sharedWith", "level", "pages", "published")
        access.assert().isEqualTo(
            mapOf(
                "department" to "sales",
                "sharedWith" to setOf("carol", "bob"),
                "level" to Level.HIGH,
                "pages" to 12L,
                "published" to false,
            ),
        )
        // A copy: the state evolving further does not change what was derived.
        document.sharedWith += "dave"
        access["sharedWith"].assert().isEqualTo(setOf("bob", "carol"))
        model.accessOf(Document("d-2"))["department"].assert().isNull()
    }

    @Test
    fun `a model without access attributes derives none`() {
        val plain = aggregate<Document>("office", "note") { initial { Document(it.id) } }
        plain.accessAttributes.assert().isEmpty()
        plain.accessOf(Document("n-1")).assert().isEmpty()
    }

    @Test
    fun `a value of another type than its attribute's fails, naming the attribute`() {
        @Suppress("UNCHECKED_CAST")
        val loose = aggregate<Document>("office", "document") {
            initial { Document(it.id) }
            access(level as AccessAttribute.Single<Any>) { it.department ?: "none" }
            access(sharedWith as AccessAttribute.Multiple<Any>) { listOf(it.pages) }
        }
        assertThrownBy<IllegalStateException> { loose.accessOf(Document("d-1")) }
            .hasMessageContaining("level").hasMessageContaining("java.lang.String")
        @Suppress("UNCHECKED_CAST")
        val notCollection = aggregate<Document>("office", "document") {
            initial { Document(it.id) }
            access(sharedWith as AccessAttribute.Multiple<Any>) { listOf(it.pages) }
        }
        assertThrownBy<IllegalStateException> { notCollection.accessOf(Document("d-1")) }
            .hasMessageContaining("sharedWith")
    }

    @Test
    fun `the names of a model's access attributes differ ignoring case, and none is built in`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<Document>("office", "document") {
                initial { Document(it.id) }
                access(department) { it.department }
                access(department) { it.department }
            }
        }.hasMessageContaining("department twice")
        assertThrownBy<IllegalArgumentException> {
            aggregate<Document>("office", "document") {
                initial { Document(it.id) }
                access(AccessAttribute.single("orgPath", String::class.java)) { it.department }
                access(AccessAttribute.multiple("orgpath", String::class.java)) { it.sharedWith }
            }
        }.hasMessageContaining("differ only in case")
        assertThrownBy<IllegalArgumentException> {
            aggregate<Document>("office", "document") {
                initial { Document(it.id) }
                access(AccessAttribute.OWNER) { it.department }
            }
        }.hasMessageContaining("built-in")
    }
}
