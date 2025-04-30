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

package me.ahoo.wow.api.messaging

import me.ahoo.wow.api.Copyable

/**
 * Message Header .
 *
 * @author ahoo wang
 */
/**
 * 接口扩展了MutableMap<String, String>和Copyable<Header>，用于管理[Message]请求或响应的头部信息
 * 它提供了一种链式调用的方式来设置和获取头部字段，同时也支持将头部信息设置为只读状态
 */
interface Header : MutableMap<String, String>, Copyable<Header> {
    /**
     * 表示当前[Header]实例是否为只读状态
     * 当[isReadOnly]为`true`时，[Header]的内容不能被修改
     */
    val isReadOnly: Boolean

    /**
     * 创建一个新的Header实例，其内容与当前实例相同，但设置为只读状态
     * @return 一个新的只读Header实例
     */
    fun withReadOnly(): Header

    /**
     * 在当前Header实例中添加一个键值对，并返回当前实例以支持链式调用
     * @param key 要添加的键
     * @param value 与键关联的值
     * @return 当前Header实例
     */
    fun with(key: String, value: String): Header {
        this[key] = value
        return this
    }

    /**
     * 在当前Header实例中添加一系列键值对，并返回当前实例以支持链式调用
     * @param additional 包含要添加的键值对的Map
     * @return 当前Header实例
     */
    fun with(additional: Map<String, String>): Header {
        putAll(additional)
        return this
    }
}
