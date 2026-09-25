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

import { describe, expect, it } from 'vitest';
import { bindMethods } from '../../src/client/bindMethods';

class Base {
  constructor(readonly name: string) {
    bindMethods(this);
  }

  greet(): string {
    return `base ${this.name}`;
  }

  shout(): string {
    return this.greet().toUpperCase();
  }

  get label(): string {
    return `label ${this.name}`;
  }
}

class Derived extends Base {
  override greet(): string {
    return `derived ${this.name}`;
  }
}

describe('bindMethods', () => {
  it('binds every inherited method to the instance', () => {
    const { greet, shout } = new Base('a');
    expect(greet()).toBe('base a');
    expect(shout()).toBe('BASE A');
  });

  it("binds a subclass's override, which the base's methods reach", () => {
    const { greet, shout } = new Derived('b');
    expect(greet()).toBe('derived b');
    expect(shout()).toBe('DERIVED B');
  });

  it('leaves getters, enumeration and the prototype alone', () => {
    const client = new Derived('c');
    expect(client.label).toBe('label c');
    expect(Object.getOwnPropertyDescriptor(client, 'label')).toBeUndefined();
    expect(Object.keys(client)).toEqual(['name']);
    expect({ ...client }).toEqual({ name: 'c' });
    expect(Object.getOwnPropertyNames(Derived.prototype)).toEqual([
      'constructor',
      'greet',
    ]);
  });
});
