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

import type { SourceFile } from 'ts-morph';
import { ts } from 'ts-morph';

/**
 * Diagnostics that mean the generated code itself is broken, whatever the
 * project around it resolves:
 *
 * - 2304, 2552, 2503: a name, possibly misspelled, or a namespace that is
 *   neither declared nor imported;
 * - 2300, 2451, 2393: a name declared twice in one scope;
 * - 2308: an index re-exporting the same name from two modules;
 * - 2456: a type alias that references itself.
 *
 * Diagnostics that depend on the modules the output directory resolves -
 * a missing package (2307), a missing export (2305) - are the project's to
 * report, and are left out.
 */
const INTEGRITY_DIAGNOSTICS = new Set([
  2300, 2304, 2308, 2393, 2451, 2456, 2503, 2552,
]);

/**
 * Fails when generated code references a name it does not declare or import,
 * or declares one twice.
 *
 * This is a safety net under the emitters, which write every import
 * explicitly: the output must be the same whether or not its directory
 * resolves `@ahoo-wang/*`, and it must never be saved in a state that cannot
 * compile.
 *
 * @param sourceFiles - The generated files
 * @throws Error listing each diagnostic with its file and line
 */
export function verifyGeneratedCode(sourceFiles: readonly SourceFile[]): void {
  const problems: string[] = [];
  for (const sourceFile of sourceFiles) {
    for (const diagnostic of sourceFile.getPreEmitDiagnostics()) {
      if (!INTEGRITY_DIAGNOSTICS.has(diagnostic.getCode())) continue;
      if (diagnostic.getSourceFile() !== sourceFile) continue;
      const message = ts.flattenDiagnosticMessageText(
        diagnostic.compilerObject.messageText,
        ' ',
      );
      problems.push(
        `${sourceFile.getFilePath()}:${diagnostic.getLineNumber() ?? 0} TS${diagnostic.getCode()} ${message}`,
      );
    }
  }
  if (problems.length > 0) {
    throw new Error(
      `The generated code does not compile; nothing was written. This is a wow-generator bug:\n${problems.join('\n')}`,
    );
  }
}
