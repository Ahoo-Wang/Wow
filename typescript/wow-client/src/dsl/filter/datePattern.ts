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

// Internal: not re-exported by filter/index.ts. The pattern syntax of
// `java.time.format.DateTimeFormatter`, as far as `datePattern` is checked
// before it reaches the server.

/** The repeat counts each pattern letter accepts. */
const DATE_PATTERN_COUNTS: Readonly<
  Record<string, number | readonly number[]>
> = {
  G: 5,
  u: 19,
  y: 19,
  Q: 5,
  q: 5,
  M: 5,
  L: 5,
  D: 3,
  d: 2,
  F: 1,
  E: 5,
  e: 5,
  c: [1, 3, 4, 5],
  a: 1,
  B: [1, 4, 5],
  h: 2,
  H: 2,
  k: 2,
  K: 2,
  m: 2,
  s: 2,
  S: 9,
  A: 19,
  n: 19,
  N: 19,
  V: [2],
  v: [1, 4],
  z: 4,
  O: [1, 4],
  X: 5,
  x: 5,
  Z: 5,
  W: 1,
  w: 2,
  Y: Number.POSITIVE_INFINITY,
  g: 19,
};

function validateDatePatternLetter(
  pattern: string,
  letter: string,
  count: number,
): void {
  const allowed = DATE_PATTERN_COUNTS[letter];
  const valid =
    typeof allowed === 'number'
      ? count <= allowed
      : allowed?.includes(count) === true;
  if (!valid) {
    throw new TypeError(`datePattern is invalid: [${pattern}].`);
  }
}

function isNumericDatePatternLetter(
  letter: string | undefined,
  count: number,
): boolean {
  return (
    letter !== undefined &&
    ('uyDFdhHkKmsSgAnNWwY'.includes(letter) ||
      (letter === 'c' && count === 1) ||
      ('eMLQq'.includes(letter) && count <= 2))
  );
}

/**
 * Refuses a blank `datePattern`, and one `DateTimeFormatter.ofPattern` would
 * refuse: an unknown letter or repeat count, an unclosed quote or optional
 * section, a reserved character, or a padded numeric field followed directly
 * by another numeric field.
 */
export function validateDatePattern(pattern: string): void {
  if (typeof pattern !== 'string' || !pattern.trim()) {
    throw new TypeError('datePattern cannot be blank.');
  }
  let quoted = false;
  let optionalDepth = 0;
  for (let index = 0; index < pattern.length; index++) {
    const character = pattern[index];
    if (character === "'") {
      if (pattern[index + 1] === "'") {
        index++;
      } else {
        quoted = !quoted;
      }
      continue;
    }
    if (quoted) continue;
    if (/[A-Za-z]/.test(character)) {
      let letter = character;
      let end = index + 1;
      while (pattern[end] === letter) end++;
      let count = end - index;
      let padded = false;
      if (letter === 'p') {
        padded = true;
        letter = pattern[end];
        if (!letter || !/[A-Za-z]/.test(letter) || letter === 'p') {
          throw new TypeError(`datePattern is invalid: [${pattern}].`);
        }
        const fieldStart = end++;
        while (pattern[end] === letter) end++;
        count = end - fieldStart;
      }
      validateDatePatternLetter(pattern, letter, count);
      if (padded && isNumericDatePatternLetter(letter, count)) {
        const nextLetter = pattern[end];
        let nextEnd = end;
        while (nextEnd < pattern.length && pattern[nextEnd] === nextLetter)
          nextEnd++;
        if (isNumericDatePatternLetter(nextLetter, nextEnd - end)) {
          throw new TypeError(`datePattern is invalid: [${pattern}].`);
        }
      }
      index = end - 1;
    } else if (character === '[') {
      optionalDepth++;
    } else if (character === ']') {
      if (optionalDepth === 0)
        throw new TypeError(`datePattern is invalid: [${pattern}].`);
      optionalDepth--;
    } else if ('{}#'.includes(character)) {
      throw new TypeError(`datePattern is invalid: [${pattern}].`);
    }
  }
  if (quoted) {
    throw new TypeError(`datePattern is invalid: [${pattern}].`);
  }
}
