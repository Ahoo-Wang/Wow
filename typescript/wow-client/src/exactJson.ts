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

import type { AggregateType } from './wire.js';

/**
 * Which numbers of a JSON value a client reads and writes as their exact
 * text: an `int64`, `bigint` or `decimal` field of a Manifest, which
 * JavaScript's `number` would round. The wire keeps JSON numbers (decision
 * D122); such a field is a `string` in the generated types, its digits
 * exactly as the server wrote them. A generated client carries the marks of
 * its Manifest; a hand-written one writes them in these forms:
 *
 * - `true`: this value is such a number — `{ price: true }`.
 * - Members: the marks of an object's members by name, `'*'` for every item
 *   of an array and every value of a map — `{ lines: { '*': { amount: true } } }`.
 * - Lazy, `() => marks`: marks read only when a value reaches them, for a
 *   recursive type — `const tree: TextNumbers = { amount: true, children:
 *   { '*': () => tree } }`.
 * - {@link textNumberOr}: a number read exactly, or an object or array
 *   marked as given — `textNumberOr({ amount: true })` for a `decimal |
 *   { amount: decimal }`.
 * - {@link textNumbersBy}: the marks of an object chosen by the string value
 *   of one of its members — `textNumbersBy('kind', { decimal: { amount: true
 *   } })` for a union its `kind` tells apart.
 */
export type TextNumbers =
  | true
  | (() => TextNumbers)
  | TextNumbersOr
  | TextNumbersByName
  | { readonly [member: string]: TextNumbers };

/** The key of a {@link TextNumbersOr}: a symbol, so no member name is mistaken for it. */
const OR: unique symbol = Symbol('wow.textNumberOr');

/** The key of a {@link TextNumbersByName}: a symbol, so no member name is mistaken for it. */
const BY: unique symbol = Symbol('wow.textNumbersBy');

/** A number read exactly, or an object or array marked as given: {@link textNumberOr}. */
export interface TextNumbersOr {
  readonly [OR]: TextNumbers;
}

/** The marks of an object chosen by the value of one of its members: {@link textNumbersBy}. */
export interface TextNumbersByName {
  readonly [BY]: {
    readonly discriminant: string;
    readonly cases: Readonly<Record<string, TextNumbers>>;
  };
}

/**
 * The marks of a value that is either an exact number or an object or array
 * marked `marks`: `textNumberOr({ amount: true })` reads `12.50` and
 * `{ "amount": 12.50 }` both as their text.
 */
export function textNumberOr(marks: TextNumbers): TextNumbersOr {
  return { [OR]: marks };
}

/**
 * The marks of an object chosen by the string value of its member
 * `discriminant`: `textNumbersBy('name', { 'shop.cart.ItemAdded': { body:
 * { price: true } } })` reads an event by its name. An object whose
 * discriminant names no case has no exact numbers.
 */
export function textNumbersBy(
  discriminant: string,
  cases: Readonly<Record<string, TextNumbers>>,
): TextNumbersByName {
  return { [BY]: { discriminant, cases } };
}

/** Whether `marks` marks nothing, so plain `JSON.parse` reads the value. */
export function marksNothing(marks: TextNumbers | undefined): boolean {
  if (marks === undefined) return true;
  // A lazy mark is a recursive type's, which the generator writes only for a
  // type that marks something: following it could go round forever.
  if (marks === true || typeof marks === 'function' || OR in marks) {
    return false;
  }
  return Object.values(BY in marks ? marks[BY].cases : marks).every(
    marksNothing,
  );
}

/** A number of a captured object, kept as its text until its marks are known. */
class Captured {
  constructor(readonly text: string) {}
}

/**
 * The marks of a value of one kind, a number or else an object or array:
 * lazy marks read, and the side of a {@link textNumberOr} that kind takes.
 */
function kind(
  marks: TextNumbers | undefined,
  compound: boolean,
): TextNumbers | undefined {
  while (typeof marks === 'function') marks = marks();
  return marks !== undefined && marks !== true && OR in marks
    ? compound
      ? kind(marks[OR], true)
      : true
    : marks;
}

