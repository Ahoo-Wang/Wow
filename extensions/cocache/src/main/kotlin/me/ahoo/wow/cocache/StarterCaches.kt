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

package me.ahoo.wow.cocache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.spring.boot.CacheAssembler
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.util.ClassUtils

/**
 * The caches CoCache's starter creates, when the application has it: its [CacheAssembler] applies
 * `cocache.caches.[<name>].*` and `cocache.defaults.*` to each spec, and knows the caches it checks those settings
 * against. Apart, so a service without the starter never loads its classes.
 */
internal object StarterCaches {
    private const val ASSEMBLER = "me.ahoo.cocache.spring.boot.CacheAssembler"

    /** The starter's cache factory among [beans], null without the starter or its assembler. */
    fun factoryOf(beans: ListableBeanFactory, classLoader: ClassLoader?): StateViewCaches.CacheFactory? {
        if (!ClassUtils.isPresent(ASSEMBLER, classLoader)) return null
        val assembler = beans.getBeanProvider(CacheAssembler::class.java).ifAvailable ?: return null
        return object : StateViewCaches.CacheFactory {
            override fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> = assembler.create(spec)
        }
    }
}
