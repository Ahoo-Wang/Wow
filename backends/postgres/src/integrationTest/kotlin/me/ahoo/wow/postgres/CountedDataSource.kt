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

package me.ahoo.wow.postgres

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * [target] recording the SQL of every statement its connections prepare, so a test can pin how many statements an
 * operation takes (B.D diagnostics), and counting the transactions its connections commit (B.O-5).
 */
class CountedDataSource(target: DataSource) {
    private val prepared = ConcurrentLinkedQueue<String>()
    private val committed = AtomicInteger()

    /** The transactions committed so far with `Connection.commit`, not counting autocommitted statements. */
    val commits: Int get() = committed.get()

    val dataSource: DataSource = proxy(DataSource::class.java, target)

    /** The number of statements prepared so far: a mark for [since]. */
    fun begin(): Int = prepared.size

    /** The SQL of the statements prepared since [mark]. */
    fun since(mark: Int): List<String> = prepared.drop(mark)

    private fun <T : Any> proxy(type: Class<T>, delegate: T): T = type.cast(
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            InvocationHandler { _, method, args ->
                if (method.name == "prepareStatement") {
                    prepared += args?.firstOrNull() as? String ?: ""
                }
                if (method.name == "commit") {
                    committed.incrementAndGet()
                }
                val result = try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
                if (method.name == "getConnection") proxy(Connection::class.java, result as Connection) else result
            },
        ),
    )
}
