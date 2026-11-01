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
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.junit.jupiter.api.Test

/** The view store's domain names its context with a literal, which must be the view store api's name of it. */
class ViewStoreBoundedContextTest {
    @Test
    fun `the context's literal name should be the api's`() {
        ViewStoreBoundedContext::class.java.getAnnotation(BoundedContext::class.java).name
            .assert().isEqualTo(ViewStoreContext.NAME)
    }
}
