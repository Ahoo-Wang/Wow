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

package me.ahoo.wow.spring.boot.query

import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.spi.config.configCheck
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory

/**
 * Refuses an application's [QueryPolicy] bean before any bean is built. `QueryPolicy` is internal to Wow and the node
 * no longer applies such a bean: one left in place, its compile error silenced by an opt-in, would drop the rule it
 * stood for without a word, and the node would serve the rows it hid. The start fails instead, naming each bean and
 * where its rule goes now.
 */
internal class RetiredQueryPolicies : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val names = beanFactory.getBeanNamesForType(QueryPolicy::class.java, true, false).toList()
        configCheck(names.isEmpty(), QueryPolicy::class.java.name, names, FIX) {
            "${names.joinToString()} ${if (names.size == 1) "is a QueryPolicy bean" else "are QueryPolicy beans"}: " +
                "QueryPolicy is internal to Wow and no longer applied, so the rows such a policy hides would be served."
        }
    }

    internal companion object {
        const val FIX: String = "Move each rule: whether a caller may read a model at all to a ReadAuthorizer bean, " +
            "which rows it reads to an access attribute (@Access) and an AccessPolicy bean."
    }
}
