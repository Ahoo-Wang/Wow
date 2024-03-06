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

package me.ahoo.wow.spring.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.SnapshotQueryServiceFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.FactoryBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

class SnapshotQueryServiceFactoryBean(private val namedAggregate: NamedAggregate) :
    FactoryBean<Any>,
    ApplicationContextAware {
    private lateinit var appContext: BeanFactory
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.appContext = applicationContext
    }

    override fun getObject(): Any {
        val queryServiceFactory: SnapshotQueryServiceFactory =
            appContext.getBean(SnapshotQueryServiceFactory::class.java)
        return queryServiceFactory.create<Any>(namedAggregate)
    }

    override fun getObjectType(): Class<*> {
        return SnapshotQueryService::class.java
    }
}