/** The marks of `value` itself: of its kind, and of its case where a member chooses it. */
function own(
  marks: TextNumbers | undefined,
  value: unknown,
): TextNumbers | undefined {
  const object = typeof value === 'object' && value !== null;
  const mine = kind(marks, object);
  if (mine === undefined || mine === true || !(BY in mine)) return mine;
  const { discriminant, cases } = mine[BY];
  const name = object
    ? (value as Record<string, unknown>)[discriminant]
    : undefined;
  return typeof name === 'string' &&
    Object.prototype.hasOwnProperty.call(cases, name)
    ? own(cases[name], value)
    : undefined;
}

/** The marks of a member or item, by name, of a value whose own marks are `marks`. */
function child(
  marks: TextNumbers | undefined,
  name: string,
): TextNumbers | undefined {
  if (marks === undefined || marks === true || typeof marks === 'function') {
    return undefined;
  }
  const members = marks as Readonly<Record<string, TextNumbers>>;
  return Object.prototype.hasOwnProperty.call(members, name)
    ? members[name]
    : Object.prototype.hasOwnProperty.call(members, '*')
      ? members['*']
      : undefined;
}

/** Sets a member as `JSON.parse` does: an own data property, `__proto__` too. */
function define(target: Record<string, unknown>, key: string, value: unknown) {
  if (key === '__proto__') {
    Object.defineProperty(target, key, {
      value,
      writable: true,
      enumerable: true,
      configurable: true,
    });
  } else {
    target[key] = value;
  }
}

/** Resolves the numbers of a captured value against its now known marks. */
function resolve(value: unknown, marks: TextNumbers | undefined): unknown {
  if (value instanceof Captured) {
    return kind(marks, false) === true ? value.text : Number(value.text);
  }
  const mine = own(marks, value);
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index++) {
      value[index] = resolve(value[index], child(mine, String(index)));
    }
  } else if (value !== null && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    for (const key of Object.keys(object)) {
      define(object, key, resolve(object[key], child(mine, key)));
    }
  }
  return value;
}

const Code = {
  TAB: 0x09,
  LF: 0x0a,
  CR: 0x0d,
  SPACE: 0x20,
  QUOTE: 0x22,
  PLUS: 0x2b,
  COMMA: 0x2c,
  MINUS: 0x2d,
  DOT: 0x2e,
  ZERO: 0x30,
  NINE: 0x39,
  COLON: 0x3a,
  UPPER_E: 0x45,
  OPEN_BRACKET: 0x5b,
  BACKSLASH: 0x5c,
  CLOSE_BRACKET: 0x5d,
  LOWER_E: 0x65,
  OPEN_BRACE: 0x7b,
  CLOSE_BRACE: 0x7d,
} as const;

const ESCAPES: Readonly<Record<string, string>> = {
  '"': '"',
  '\\': '\\',
  '/': '/',
  b: '\b',
  f: '\f',
  n: '\n',
  r: '\r',
  t: '\t',
};

/** A JSON text read as `JSON.parse` reads it, but for the numbers `marks` marks. */
class Parser {
  private at = 0;

  constructor(private readonly text: string) {}

  parse(marks: TextNumbers | undefined): unknown {
    this.skipSpaces();
    const value = this.value(marks, false);
    this.skipSpaces();
    if (this.at < this.text.length) this.fail();
    return value;
  }

  /** A value; `capture` keeps its numbers as {@link Captured} text. */
  private value(marks: TextNumbers | undefined, capture: boolean): unknown {
    const code = this.text.charCodeAt(this.at);
    switch (code) {
      case Code.OPEN_BRACE:
      case Code.OPEN_BRACKET: {
        if (capture) return this.compound(code, undefined, true);
        const mine = kind(marks, true);
        // An object marked by a member's value is read whole, then marked.
        return mine !== undefined && mine !== true && BY in mine
          ? resolve(this.compound(code, undefined, true), mine)
          : this.compound(code, mine, false);
      }
      case Code.QUOTE:
        return this.string();
      case 0x74: // t
        return this.literal('true', true);
      case 0x66: // f
        return this.literal('false', false);
      case 0x6e: // n
        return this.literal('null', null);
      default: {
        const text = this.number();
        if (capture) return new Captured(text);
        return kind(marks, false) === true ? text : Number(text);
      }
    }
  }

