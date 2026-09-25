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

import type { Named } from '@ahoo-wang/wow-client';

/**
 * Where a generated declaration lives: its name, and the directory under the
 * output directory whose `types.ts` declares it, or the package it is
 * imported from (a path starting with `@`).
 */
export interface ModelInfo extends Named {
  name: string;
  path: string;
}
