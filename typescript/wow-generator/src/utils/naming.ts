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


const NAMING_SEPARATORS = /[-_\s.]+|(?=[A-Z])/;

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
  let names: string[];
  if (Array.isArray(name)) {
    // If input is an array, split each element by naming separators and flatten the result
    names = name.flatMap(part => part.split(NAMING_SEPARATORS));
  } else {
    // If input is a string, split it by naming separators
    names = name.split(NAMING_SEPARATORS);
  }
  return names
    .filter(part => part.length > 0)
    .map(part => {
      if (part.length === 0) return '';
      const firstChar = part.charAt(0);
      const rest = part.slice(1);
      return (
        (/[a-zA-Z]/.test(firstChar) ? firstChar.toUpperCase() : firstChar) +
        rest.toLowerCase()
      );
    })
    .join('');
}