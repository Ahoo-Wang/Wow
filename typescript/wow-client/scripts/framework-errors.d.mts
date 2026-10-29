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

/** The error catalog's snapshot the script reads; see the `.mjs`. */
export interface FrameworkErrorsCatalog {
  retry: { attempts: number; backoffMillis: number; maxWaitSeconds: number };
  errors: {
    name: string;
    code: string;
    status: number;
    retry: 'none' | 'same-request' | 'new-request';
    retryAfter?: number;
    title: string;
    description?: string;
  }[];
}

/** The catalog's snapshot: api/manifest/src/test/resources/framework-errors.json. */
export const CATALOG: string;

/** The file it writes: src/frameworkErrors.ts. */
export const OUTPUT: string;

/** The source of src/frameworkErrors.ts for `catalog`, formatted. */
export function frameworkErrors(
  catalog: FrameworkErrorsCatalog,
): Promise<string>;