  /** An object or an array whose own marks are `marks`. */
  private compound(
    open: number,
    marks: TextNumbers | undefined,
    capture: boolean,
  ): unknown {
    const array = open === Code.OPEN_BRACKET;
    const close = array ? Code.CLOSE_BRACKET : Code.CLOSE_BRACE;
    const items: unknown[] = [];
    const object: Record<string, unknown> = {};
    this.at++;
    this.skipSpaces();
    if (this.text.charCodeAt(this.at) === close) {
      this.at++;
      return array ? items : object;
    }
    for (;;) {
      if (array) {
        items.push(this.value(child(marks, '*'), capture));
      } else {
        if (this.text.charCodeAt(this.at) !== Code.QUOTE) this.fail();
        const key = this.string();
        this.skipSpaces();
        this.expect(Code.COLON);
        this.skipSpaces();
        define(object, key, this.value(child(marks, key), capture));
      }
      this.skipSpaces();
      const next = this.text.charCodeAt(this.at++);
      if (next === close) return array ? items : object;
      if (next !== Code.COMMA) this.fail(this.at - 1);
      this.skipSpaces();
    }
  }

  private string(): string {
    const text = this.text;
    let start = ++this.at;
    let value = '';
    for (;;) {
      const code = text.charCodeAt(this.at);
      if (code === Code.QUOTE) {
        value += text.slice(start, this.at++);
        return value;
      }
      if (code === Code.BACKSLASH) {
        value += text.slice(start, this.at);
        const escape = text[this.at + 1];
        if (escape === 'u') {
          const hex = text.slice(this.at + 2, this.at + 6);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) this.fail(this.at);
          value += String.fromCharCode(Number.parseInt(hex, 16));
          this.at += 6;
        } else {
          const unescaped = escape === undefined ? undefined : ESCAPES[escape];
          if (unescaped === undefined) this.fail(this.at);
          value += unescaped;
          this.at += 2;
        }
        start = this.at;
      } else if (code < Code.SPACE || Number.isNaN(code)) {
        this.fail();
      } else {
        this.at++;
      }
    }
  }

  /** The text of a number, checked against JSON's grammar. */
  private number(): string {
    const text = this.text;
    const start = this.at;
    if (text.charCodeAt(this.at) === Code.MINUS) this.at++;
    const first = text.charCodeAt(this.at);
    if (first === Code.ZERO) {
      this.at++;
    } else if (first > Code.ZERO && first <= Code.NINE) {
      this.digits();
    } else {
      this.fail();
    }
    if (text.charCodeAt(this.at) === Code.DOT) {
      this.at++;
      if (!this.digits()) this.fail();
    }
    const exponent = text.charCodeAt(this.at);
    if (exponent === Code.LOWER_E || exponent === Code.UPPER_E) {
      this.at++;
      const sign = text.charCodeAt(this.at);
      if (sign === Code.PLUS || sign === Code.MINUS) this.at++;
      if (!this.digits()) this.fail();
    }
    return text.slice(start, this.at);
  }

  /** Skips digits; whether there was one. */
  private digits(): boolean {
    const start = this.at;
    let code = this.text.charCodeAt(this.at);
    while (code >= Code.ZERO && code <= Code.NINE) {
      code = this.text.charCodeAt(++this.at);
    }
    return this.at > start;
  }

  private literal<T>(word: string, value: T): T {
    if (!this.text.startsWith(word, this.at)) this.fail();
    this.at += word.length;
    return value;
  }

  private skipSpaces(): void {
    let code = this.text.charCodeAt(this.at);
    while (
      code === Code.SPACE ||
      code === Code.LF ||
      code === Code.CR ||
      code === Code.TAB
    ) {
      code = this.text.charCodeAt(++this.at);
    }
  }

  private expect(code: number): void {
    if (this.text.charCodeAt(this.at) !== code) this.fail();
    this.at++;
  }

  private fail(at: number = this.at): never {
    throw new SyntaxError(
      at >= this.text.length
        ? 'Unexpected end of JSON input'
        : `Unexpected token in JSON at position ${at}`,
    );
  }
}

