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

// Run after building: pnpm --filter @ahoo-wang/wow-client test:api [-u]
//
// Holds the declarations each entry ships to its API report under
// `test/api/`: every exported name with its full signature (parameter and
// return types, generic defaults, optional markers, enum members). The names
// alone are held by `test/surface/`; this holds the shapes 9.x freezes.
//
// Without `-u` a report that differs from the build fails, and the new report
// is written to a temporary folder for review. With `-u` the reports are
// rewritten from the build; commit the change on purpose.
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Extractor, ExtractorConfig } from '@microsoft/api-extractor';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const update = process.argv.includes('-u');

/** Entry declaration file (from `package.json` exports) → report name. */
const ENTRIES = {
  'dist/index.d.ts': 'root',
  'dist/dsl.d.ts': 'dsl',
  'dist/legacy/index.d.ts': 'legacy',
};

const tempFolder = mkdtempSync(join(tmpdir(), 'wow-client-api-'));
let failed = false;
try {
  for (const [entry, name] of Object.entries(ENTRIES)) {
    const config = ExtractorConfig.prepare({
      configObject: {
        projectFolder: packageRoot,
        newlineKind: 'lf',
        mainEntryPointFilePath: join(packageRoot, entry),
        compiler: {
          overrideTsconfig: {
            compilerOptions: {
              target: 'ES2020',
              lib: ['ES2020', 'DOM', 'DOM.Iterable'],
              module: 'ESNext',
              moduleResolution: 'bundler',
              strict: true,
              skipLibCheck: true,
              types: [],
            },
            files: [join(packageRoot, entry)],
          },
        },
        apiReport: {
          enabled: true,
          reportFileName: name,
          reportFolder: join(packageRoot, 'test/api'),
          reportTempFolder: tempFolder,
          // A type a signature names but its entry does not export (the
          // Condition request unions, AggregationGroupBase) is frozen with it,
          // so the report spells its shape out too.
          includeForgottenExports: true,
        },
        docModel: { enabled: false },
        dtsRollup: { enabled: false },
        tsdocMetadata: { enabled: false },
        messages: {
          compilerMessageReporting: { default: { logLevel: 'warning' } },
          extractorMessageReporting: {
            default: { logLevel: 'warning' },
            // Release tags (@public, @beta) are not used: every export is public.
            'ae-missing-release-tag': { logLevel: 'none' },
            // Documentation coverage and {@link} targets are not what this
            // report guards.
            'ae-undocumented': { logLevel: 'none' },
            'ae-unresolved-link': { logLevel: 'none' },
            // A type that a signature names but no entry exports stays visible
            // in the report as a warning comment, not a failure.
            'ae-forgotten-export': {
              logLevel: 'none',
              addToApiReportFile: true,
            },
          },
          // The JSDoc is written for IDE hovers, not TSDoc.
          tsdocMessageReporting: { default: { logLevel: 'none' } },
        },
      },
      packageJsonFullPath: join(packageRoot, 'package.json'),
    });
    const result = Extractor.invoke(config, {
      localBuild: update,
      messageCallback: message => {
        message.handled = true;
        // console-* messages narrate the report file; apiReportChanged below
        // decides whether a difference fails.
        if (message.messageId.startsWith('console-')) return;
        if (message.logLevel === 'error' || message.logLevel === 'warning') {
          console.error(`${name}: ${message.formatMessageWithoutLocation()}`);
          failed = true;
        }
      },
    });
    if (result.apiReportChanged && !update) {
      console.error(
        `${name}: the declarations differ from test/api/${name}.api.md. ` +
          `The new report is ${join(tempFolder, `${name}.api.md`)}; ` +
          'if the change is intended, run `pnpm test:api -u` and commit it.',
      );
      failed = true;
    } else if (!result.succeeded) failed = true;
  }
} finally {
  if (!failed) rmSync(tempFolder, { recursive: true, force: true });
}

if (failed) process.exit(1);
console.log(
  `${Object.keys(ENTRIES).length} API reports ${update ? 'written' : 'match the build'}.`,
);
