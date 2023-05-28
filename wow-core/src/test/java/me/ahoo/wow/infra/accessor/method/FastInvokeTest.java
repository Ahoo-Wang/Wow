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

package me.ahoo.wow.infra.accessor.method;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class FastInvokeTest {

    static class Ctor {
        private final String id;


        Ctor(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    public String[] varArgsMethod(String... args) {
        return args;
    }

    @Test
    void invoke() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = getClass().getDeclaredMethod("varArgsMethod", String[].class);
        String[] args = {"1", "2", "3"};
        Object[] invokeArgs = {args};
        Object result = FastInvoke.invoke(method, this, invokeArgs);
        assertThat(result, equalTo(args));
    }

    @Test
    void newInstance() throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        Constructor<Ctor> ctor = Ctor.class.getDeclaredConstructor(String.class);
        Ctor instance = FastInvoke.newInstance(ctor, new Object[]{"1"});
        assertThat(instance.getId(), equalTo("1"));
    }
}