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

public final class InvocationArguments {
    public static final Object[] EMPTY_ARGS = new Object[0];

    private InvocationArguments() {
    }

    public static Object[] actualArgs(Object[] args) {
        return args == null ? EMPTY_ARGS : args;
    }

    public static Object[] prependReceiver(Object receiver, Object[] args) {
        if (args == null) {
            return new Object[]{receiver};
        }
        Object[] newArgs = new Object[args.length + 1];
        newArgs[0] = receiver;
        System.arraycopy(args, 0, newArgs, 1, args.length);
        return newArgs;
    }

}
