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
import { CommandHeaders, WowHeaders } from '../../src';
import {
  requestHeaderNames,
  responseHeaderNames,
} from '../fixtures/serverContract.js';

function headerValues(target: object, ...prefixes: string[]): string[] {
  return Object.entries(target)
    .filter(([key]) => !prefixes.includes(key))
    .map(([, value]) => value as string)
    .sort();
}

/** The Wow headers the server declares: its own, not standard HTTP ones. */
const serverHeaders = requestHeaderNames
  .filter(
    name =>
      name.startsWith(CommandHeaders.COMMAND_HEADERS_PREFIX) ||
      name.startsWith(WowHeaders.WOW_HEADERS_PREFIX),
  )
  .sort();

describe('header names match the server contract', () => {
  it('reads the headers the server declares', () => {
    expect(serverHeaders).toContain('Command-Wait-Tail-Function');
    expect(serverHeaders).toContain('Wow-Space-Id');
    expect(responseHeaderNames).toContain('Wow-Error-Code');
  });

  it('CommandHeaders sends exactly the request headers the server declares', () => {
    const sent = headerValues(
      CommandHeaders,
      'COMMAND_HEADERS_PREFIX',
      'WAIT_PREFIX',
      'WAIT_TAIL_PREFIX',
      'COMMAND_HEADER_X_PREFIX',
    );
    expect(sent).toEqual(serverHeaders);
  });

  it('WowHeaders names a request header and the error code response header', () => {
    expect(requestHeaderNames).toContain(WowHeaders.SPACE_ID);
    expect(responseHeaderNames).toContain(WowHeaders.ERROR_CODE);
    // The server reads a command's space from the shared Wow- header.
    expect(CommandHeaders.SPACE_ID).toBe(WowHeaders.SPACE_ID);
  });

  it('each prefix is the prefix of declared headers', () => {
    const declaredWith = (prefix: string) =>
      requestHeaderNames.filter(name => name.startsWith(prefix));
    expect(declaredWith(CommandHeaders.COMMAND_HEADERS_PREFIX)).toContain(
      CommandHeaders.COMMAND_TYPE,
    );
    expect(declaredWith(CommandHeaders.WAIT_PREFIX)).toContain(
      CommandHeaders.WAIT_STAGE,
    );
    expect(declaredWith(CommandHeaders.WAIT_TAIL_PREFIX)).toContain(
      CommandHeaders.WAIT_TAIL_STAGE,
    );
    expect(declaredWith(WowHeaders.WOW_HEADERS_PREFIX)).toContain(
      WowHeaders.SPACE_ID,
    );
    // The pass-through prefix names no declared header, so the contract does
    // not carry it; it is a command header like the others.
    expect(CommandHeaders.COMMAND_HEADER_X_PREFIX).toBe(
      `${CommandHeaders.COMMAND_HEADERS_PREFIX}Header-`,
    );
  });
});
