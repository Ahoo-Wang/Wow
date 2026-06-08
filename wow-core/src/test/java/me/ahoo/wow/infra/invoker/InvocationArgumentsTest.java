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

package me.ahoo.wow.infra.invoker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class InvocationArgumentsTest {

    @Test
    void actualArgsShouldReturnEmptyArgsWhenNull() {
        Object[] actualArgs = InvocationArguments.actualArgs(null);

        assertThat(actualArgs).isSameAs(InvocationArguments.EMPTY_ARGS);
        assertThat(actualArgs).isEmpty();
    }

    @Test
    void actualArgsShouldReuseProvidedArray() {
        Object[] args = {"wow"};

        Object[] actualArgs = InvocationArguments.actualArgs(args);

        assertThat(actualArgs).isSameAs(args);
        assertThat(actualArgs).containsExactly("wow");
    }

    @Test
    void prependReceiverShouldCreateReceiverOnlyArgsWhenArgsAreNull() {
        Object receiver = new Object();

        Object[] arguments = InvocationArguments.prependReceiver(receiver, null);

        assertThat(arguments).containsExactly(receiver);
    }

    @Test
    void prependReceiverShouldCopyArgsAfterReceiver() {
        Object[] args = {"command", "state"};

        Object[] arguments = InvocationArguments.prependReceiver("receiver", args);

        assertThat(arguments).isNotSameAs(args);
        assertThat(arguments).containsExactly("receiver", "command", "state");
        assertThat(args).containsExactly("command", "state");
    }

    @Test
    void prependReceiverShouldPreserveNullReceiver() {
        Object[] arguments = InvocationArguments.prependReceiver(null, new Object[]{"arg"});

        assertThat(arguments).containsExactly(null, "arg");
    }
}
