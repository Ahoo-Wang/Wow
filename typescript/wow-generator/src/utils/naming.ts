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

const NAMING_SEPARATORS = /[-_'\s./?;:,()[\]{}|\\]+/;

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

  return `'${name}'`;
}

export function resolveEnumMemberName(name: string): string {
  if (/^\d+$/.test(name)) {
    return `NUM_${name}`;
  }
  return resolvePropertyName(upperSnakeCase(name));
}
