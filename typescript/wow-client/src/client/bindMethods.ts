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
 * (internal) Binds every method a client inherits to the client itself, so a
 * method handed on as a function — `execute: client.listState` in a
 * wow-react hook, `ids.map(client.getStateById)` — still reaches its fetcher.
 * The decorated methods read the client's metadata through `this`; unbound,
 * they failed with `Cannot read properties of undefined`.
 *
 * Each bound copy is an own, non-enumerable property, so spreading or
 * serialising a client is unchanged, and the prototype keeps the methods for
 * reflection. A subclass's override is the one bound: the lookup starts at
 * the instance.
 */
export function bindMethods(client: object): void {
  const seen = new Set(Object.getOwnPropertyNames(client));
  for (
    let prototype: object | null = Object.getPrototypeOf(client);
    prototype !== null && prototype !== Object.prototype;
    prototype = Object.getPrototypeOf(prototype)
  ) {
    for (const name of Object.getOwnPropertyNames(prototype)) {
      if (name === 'constructor' || seen.has(name)) continue;
      seen.add(name);
      // The first prototype that names it is the one a lookup finds; a
      // getter is left alone rather than run.
      const method: unknown = Object.getOwnPropertyDescriptor(
        prototype,
        name,
      )?.value;
      if (typeof method !== 'function') continue;
      Object.defineProperty(client, name, {
        value: method.bind(client),
        writable: true,
        configurable: true,
        enumerable: false,
      });
    }
  }
}
