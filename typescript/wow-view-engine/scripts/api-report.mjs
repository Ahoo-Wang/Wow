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

// Run after building: pnpm --filter @ahoo-wang/wow-view-engine test:api [-u]
//
// Holds the declarations each entry ships to its API report under
// `test/api/`: every exported name with its full signature (parameter and
// return types, optional markers). The names alone are held by
// `test/surface/`; this holds their shapes.
//
// Without `-u` a report that differs from the build fails, and the new report
// is written to a temporary folder for review. With `-u` the reports are
// rewritten from the build; commit the change on purpose.
//
// The same run writes each entry's doc model (API Extractor's `.api.json`)
// to that temporary folder and renders it into the documentation site's
// symbol index, one English page per entry (`scripts/symbol-index.mjs`):
// a committed page that differs fails as a report does, and `-u` rewrites
// it. A TSDoc summary changed in the source is therefore accepted here too.
//
// Either way, a type a public signature names must be public itself
// (R2-85): exported by that entry or by another entry of the package, so a
// host can name every prop's type it writes a value of, and a vendored
// component's type never reaches a prop, where a `shadcn add --diff` would
// change the API. `FORGOTTEN` names the few that are data rather than a
// type to name, and the report spells their shape out all the same.
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Extractor, ExtractorConfig } from '@microsoft/api-extractor';
import {
  INDEX_PAGES,
  REFERENCE,
  curatedCoverage,
  itemsOf,
  renderIndex,
} from './symbol-index.mjs';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const update = process.argv.includes('-u');

/** Entry declaration file (from `package.json` exports) → report name. */
const ENTRIES = {
  'dist/index.d.ts': 'root',
  'dist/react/index.d.ts': 'react',
  'dist/ui/index.d.ts': 'ui',
  'dist/testing/index.d.ts': 'testing',
  'dist/react-router/index.d.ts': 'react-router',
};

/**
 * The unexported symbols a signature may name, by entry, with why: data a
 * type is read off, not a type a host writes a value of.
 */
const FORGOTTEN = {
  // `AnalysisEditorController` is what `questionEditing` returns, read off
  // it rather than written twice; its members' types are all exported.
  react: ['questionEditing'],
  ui: [
    // `TokenName` and `FveToken` are read off the theme's registry, whose
    // machine form is `dist/theme-tokens.json`; the names are in the report.
    'TOKENS',
    'RoleArea',
    // What a chart hands the image export through `ChartImageSlot`: the
    // engine's own wiring between two of its components, through ECharts'
    // own types, never a value a host builds.
    'ChartCapture',
    'CaptureChart',
    'ChartLibrary',
    'Library',
    'ChartTheme',
    'LegendEntry',
  ],
};

/** Every name an entry of the package exports, off `test/surface/`. */
const PUBLIC = new Set(
  Object.values(ENTRIES).flatMap(name =>
    readFileSync(join(packageRoot, `test/surface/${name}.txt`), 'utf8')
      .split('\n')
      .filter(line => line !== '' && !line.startsWith('#'))
      .map(line => line.split(/\s+/)[1]),
  ),
);

mkdirSync(join(packageRoot, 'test/api'), { recursive: true });
const tempFolder = mkdtempSync(join(tmpdir(), 'wow-view-engine-api-'));
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
              target: 'ES2023',
              lib: ['ES2023', 'DOM', 'DOM.Iterable'],
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
          // The few unexported symbols a signature may name (`FORGOTTEN`)
          // are spelled out, so the report holds their shape too.
          includeForgottenExports: true,
        },
        // Read by the symbol index below, never committed.
        docModel: {
          enabled: true,
          apiJsonFilePath: join(tempFolder, `${name}.api.json`),
        },
        dtsRollup: { enabled: false },
        tsdocMetadata: { enabled: false },
        messages: {
          compilerMessageReporting: { default: { logLevel: 'warning' } },
          extractorMessageReporting: {
            default: { logLevel: 'warning' },
            // Release tags (@public, @beta) are not used: every export is public.
            'ae-missing-release-tag': { logLevel: 'none' },
            // Doc comments are not what this report guards (the summaries
            // are the source's business, read on hover); {@link} targets
            // neither.
            'ae-undocumented': { logLevel: 'none' },
            'ae-unresolved-link': { logLevel: 'none' },
            // A type that a signature names but the entry does not export
            // is checked after invoke() against the other entries and
            // `FORGOTTEN`. The warning stays out of the report, because it
            // names the declaring file and line, and moving a file is not an
            // API change.
            'ae-forgotten-export': {
              logLevel: 'warning',
              addToApiReportFile: false,
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
        if (message.messageId === 'ae-forgotten-export') {
          // API Extractor suffixes a name two entries' symbols share
          // (`DashboardPanel_2`, the model's type beside /ui's component).
          const symbol = /symbol "([^"]+)"/
            .exec(message.text)?.[1]
            ?.replace(/_\d+$/, '');
          if (
            symbol &&
            !PUBLIC.has(symbol) &&
            !FORGOTTEN[name]?.includes(symbol)
          ) {
            console.error(
              `${name}: ${symbol} is named by a public signature but no entry exports it: ` +
                'export it, or write the signature in public types.',
            );
            failed = true;
          }
          return;
        }
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
      // A forgotten export is a warning the callback above has already
      // judged, so only errors decide here.
    } else if (result.errorCount > 0) failed = true;
  }

  // The symbol index, from the doc models just written.
  const coverage = curatedCoverage();
  for (const [name, { page }] of Object.entries(INDEX_PAGES)) {
    const model = join(tempFolder, `${name}.api.json`);
    if (!existsSync(model)) continue;
    const rendered = renderIndex(
      name,
      itemsOf(JSON.parse(readFileSync(model, 'utf8'))),
      coverage,
    );
    const committed = join(REFERENCE, page);
    if (update) writeFileSync(committed, rendered);
    else if (
      !existsSync(committed) ||
      readFileSync(committed, 'utf8') !== rendered
    ) {
      writeFileSync(join(tempFolder, page), rendered);
      console.error(
        `${name}: the symbol index ${committed} differs from the build. ` +
          `The new page is ${join(tempFolder, page)}; ` +
          'if the change is intended, run `pnpm test:api -u` and commit it.',
      );
      failed = true;
    }
  }
} finally {
  if (!failed) rmSync(tempFolder, { recursive: true, force: true });
}

if (failed) process.exit(1);
console.log(
  `${Object.keys(ENTRIES).length} API report(s) and symbol index page(s) ${update ? 'written' : 'match the build'}.`,
);
