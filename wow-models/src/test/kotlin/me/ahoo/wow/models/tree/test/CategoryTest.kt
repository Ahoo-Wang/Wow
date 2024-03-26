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

package me.ahoo.wow.models.tree.test

import me.ahoo.wow.models.tree.aggregate.Category
import me.ahoo.wow.models.tree.aggregate.CategoryState
import me.ahoo.wow.models.tree.command.CategoryCreated
import me.ahoo.wow.models.tree.command.CreateCategory
import me.ahoo.wow.test.aggregate.`when`
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

class CategoryTest {
    var data: String = "data"
        private set

    @Test
    fun onCreate() {
        val instance = CategoryTest()
        val property1 = instance.javaClass.kotlin.memberProperties.first() as KMutableProperty1<CategoryTest, String>
//        property1.isAccessible = true
        property1.set(
            instance,
            "hi"
        )
        Category::class.functions.toTypedArray().get(4).parameters.last().type.jvmErasure

        val superClass = Category::class.java.genericSuperclass as ParameterizedType

        aggregateVerifier<Category, CategoryState>()
            .`when`(CreateCategory("1", "1"))
            .expectNoError()
            .expectEventType(CategoryCreated::class.java)
            .verify()
    }


    fun onCreate(hi: String, ho: String) {
        TODO()
    }
}