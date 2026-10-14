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

/** How many writes a store remembers the outcome of, unless told otherwise. */
const REMEMBERED_WRITES = 256;

/** A bounded memory by key: the oldest entry goes first. */
export class Remembered<T> {
  private readonly entries = new Map<string, T>();

  constructor(private readonly limit = REMEMBERED_WRITES) {}

  get(key: string): T | undefined {
    return this.entries.get(key);
  }

  has(key: string): boolean {
    return this.entries.has(key);
  }

  set(key: string, value: T): void {
    this.entries.delete(key);
    this.entries.set(key, value);
    if (this.entries.size > this.limit)
      this.entries.delete(this.entries.keys().next().value!);
  }
}
