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

package me.ahoo.wow.example.eshop.catalog.domain

import java.util.Locale

/**
 * The unique names of the catalog: the namespaces brands and categories reserve their names in, and the key a name is
 * reserved under, so "Contoso" and " contoso " are one name.
 */
object CatalogNames {
    const val BRAND_NAMES = "eshop-catalog.brand-name"
    const val CATEGORY_NAMES = "eshop-catalog.category-name"

    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
}
