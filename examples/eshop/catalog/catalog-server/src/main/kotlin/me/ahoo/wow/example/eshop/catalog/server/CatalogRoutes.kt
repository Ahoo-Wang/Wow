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

package me.ahoo.wow.example.eshop.catalog.server

import me.ahoo.wow.example.eshop.catalog.domain.product.Storefront
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The instances of the catalog's service routes, which the routes' generated binding finds among the host's beans. */
@Configuration(proxyBeanMethods = false)
class CatalogRoutes {
    // region storefront
    /** The storefront's service routes: their generated binding calls them on this bean. */
    @Bean
    fun storefront(): Storefront = Storefront()
    // endregion
}
