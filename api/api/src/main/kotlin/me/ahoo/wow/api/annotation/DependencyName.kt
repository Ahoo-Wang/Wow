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

/**
 * The name of the dependency a parameter takes. On a parameter of a command function or an after hook that takes a
 * domain service, or of a processor's constructor, the module's binding index asks the host's dependencies for the
 * service of the parameter's type named [value], so two parameters of one type can take two services. Without it, the
 * host gives the only service of that type, or its primary one.
 *
 * The command parameter and the decide scope take none.
 *
 * @param value the dependency's name, not blank.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@MustBeDocumented
annotation class DependencyName(
    val value: String,
)
