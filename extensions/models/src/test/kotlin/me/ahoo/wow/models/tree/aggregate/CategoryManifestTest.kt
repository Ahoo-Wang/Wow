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

package me.ahoo.wow.models.tree.aggregate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.models.tree.CategoryContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** What the compiler made of the library's [Tree] in a domain module: the domain's commands and events. */
class CategoryManifestTest {
    private val provider = ModelProvider.load().single()

    @Test
    fun `the binding index should build the tree's model`() {
        provider.context.assert().isEqualTo(CategoryContext.NAME)
        provider.models(Dependencies.of()).map { it.type.toString() }.assert().containsExactly("category.category")
    }

    @Test
    fun `the fragment should list the domain's commands, events and errors`() {
        val tree = checkNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment))
            .use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        val context = ManifestTree.read(tree).contexts.single()
        val aggregate = context.aggregates.single()
        aggregate.ownership.name.assert().isEqualTo("TENANT")
        aggregate.domainCommands.associate { it.name to it.creation.name }.assert().isEqualTo(
            mapOf(
                "CreateCategory" to "ALLOWED",
                "DeleteCategory" to "NONE",
                "MoveCategory" to "NONE",
                "UpdateCategory" to "NONE",
            ),
        )
        // A Decision does not say which events it produces, and a generic superclass cannot list them.
        aggregate.domainCommands.map { it.produces }.assert().containsOnlyNulls()
        aggregate.events.map { it.name }.assert()
            .containsExactly("CategoryCreated", "CategoryDeleted", "CategoryMoved", "CategoryUpdated")
        context.errors.associate { it.code to it.status }.assert().isEqualTo(
            mapOf(Category.NAME_RESERVED to 409, TreeRejections.PARENT_NOT_FOUND to 404),
        )
    }
}
