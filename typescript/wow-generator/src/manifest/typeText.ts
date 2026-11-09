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
 * The text of composed types: unions, intersections and literals, grouped as
 * TypeScript's precedence reads them.
 */

import { quoteStringLiteral } from '../naming/naming';

/**
 * Whether a type's text is a union or an intersection at its top level, so
 * it needs parentheses to be one member of another: a `|` or `&` inside an
 * object type, a type argument or a string literal does not count.
 */
export function compound(type: string): boolean {
  let depth = 0;
  for (let index = 0; index < type.length; index++) {
    const char = type[index];
    if (char === "'") {
      // A literal's quotes and escapes, as quoteStringLiteral writes them.
      for (index++; type[index] !== "'"; index++) {
        if (type[index] === '\\') index++;
      }
    } else if ('({[<'.includes(char)) {
      depth++;
    } else if (')}]>'.includes(char)) {
      depth--;
    } else if (
      depth === 0 &&
      (char === '|' || char === '&') &&
      type[index - 1] === ' ' &&
      type[index + 1] === ' '
    ) {
      return true;
    }
  }
  return false;
}

/** A union or an intersection, its members set apart when they are compound. */
export function join(members: string[], separator: ' | ' | ' & '): string {
  const unique = [...new Set(members)];
  if (unique.length === 1) return unique[0];
  return unique
    .map(member => (compound(member) ? `(${member})` : member))
    .join(separator);
}

export function literal(value: unknown): string {
  return typeof value === 'string'
    ? quoteStringLiteral(value)
    : JSON.stringify(value);
}
