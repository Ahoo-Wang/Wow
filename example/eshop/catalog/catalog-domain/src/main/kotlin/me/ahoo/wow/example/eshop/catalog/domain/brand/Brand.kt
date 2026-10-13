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

/** The codes a brand rejects a command with. */
object BrandRejections {
    @ErrorCode(description = "A brand's name is never blank.")
    const val BLANK_NAME = "eshop-catalog.brand.blank-name"

    @ErrorCode(description = "Another brand has this name.", status = 409)
    const val NAME_TAKEN = "eshop-catalog.brand.name-taken"
}

/** The state of a [Brand]. */
class BrandState(val id: String) {
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
 * brand reserves it (`DecideScope.reserve`) as it takes it.
 */
@AggregateRoot
@Description("A brand of the catalog's products")
class Brand(private val state: BrandState) {
    @OnCommand(produces = [BrandCreated::class])
    suspend fun onCommand(command: CreateBrand, scope: DecideScope): Decision =
        take(command.name, scope) ?: Decision.events(BrandCreated(command.name.trim()))

    @OnCommand(produces = [BrandRenamed::class])
    suspend fun onCommand(command: RenameBrand, scope: DecideScope): Decision {
        if (command.name.trim() == state.name) return Decision.NoOp
        return take(command.name, scope) ?: Decision.events(BrandRenamed(command.name.trim()))
    }

    /** Rejects a blank name or one another brand holds; null when this brand may take [name]. */
    private suspend fun take(name: String, scope: DecideScope): Decision? = when {
        name.isBlank() -> Decision.reject(BrandRejections.BLANK_NAME, "A brand needs a name.")
        !scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name)) ->
            Decision.reject(BrandRejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")

        else -> null
    }
}
