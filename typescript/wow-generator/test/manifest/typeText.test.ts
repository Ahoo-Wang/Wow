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
import { compound, join, literal } from '../../src/manifest/typeText';

describe('the text of composed types', () => {
  it('counts a union or an intersection only at the top level', () => {
    expect(compound('A | B')).toBe(true);
    expect(compound('{ a: string } & { b: string }')).toBe(true);
    expect(compound('{ a: A | B }')).toBe(false);
    expect(compound('Record<string, A | B>')).toBe(false);
    expect(compound('(A | B)[]')).toBe(false);
    expect(compound(literal('a | b'))).toBe(false);
    expect(compound(literal("it's | b"))).toBe(false);
  });

  it('ends at the end of the text, a literal left open included', () => {
    expect(compound("'a | b")).toBe(false);
    expect(compound("A | 'b")).toBe(true);
    expect(compound("'a\\")).toBe(false);
  });

  it('takes no arrow for a closing bracket', () => {
    expect(compound('{ f: () => A } | B')).toBe(true);
    expect(compound('Record<string, () => A> & B')).toBe(true);
    // An arrow's return type reaches to the end: as one member it needs parentheses.
    expect(compound('() => A | B')).toBe(true);
    expect(compound('{ f: () => A | B }')).toBe(false);
  });

  it('groups the compound members of a union or an intersection, once each', () => {
    expect(join(['{ k: 1 } | { k: 2 }', '{ r: number }'], ' & ')).toBe(
      '({ k: 1 } | { k: 2 }) & { r: number }',
    );
    expect(join(['A & B', 'C'], ' | ')).toBe('(A & B) | C');
    expect(join(['A', 'A'], ' | ')).toBe('A');
    expect(literal(3)).toBe('3');
  });
});
