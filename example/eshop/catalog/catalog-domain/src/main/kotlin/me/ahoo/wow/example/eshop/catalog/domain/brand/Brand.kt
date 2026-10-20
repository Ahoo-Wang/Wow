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
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
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
 * Like a product, a brand belongs to its tenant (`Ownership.TENANT`, decision D189): every shopper reads it.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A brand of the catalog's products")
class Brand(private val state: BrandState) {
    @OnCommand(produces = [BrandCreated::class])
    suspend fun onCommand(command: CreateBrand, scope: DecideScope): Decision =
        take(command.name, scope) ?: Decision.events(BrandCreated(command.name.trim()))

    @OnCommand(produces = [BrandRenamed::class])
    suspend fun onCommand(command: RenameBrand, scope: DecideScope): Decision {
        if (command.name.trim() == state.name) return Decision.NoOp
        take(command.name, scope)?.let { return it }
        // A new spelling of the same name keeps its key.
        if (CatalogNames.key(command.name) != CatalogNames.key(state.name)) {
            scope.release(CatalogNames.BRAND_NAMES, CatalogNames.key(state.name))
        }
        return Decision.events(BrandRenamed(command.name.trim()))
    }

    /** Rejects a blank name or one another brand holds; null when this brand may take [name]. */
    private suspend fun take(name: String, scope: DecideScope): Decision? = when {
        name.isBlank() -> Decision.reject(Rejections.BLANK_NAME, "A brand needs a name.")
        !scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name)) ->
            Decision.reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")

        else -> null
    }

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.brand.<code>` (decision D259), as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "A brand's name is never blank.")
        const val BLANK_NAME = "blank-name"

        @ErrorCode(description = "Another brand has this name.", status = 409)
        const val NAME_TAKEN = "name-taken"
    }
}
