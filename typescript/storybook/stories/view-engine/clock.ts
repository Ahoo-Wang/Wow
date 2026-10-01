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
 * The browser's clock moved to `at` for one story, for its `beforeEach`;
 * the returned function puts the real one back.
 *
 * The clock still runs — only its starting point moves — so a popup's
 * animation, a debounce or a `waitFor` deadline measured with `Date.now()`
 * behave as they do on the real clock. A date made from parts or from a
 * string is the date it names; only `new Date()` and `Date.now()` read the
 * moved clock. A date made by the real `Date` is still `instanceof Date`.
 *
 * For a calendar that opens on today's month: react-day-picker opens on it
 * unless it is told otherwise, so a story that clicks a day by its number
 * found it only while the wall clock was in that month.
 */
export function clockAt(at: Date): () => void {
  const Real = globalThis.Date;
  const offset = at.getTime() - Real.now();
  class Moved extends Real {
    constructor(...args: [] | ConstructorParameters<DateConstructor>) {
      if (args.length === 0) super(Real.now() + offset);
      else super(...(args as [string | number | Date]));
    }
    static override now(): number {
      return Real.now() + offset;
    }
    static [Symbol.hasInstance](value: unknown): boolean {
      return value instanceof Real;
    }
  }
  globalThis.Date = Moved as DateConstructor;
  return () => {
    globalThis.Date = Real;
  };
}
