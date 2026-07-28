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

package me.ahoo.wow.spring

import me.ahoo.wow.runtime.RuntimeComponent

/**
 * Explicit Spring bean boundary for non-dispatcher components owned by the
 * canonical Wow runtime.
 *
 * Implement this marker instead of exposing an arbitrary runtime lifecycle bean when
 * the component must participate in the runtime readiness barrier, global
 * quiescence, reverse-order cleanup, and force-stop policy.
 *
 * Only components declared in the current Spring application context are
 * collected. Runtime components and message dispatchers share one global
 * Spring ordering sequence; startup follows that order and cleanup reverses it.
 *
 * Bean construction, factory methods, and `@PostConstruct` must not acquire
 * resources that require runtime shutdown. Acquire them from `prepare` or
 * `start`; `forceStop` must remain safe before either method is invoked.
 */
interface WowRuntimeComponent : RuntimeComponent
