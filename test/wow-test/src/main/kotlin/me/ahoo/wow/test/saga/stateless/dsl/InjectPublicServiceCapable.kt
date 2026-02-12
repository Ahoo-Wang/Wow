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

package me.ahoo.wow.test.saga.stateless.dsl

import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.test.dsl.InjectServiceCapable

/**
 * Interface for injecting services into a public service provider.
 *
 * This interface extends [InjectServiceCapable] and provides a dedicated
 * [ServiceProvider] for public services that can be accessed during testing.
 * Services injected here are available to the test environment.
 */
interface InjectPublicServiceCapable : InjectServiceCapable<Unit> {
    /**
     * The public service provider where services are registered.
     */
    val publicServiceProvider: ServiceProvider

    /**
     * Injects services into the public service provider.
     *
     * @param inject A lambda function that configures services on the [ServiceProvider].
     */
    override fun inject(inject: ServiceProvider.() -> Unit) {
        inject(publicServiceProvider)
    }
}
