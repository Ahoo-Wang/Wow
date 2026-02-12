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

package me.ahoo.wow.infra

/**
 * Interface for the Decorator design pattern implementation in the Wow framework.
 * Decorators wrap objects to add additional functionality while maintaining the same interface.
 * This interface provides a standard way to access the wrapped (decorated) object.
 *
 * @param C the type of the component being decorated
 */
interface Decorator<out C : Any> {
    /**
     * The original component being decorated.
     * This provides access to the wrapped object for delegation or inspection.
     */
    val delegate: C

    companion object {
        /**
         * Recursively unwraps decorators to find the original (non-decorated) component.
         * This method traverses the decorator chain until it finds an object that is not a decorator.
         *
         * @param C the type of the component
         * @return the original non-decorated component
         *
         * Example usage:
         * ```
         * val decoratedComponent = SomeDecorator(SomeDecorator(originalComponent))
         * val original = decoratedComponent.getOriginalDelegate() // returns originalComponent
         * ```
         */
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun <C : Any> C.getOriginalDelegate(): C {
            if (this is Decorator<*>) {
                return delegate.getOriginalDelegate() as C
            }
            return this
        }
    }
}
