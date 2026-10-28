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

package me.ahoo.wow.viewstore.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.ViewStoreContract
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** What the compiler generated for the view store: its three aggregates, its saga and the Manifest fragment. */
class ViewStoreDomainModelProviderTest {
    private val provider = ModelProvider.load().single()
    private val dependencies = Dependencies.of(SharedBoardReferences { _, _, _ -> emptyList() })

    @Test
    fun `the binding index should build the view store's models and saga`() {
        provider.context.assert().isEqualTo(ViewStoreContext.NAME)
        provider.models(dependencies).map { it.type.toString() }.assert().containsExactlyInAnyOrder(
            "view-store.view",
            "view-store.view_preferences",
            "view-store.dataset_wording",
        )
        provider.processors(dependencies).map { it.name }.assert()
            .containsExactly("ViewAudienceSaga.onAudienceChanged")
    }

    @Test
    fun `the fragment should declare the ownership, the lifecycle and the errors of the view store`() {
        val tree = checkNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment))
            .use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        val context = ManifestTree.read(tree).contexts.single()
        val aggregates = context.aggregates.associateBy { it.name }
        aggregates.getValue(ViewStoreContract.View.NAME).lifecycle.assert()
            .containsExactlyInAnyOrder("wow.lifecycle.DeleteAggregate", "wow.lifecycle.TransferOwner")
        aggregates.getValue(ViewStoreContract.ViewPreferences.NAME).lifecycle.assert().isEmpty()
        aggregates.getValue(ViewStoreContract.DatasetWording.NAME).ownership.name.assert().isEqualTo("TENANT")
        aggregates.getValue(ViewStoreContract.View.NAME).ownership.name.assert().isEqualTo("OWNER")
        context.errors.associate { it.code to it.status }.assert()
            .containsEntry(ViewStoreRejections.INVALID, 400)
            .containsEntry(ViewStoreRejections.NOT_FOUND, 404)
            .containsEntry(ViewStoreRejections.SYSTEM_VIEW_READ_ONLY, 403)
            .containsEntry(ViewStoreRejections.REFERENCED_BY_SHARED_DASHBOARD, 409)
    }
}
