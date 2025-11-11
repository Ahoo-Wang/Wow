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
 * Retrieves a nested property value from an object using a dot-separated path or an array of path segments.
 * This function safely navigates through objects and arrays, returning a default value if the property path does not exist or is invalid.
 *
 * @template T - The type of the default value and the expected return type.
 * @param {any} object - The root object or array to retrieve the property from. If null or undefined, returns the defaultValue.
 * @param {string | string[]} propertyName - The property path. Can be a dot-separated string (e.g., "a.b.c") or an array of strings (e.g., ["a", "b", "c"]).
 * @param {T} [defaultValue] - The value to return if the property path does not exist, is invalid, or the object is null/undefined.
 * @returns {T | undefined} The value at the specified property path, or the defaultValue if not found. If no defaultValue is provided and the path is not found, returns undefined.
 *
 * @example
 * // Basic object property access
 * const obj = { user: { name: 'John' } };
 * getPropertyValue(obj, 'user.name'); // Returns 'John'
 *
 * @example
 * // Array index access
 * const arr = [{ name: 'Item1' }, { name: 'Item2' }];
 * getPropertyValue(arr, '1.name'); // Returns 'Item2'
 *
 * @example
 * // Using array path
 * getPropertyValue(obj, ['user', 'name']); // Returns 'John'
 *
 * @example
 * // With default value
 * getPropertyValue(obj, 'user.age', 25); // Returns 25 (since 'age' does not exist)
 *
 * @example
 * // Invalid path or null object
 * getPropertyValue(null, 'any.path'); // Returns undefined
 * getPropertyValue(obj, 'invalid.path', 'default'); // Returns 'default'
 *
 * @note This function does not throw exceptions; it returns the defaultValue for any invalid paths or types.
 * @note For array access, only non-negative integer indices are supported. Invalid indices (e.g., negative numbers, floats, or non-numeric strings) will result in returning the defaultValue.
 */
export function getPropertyValue<T = any>(
  object: any,
  propertyName: string | string[],
  defaultValue?: T,
): T | undefined {
  if (object == null) return defaultValue;

  const pathSegments = Array.isArray(propertyName)
    ? propertyName
    : propertyName.split('.').filter(Boolean);

  if (pathSegments.length === 0) return object;

  let current: any = object;
  for (const segment of pathSegments) {
    if (Array.isArray(current)) {
      const index = parseInt(segment, 10);
      if (isNaN(index) || index < 0 || !Number.isInteger(index)) {
        return defaultValue;
      }
      current = current[index];
    } else if (typeof current === 'object') {
      current = current[segment];
    } else {
      return defaultValue;
    }
    if (current === undefined || current === null) {
      return defaultValue;
    }
  }
  return current;
}
