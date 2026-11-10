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

package me.ahoo.wow.spring.cloud

import me.ahoo.wow.example.eshop.catalog.domain.product.Storefront
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The instance of the eShop catalog's service routes, for a node that serves the catalog's context from its domain
 * module alone: a host that serves a module's routes gives their class, as the catalog's own server does.
 */
@Configuration(proxyBeanMethods = false)
class CatalogStorefront {
    @Bean
    fun storefront(): Storefront = Storefront()
}
