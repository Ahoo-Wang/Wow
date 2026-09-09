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
/** Fixed UTC offsets do not require the runtime's Intl offset-zone support. */
export function fixedTimeZoneOffset(timeZone?: string): number | undefined {
  const parts =
    typeof timeZone === 'string'
      ? /^([+-])([01]\d|2[0-3])(?::?([0-5]\d))?$/.exec(timeZone)
      : null;
  if (!parts || parts[0] !== timeZone) return undefined;
  return (
    (parts[1] === '-' ? -1 : 1) *
    (Number(parts[2]) * 60 + Number(parts[3] ?? 0))
  );
}

export function validateTimeZone(timeZone?: string): void {
  if (timeZone !== undefined && typeof timeZone !== 'string')
    throw new TypeError('时区必须是字符串');
  if (fixedTimeZoneOffset(timeZone) === undefined)
    new Intl.DateTimeFormat('en', { timeZone });
}
