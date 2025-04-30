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

package me.ahoo.wow.api

/**
 * 定义复制操作的契约，允许实现该接口的类的对象复制自身
 * 使用泛型参数 SOURCE 来表示源对象的类型，该类型在实现时被具体化
 *
 * @param <SOURCE> 源对象的类型，由实现类具体指定
 */
interface Copyable<out SOURCE> {
    /**
     * 复制当前对象
     * 该方法用于创建并返回当前对象的一个复制品，确保复制品在修改时不会影响到原始对象
     *
     * @return SOURCE 复制后的对象，其类型与源对象相同
     */
    fun copy(): SOURCE
}
