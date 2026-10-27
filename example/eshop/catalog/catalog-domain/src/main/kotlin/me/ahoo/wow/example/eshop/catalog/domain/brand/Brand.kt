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

package me.ahoo.wow.example.eshop.catalog.domain.brand

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.example.eshop.catalog.api.brand.BrandCreated
import me.ahoo.wow.example.eshop.catalog.api.brand.BrandRenamed
import me.ahoo.wow.example.eshop.catalog.api.brand.CreateBrand
import me.ahoo.wow.example.eshop.catalog.api.brand.RenameBrand
import me.ahoo.wow.example.eshop.catalog.domain.CatalogNames

/** The state of a [Brand]. */
class BrandState {
    var name: String = ""
        private set

    fun onSourcing(event: BrandCreated) {
        name = event.name
    }

    fun onSourcing(event: BrandRenamed) {
        name = event.name
    }
}

/**
 * A brand of the catalog's products. Its name is unique among the brands, ignoring case and surrounding blanks: the
 * brand reserves it (`DecideScope.reserve`) as it takes it. A rename releases the old name (`DecideScope.release`)
 * once it commits, so another brand may take it then.
 *
 * Like a product, a brand belongs to its tenant (`Ownership.TENANT`): every shopper reads it.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A brand of the catalog's products")
class Brand(private val state: BrandState) {
    suspend fun onCommand(command: CreateBrand, scope: DecideScope): BrandCreated {
        take(command.name, scope)
        return BrandCreated(command.name.trim())
    }

    // region rename
    suspend fun onCommand(command: RenameBrand, scope: DecideScope): BrandRenamed? {
        if (command.name.trim() == state.name) return null
        take(command.name, scope)
        // A new spelling of the same name keeps its key.
        if (CatalogNames.key(command.name) != CatalogNames.key(state.name)) {
            scope.release(CatalogNames.BRAND_NAMES, CatalogNames.key(state.name))
        }
        return BrandRenamed(command.name.trim())
    }
    // endregion

    // region take
    /** Takes [name] for this brand, or rejects the command when another brand holds it. */
    private suspend fun take(name: String, scope: DecideScope) {
        if (!scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
            scope.reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
        }
    }
    // endregion

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.brand.<code>`, as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "Another brand has this name.", status = 409)
        const val NAME_TAKEN = "name-taken"
    }
}
