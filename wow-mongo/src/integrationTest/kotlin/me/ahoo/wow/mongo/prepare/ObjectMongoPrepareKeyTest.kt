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

package me.ahoo.wow.mongo.prepare

import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.proxy.DefaultPrepareKeyProxyFactory
import me.ahoo.wow.infra.prepare.proxy.prepareKeyMetadata

class ObjectMongoPrepareKeyTest :
    MongoPrepareKeySpec<ObjectMongoPrepareKeyTest.ObjectedValue>() {

    override val name: String = ObjectedValuePrepareKey.NAME
    override val valueType: Class<ObjectedValue>
        get() = ObjectedValue::class.java

    override fun generateValue(): ObjectedValue {
        return ObjectedValue(GlobalIdGenerator.generateAsString())
    }

    override fun createPrepareKey(name: String): PrepareKey<ObjectedValue> {
        val metadata = prepareKeyMetadata<ObjectedValuePrepareKey>()
        return DefaultPrepareKeyProxyFactory(prepareKeyFactory).create(metadata)
    }

    @PreparableKey(name = ObjectedValuePrepareKey.NAME)
    interface ObjectedValuePrepareKey : PrepareKey<ObjectedValue> {
        companion object {
            const val NAME = "object"
        }
    }

    data class ObjectedValue(val value: String, val prepareTime: Long = System.currentTimeMillis())
}