/**
 * `text` read as `JSON.parse` reads it, but each number `marks` marks comes
 * back as its exact text, a string. Without marks it is
 * `JSON.parse` itself. Malformed text throws a `SyntaxError`.
 */
export function parseJson(text: string, marks?: TextNumbers): unknown {
  if (marksNothing(marks)) return JSON.parse(text);
  return new Parser(text).parse(marks);
}

/** JSON's number grammar. */
const JSON_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;

/**
 * `value` written as `JSON.stringify` writes it, but each string `marks`
 * marks goes on the wire as the JSON number it spells, unquoted: `{"price":
 * "19.99"}` marked `{price: true}` is `{"price":19.99}`.
 * A marked string that is not a JSON number is a `TypeError`.
 */
export function stringifyJson(value: unknown, marks?: TextNumbers): string {
  if (marksNothing(marks)) return JSON.stringify(value);
  const numbers: string[] = [];
  // A NUL and a fresh nonce: no string of the value is the placeholder.
  const prefix = `\u0000wow-number-${Math.random().toString(36).slice(2)}:`;
  const replaced = replace(value, marks, numbers, prefix);
  const text = JSON.stringify(replaced);
  if (numbers.length === 0) return text;
  const placeholder = new RegExp(
    `"${JSON.stringify(prefix).slice(1, -1).replace(/\\/g, '\\\\')}(\\d+)"`,
    'g',
  );
  return text.replace(
    placeholder,
    (_, index: string) => numbers[Number(index)]!,
  );
}

/**
 * A copy of `value` along the marked paths, each marked string replaced by a
 * placeholder of its number; everything else shared.
 */
function replace(
  value: unknown,
  marks: TextNumbers | undefined,
  numbers: string[],
  prefix: string,
): unknown {
  const mine = own(marks, value);
  if (mine === undefined || value === null || value === undefined) {
    return value;
  }
  if (mine === true) {
    if (typeof value !== 'string') return value;
    if (!JSON_NUMBER.test(value)) {
      throw new TypeError(`Not a JSON number: [${value}].`);
    }
    numbers.push(value);
    return `${prefix}${numbers.length - 1}`;
  }
  if (Array.isArray(value)) {
    return value.map((item, index) =>
      replace(item, child(mine, String(index)), numbers, prefix),
    );
  }
  if (typeof value !== 'object') return value;
  const object = value as Record<string, unknown>;
  // `JSON.stringify` writes what `toJSON` returns (a `Date`, say): not a path.
  if (typeof object.toJSON === 'function') return value;
  const copy: Record<string, unknown> = {};
  for (const key of Object.keys(object)) {
    define(copy, key, replace(object[key], child(mine, key), numbers, prefix));
  }
  return copy;
}

/**
 * The marks of a record's `members` that mark something, by member, or
 * `undefined` when none does.
 */
export function recordNumbers(
  members: Readonly<Record<string, TextNumbers | undefined>>,
): TextNumbers | undefined {
  const marked = Object.entries(members).filter(
    (entry): entry is [string, TextNumbers] => entry[1] !== undefined,
  );
  return marked.length === 0 ? undefined : Object.fromEntries(marked);
}

/**
 * The exact numbers ({@link TextNumbers}) of an aggregate type's events: each
 * event's `body` by its `name`.
 */
export function eventNumbers(type: AggregateType): TextNumbers | undefined {
  const events = type.textNumbers?.events;
  return events === undefined
    ? undefined
    : textNumbersBy(
        'name',
        Object.fromEntries(
          Object.entries(events).map(([name, body]) => [name, { body }]),
        ),
      );
}
