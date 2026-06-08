/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com>].
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

package me.ahoo.wow.infra.invoker;

public interface FunctionInvoker {
    int parameterCount();

    /**
     * Invokes the function with flattened invocation arguments.
     *
     * <p>For receiver-based instance functions, {@code args[0]} must be the receiver, followed by the
     * function parameters.</p>
     *
     * <p>For static functions and constructors, {@code args} contains only the function or constructor
     * parameters and does not include a receiver.</p>
     *
     * @param args flattened invocation arguments
     * @return invocation result
     * @throws Throwable if the invocation fails
     */
    Object invoke(Object[] args) throws Throwable;
}
