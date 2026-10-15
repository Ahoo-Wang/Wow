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

package me.ahoo.wow.example.eshop.catalog.domain.category

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.example.eshop.catalog.api.category.CategoryCreated
import me.ahoo.wow.example.eshop.catalog.api.category.CategoryRenamed
import me.ahoo.wow.example.eshop.catalog.api.category.CreateCategory
import me.ahoo.wow.example.eshop.catalog.api.category.RenameCategory
import me.ahoo.wow.example.eshop.catalog.domain.CatalogNames

/** The codes a category rejects a command with. */
object CategoryRejections {
    @ErrorCode(description = "A category's name is never blank.")
    const val BLANK_NAME = "eshop-catalog.category.blank-name"

    @ErrorCode(description = "Another category has this name.", status = 409)
    const val NAME_TAKEN = "eshop-catalog.category.name-taken"
}

/** The state of a [Category]. */
class CategoryState {
    var name: String = ""
        private set

    fun onSourcing(event: CategoryCreated) {
        name = event.name
    }

    fun onSourcing(event: CategoryRenamed) {
        name = event.name
    }
}

/**
 * A category of the catalog's products (the classic eShop's catalog type). Its name is unique among the categories,
 * ignoring case and surrounding blanks: the category reserves it (`DecideScope.reserve`) as it takes it. A rename releases the old name (`DecideScope.release`)
 * once it commits, so another category may take it then.
 *
 * Like a product, a category belongs to its tenant (`Ownership.TENANT`, decision D189): every shopper reads it.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A category of the catalog's products")
class Category(private val state: CategoryState) {
    @OnCommand(produces = [CategoryCreated::class])
    suspend fun onCommand(command: CreateCategory, scope: DecideScope): Decision =
        take(command.name, scope) ?: Decision.events(CategoryCreated(command.name.trim()))

    @OnCommand(produces = [CategoryRenamed::class])
    suspend fun onCommand(command: RenameCategory, scope: DecideScope): Decision {
        if (command.name.trim() == state.name) return Decision.NoOp
        take(command.name, scope)?.let { return it }
        // A new spelling of the same name keeps its key.
        if (CatalogNames.key(command.name) != CatalogNames.key(state.name)) {
            scope.release(CatalogNames.CATEGORY_NAMES, CatalogNames.key(state.name))
        }
        return Decision.events(CategoryRenamed(command.name.trim()))
    }

    /** Rejects a blank name or one another category holds; null when this category may take [name]. */
    private suspend fun take(name: String, scope: DecideScope): Decision? = when {
        name.isBlank() -> Decision.reject(CategoryRejections.BLANK_NAME, "A category needs a name.")
        !scope.reserve(CatalogNames.CATEGORY_NAMES, CatalogNames.key(name)) ->
            Decision.reject(CategoryRejections.NAME_TAKEN, "Another category is named \"${name.trim()}\".")

        else -> null
    }
}
