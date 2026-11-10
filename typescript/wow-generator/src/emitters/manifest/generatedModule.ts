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

import { join } from 'node:path';
import { escapeJsDoc } from '../../emit/jsdoc';
import type { ModuleBuilder } from '../../emit/moduleBuilder';
import { relativeModuleSpecifier } from '../../emit/imports';

/** The entry of the client every generated module imports from. */
export const WOW_CLIENT = '@ahoo-wang/wow-client';

/** The entry of the service routes' client. */
export const WOW_CLIENT_ROUTES = '@ahoo-wang/wow-client/routes';

/** A doc comment of the lines given, those without text left out. */
export function doc(
  lines: readonly (string | undefined)[],
  indent = '',
): string {
  const text = lines
    .filter((line): line is string => typeof line === 'string' && line !== '')
    .flatMap(line => escapeJsDoc(line).split('\n'));
  if (text.length === 0) return '';
  if (text.length === 1) return `${indent}/** ${text[0]} */\n`;
  return `${indent}/**\n${text.map(line => `${indent} * ${line}`.trimEnd()).join('\n')}\n${indent} */\n`;
}

/**
 * Adds a statement set off from the one before by a blank line, as the
 * structures the other emitters add are.
 */
export function addStatement(module: ModuleBuilder, statement: string): void {
  module.add(`\n${statement}`);
}

export function union(members: readonly string[]): string {
  return members.length === 0 ? 'never' : members.join(' | ');
}

/** Where each generated type is declared, by its name. */
export type TypeFiles = ReadonlyMap<string, string>;

/**
 * Imports the generated types a module names from the files declaring them.
 */
export function importTypes(
  module: ModuleBuilder,
  outputDir: string,
  files: TypeFiles,
  names: readonly string[],
  ownFile?: string,
): void {
  const byFile = new Map<string, string[]>();
  for (const name of new Set(names)) {
    const file = files.get(name);
    if (file === undefined || file === ownFile) continue;
    byFile.set(file, [...(byFile.get(file) ?? []), name]);
  }
  for (const [file, imported] of byFile) {
    module.imports.add(
      relativeModuleSpecifier(module.directoryPath, join(outputDir, file)),
      imported,
    );
  }
}
