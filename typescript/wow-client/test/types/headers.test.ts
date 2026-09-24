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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CommandHeaders, WowHeaders } from '../../src';

/**
 * The server is the source of truth for header spelling. These cases read
 * the `const val` declarations of the Kotlin `Header` objects, so a rename on
 * either side fails here instead of silently dropping the header.
 */
const OPENAPI_SOURCE =
  '../../../../wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/';
const COMMON_COMPONENT = `${OPENAPI_SOURCE}CommonComponent.kt`;
const COMMAND_COMPONENT = `${OPENAPI_SOURCE}aggregate/command/CommandComponent.kt`;

/**
 * Evaluates the string constants of `object Header { ... }` in a Kotlin file,
 * resolving `${NAME}` references to constants declared earlier in it.
 */
function readKotlinHeaders(relativePath: string): Record<string, string> {
  const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
  const body = /object Header \{([\s\S]*?)\n {4}\}/.exec(source)?.[1];
  if (body === undefined) {
    throw new Error(`No "object Header" in ${relativePath}`);
  }
  const constants: Record<string, string> = {};
  for (const [, name, value] of body.matchAll(/const val (\w+) = "([^"]*)"/g)) {
    constants[name] = value.replace(/\$\{(\w+)\}/g, (_, reference: string) => {
      const resolved = constants[reference];
      if (resolved === undefined) {
        throw new Error(`Unresolved \${${reference}} in ${name}`);
      }
      return resolved;
    });
  }
  return constants;
}

function stringConstants(target: object): Record<string, string> {
  return Object.fromEntries(
    Object.entries(target).filter(
      (entry): entry is [string, string] => typeof entry[1] === 'string',
    ),
  );
}

const commonHeaders = readKotlinHeaders(COMMON_COMPONENT);
const commandHeaders = readKotlinHeaders(COMMAND_COMPONENT);

describe('header names match the server', () => {
  it('reads the Kotlin header constants', () => {
    expect(commonHeaders).toMatchObject({
      ERROR_CODE: 'Wow-Error-Code',
      SPACE_ID: 'Wow-Space-Id',
    });
    expect(commandHeaders).toMatchObject({
      COMMAND_HEADERS_PREFIX: 'Command-',
      TENANT_ID: 'Command-Tenant-Id',
      WAIT_TAIL_FUNCTION: 'Command-Wait-Tail-Function',
    });
  });

  it('WowHeaders mirrors CommonComponent.Header', () => {
    const { WOW_HEADERS_PREFIX, ...headers } = stringConstants(WowHeaders);
    expect(WOW_HEADERS_PREFIX).toBe('Wow-');
    expect(headers).toEqual(commonHeaders);
  });

  it('CommandHeaders mirrors CommandComponent.Header', () => {
    const { SPACE_ID, ...headers } = stringConstants(CommandHeaders);
    // The server still accepts the misspelled `Command-Wait-Timout` as a
    // fallback; the client only sends `Command-Wait-Timeout`.
    const { LEGACY_WAIT_TIME_OUT, ...serverHeaders } = commandHeaders;
    expect(LEGACY_WAIT_TIME_OUT).toBe('Command-Wait-Timout');
    expect(headers).toEqual(serverHeaders);
    // The server reads a command's space from the shared Wow- header.
    expect(SPACE_ID).toBe(commonHeaders.SPACE_ID);
  });
});
