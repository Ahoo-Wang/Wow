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

import type { ModuleBuilder } from '../../emit/moduleBuilder';
import type { TypeModel } from '../../manifest/model';
import {
  addStatement,
  doc,
  importTypes,
  type TypeFiles,
} from './generatedModule';

export function emitType(
  module: ModuleBuilder,
  type: TypeModel,
  files: TypeFiles,
  outputDir: string,
): void {
  importTypes(module, outputDir, files, type.references, type.file);
  const comment = doc([type.description, `- key: \`${type.key}\``]);
  if (type.properties === undefined) {
    addStatement(module, `${comment}export type ${type.name} = ${type.type};`);
    return;
  }
  const members = type.properties.map(
    property =>
      `${doc([property.description], '  ')}  ${property.name}${property.optional ? '?' : ''}: ${property.type};`,
  );
  if (type.type !== undefined) members.push(`  [key: string]: ${type.type};`);
  addStatement(
    module,
    `${comment}export interface ${type.name} {\n${members.join('\n')}\n}`,
  );
}
