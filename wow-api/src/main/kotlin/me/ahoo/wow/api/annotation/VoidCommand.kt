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

package me.ahoo.wow.api.annotation

import java.lang.annotation.Inherited

/**
 * 标记命令为虚空命令（Void Command）。
 *
 * 使用需要命令需要将命令通过 [AggregateRoot.commands] 挂载到聚合根。
 *
 * 虚空命令的特点是：
 * - 只需要将命令发送到命令总线（Command Bus），而不需要聚合根（Aggregate Root）进行处理。
 * - 通常用于那些不需要返回结果或状态更新的命令操作。
 *
 * 使用场景：
 * - 记录用户的查询操作。
 *
 * @see AggregateRoot
 **/
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class VoidCommand
