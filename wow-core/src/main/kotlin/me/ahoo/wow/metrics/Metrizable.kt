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

package me.ahoo.wow.metrics

/**
 * Marker interface indicating that a component has been wrapped with metrics collection capabilities.
 * Components implementing this interface are already decorated with metric decorators and should
 * not be wrapped again to avoid double metrics collection.
 *
 * This interface is used by the Metrics.metrizable() function to determine whether a component
 * already has metrics enabled, preventing redundant decoration.
 */
interface Metrizable
