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
 * Anything that is neither a letter, a digit nor `$` separates the words of a
 * name: `-`, `_`, `.`, spaces, and characters no identifier may hold, such as
 * the guillemets of a Springfox `Page«User»`.
 */
const NAMING_SEPARATORS = /[^\p{L}\p{N}$]+/u;

/**
 * Words JavaScript reserves, in strict mode included, which may not name a
 * parameter or a variable.
 */
const RESERVED_WORDS = new Set([
  'arguments',
  'await',
  'break',
  'case',
  'catch',
  'class',
  'const',
  'continue',
  'debugger',
  'default',
  'delete',
  'do',
  'else',
  'enum',
  'eval',
  'export',
  'extends',
  'false',
  'finally',
  'for',
  'function',
  'if',
  'implements',
  'import',
  'in',
  'instanceof',
  'interface',
  'let',
  'new',
  'null',
  'package',
  'private',
  'protected',
  'public',
  'return',
  'static',
  'super',
  'switch',
  'this',
  'throw',
  'true',
  'try',
  'typeof',
  'var',
  'void',
  'while',
  'with',
  'yield',
]);

/**
 * Tells whether a name is a valid identifier as written.
 *
 * @param name - The candidate
 * @returns True when it can name a variable, a parameter or a type
 */
export function isIdentifier(name: string): boolean {
  return (
    /^[\p{ID_Start}$_][\p{ID_Continue}$\u200C\u200D]*$/u.test(name) &&
    !RESERVED_WORDS.has(name)
  );
}

function prefixLeadingDigit(name: string): string {
  return /^\p{N}/u.test(name) ? `_${name}` : name;
}

/**
 * Turns a name from the document into a value identifier: a parameter, a
 * method or a variable.
 *
 * A valid identifier is kept as written. Anything else is camel-cased on its
 * separators (`item-id` → `itemId`), prefixed with `_` when it starts with a
 * digit, and suffixed with `_` when it is a reserved word (`default` →
 * `default_`).
 *
 * @param name - The name the document uses
 * @returns A valid identifier
 */
export function toIdentifier(name: string): string {
  if (isIdentifier(name)) return name;
  const identifier = prefixLeadingDigit(camelCase(name) || '_');
  return RESERVED_WORDS.has(identifier) ? `${identifier}_` : identifier;
}

/**
 * Turns a name from the document into a type identifier: a model, an enum or
 * a class. It is pascal-cased, and prefixed with `_` when it starts with a
 * digit (`1stThing` → `_1stThing`).
 *
 * @param name - A name, or the parts of one
 * @returns A valid type identifier
 */
export function toTypeIdentifier(name: string | string[]): string {
  return prefixLeadingDigit(pascalCase(name) || '_');
}

export function splitName(name: string) {
  return name.split(NAMING_SEPARATORS);
}

/**
 * Splits a name string or array of strings by common naming separators.
 *
 * This function takes a string or array of strings and splits them based on common naming
 * separators including hyphens, underscores, spaces, dots, and before uppercase letters.
 * If an array is provided, each element is split individually and the results are flattened.
 *
 * @param name - A string or array of strings to split by naming separators
 * @returns An array of string parts split by naming separators
 */
export function tokenizeName(name: string | string[]): string[] {
  if (Array.isArray(name)) {
    // If input is an array, split each element by naming separators and flatten the result
    return name.flatMap(part => splitCamelCase(splitName(part)));
  }
  return splitCamelCase(splitName(name));
}

/**
 * Splits camelCase strings properly, keeping consecutive uppercase letters together.
 *
 * @param parts - Array of string parts to process
 * @returns Array of properly split parts
 */
export function splitCamelCase(parts: string[]): string[] {
  return parts.flatMap(part => {
    if (part.length === 0) {
      return [];
    }

    // Split on uppercase letters that follow lowercase letters
    const result: string[] = [];
    let current = '';

    for (let i = 0; i < part.length; i++) {
      const char = part[i];
      const isUpper = /[A-Z]/.test(char);
      const prevIsLower = i > 0 && /[a-z]/.test(part[i - 1]);

      if (isUpper && prevIsLower && current) {
        result.push(current);
        current = char;
      } else {
        current += char;
      }
    }

    if (current) {
      result.push(current);
    }

    return result;
  });
}

/**
 * Converts a string or array of strings to PascalCase format.
 *
 * This function takes a string or array of strings and converts them to PascalCase format
 * by splitting the input based on common naming separators and capitalizing the first
 * letter of each part.
 *
 * @param name - A string or array of strings to convert to PascalCase
 * @returns The PascalCase formatted string
 */
export function pascalCase(name: string | string[]): string {
  if (name === '' || name.length === 0) {
    return '';
  }
  const names: string[] = tokenizeName(name);
  return names
    .filter(part => part.length > 0)
    .map(part => {
      const firstChar = part.charAt(0);
      const rest = part.slice(1);
      return (
        (/[a-zA-Z]/.test(firstChar) ? firstChar.toUpperCase() : firstChar) +
        rest.toLowerCase()
      );
    })
    .join('');
}

/**
 * Converts a string or array of strings to camelCase format.
 *
 * This function first converts the input to PascalCase and then converts the first character to lowercase.
 *
 * @param name - A string or array of strings to convert to camelCase
 * @returns The camelCase formatted string
 */
export function camelCase(name: string | string[]): string {
  const pascalCaseName = pascalCase(name);
  return pascalCaseName.charAt(0).toLowerCase() + pascalCaseName.slice(1);
}

/**
 * Converts a string or array of strings to UPPER_SNAKE_CASE format.
 *
 * This function takes a string or array of strings and converts them to UPPER_SNAKE_CASE format
 * by splitting the input based on common naming separators, converting each part to uppercase,
 * and joining them with underscores. It properly handles consecutive uppercase letters
 * (like acronyms) by treating them as single units.
 *
 * @param name - A string or array of strings to convert to UPPER_SNAKE_CASE
 * @returns The UPPER_SNAKE_CASE formatted string
 */
export function upperSnakeCase(name: string | string[]): string {
  if (name === '' || (Array.isArray(name) && name.length === 0)) {
    return '';
  }

  const names = tokenizeName(name);
  return names
    .filter(part => part.length > 0)
    .map(part => part.toUpperCase())
    .join('_');
}

export function resolvePropertyName(name: string): string {
  if (/^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(name)) {
    return name;
  }

  return quoteStringLiteral(name);
}

/**
 * The member key an enum value prefers, before quoting: its UPPER_SNAKE_CASE
 * form, `NUM_` and the digits for a number.
 */
export function enumMemberKey(name: string): string {
  if (/^\d+$/.test(name)) {
    return `NUM_${name}`;
  }
  return upperSnakeCase(name) || name;
}

/**
 * Renders a value as a single-quoted TypeScript string literal.
 *
 * A property name is whatever the document says it is, so one carrying a
 * quote or a backslash has to be escaped rather than wrapped - `owner'sName`
 * would otherwise close the literal and generate a syntax error.
 *
 * @param value - The string to render
 * @returns The escaped string literal, quotes included
 */
export function quoteStringLiteral(value: string): string {
  // JSON escapes backslashes, double quotes, line breaks and control
  // characters; swap the double-quote escaping for single quotes.
  const escaped = JSON.stringify(value)
    .slice(1, -1)
    .replace(/\\"/g, '"')
    .replace(/'/g, "\\'");
  return `'${escaped}'`;
}
