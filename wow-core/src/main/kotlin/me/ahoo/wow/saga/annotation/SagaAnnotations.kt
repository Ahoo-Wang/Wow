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

package me.ahoo.wow.saga.annotation

import java.lang.annotation.Inherited

@Target(AnnotationTarget.CLASS)
@Inherited
annotation class Saga

/**
 * 从订阅的领域事件中解析 `SagaId`.
 * 用于多个聚合的关系维护.
 *
 * @param value 抽取领域事件的字段作为 `SagaId`
 * @param expression 通过字符串表达式解析 `SagaId`
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
annotation class SagaId(val value: String, val expression: String)

/**
 * 使用 StartSaga 订阅的领域事件聚合ID 作为 当前 SagaId
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
annotation class StartSaga

annotation class EndSaga
