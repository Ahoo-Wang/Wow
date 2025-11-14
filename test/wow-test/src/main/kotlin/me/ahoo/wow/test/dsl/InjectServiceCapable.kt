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

package me.ahoo.wow.test.dsl

import me.ahoo.wow.ioc.ServiceProvider

/**
 * Interface for injecting services into a test context and returning a result.
 *
 * Implementations of this interface allow configuring service dependencies
 * for testing scenarios and provide a fluent API for test setup.
 *
 * @param R The return type of the inject operation, typically the test builder itself for chaining.
 * @see ServiceProvider
 */
interface InjectServiceCapable<R> {
    /**
     * Injects services into the test context using the provided configuration block.
     *
     * @param inject A lambda function that configures services on a [ServiceProvider] instance.
     * @return An instance of type [R], typically the test builder for method chaining.
     */
    fun inject(inject: ServiceProvider.() -> Unit): R
}
