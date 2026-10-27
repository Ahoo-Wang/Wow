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

/**
 * `@ahoo-wang/wow-client/testing`: what a test of code that talks to a Wow
 * service builds on — a caller of a development run
 * ({@link developmentCaller}, the {@link DevelopmentCallerHeaders} it sends,
 * both of the root entry too), and the service's answers for a fake: {@link commandResultOf},
 * {@link problemOf} and {@link problemResponse}, {@link stateViewOf}.
 *
 * It loads no test framework, so any runner (or a story, or a local tool)
 * imports it.
 */
export {
  type DevelopmentCaller,
  developmentCaller,
  developmentCallerHeaders,
  DevelopmentCallerHeaders,
} from './developmentCaller.js';
export {
  commandResultOf,
  problemOf,
  problemResponse,
  stateViewOf,
} from './testing/wire.js';
