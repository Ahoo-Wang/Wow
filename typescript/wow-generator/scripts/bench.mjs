#!/usr/bin/env node
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
 * Times a generation, phase by phase, so a refactor can show what it made
 * faster or slower. It is not run in CI: timings there say more about the
 * runner than about the generator.
 *
 *   pnpm --filter @ahoo-wang/wow-generator build
 *   pnpm --filter @ahoo-wang/wow-generator bench [spec ...] [--json]
 *
 * Without specs it times `test/demo.spec.json` and `test/openai.spec.yml`. Each
 * spec runs in a process of its own, so its peak memory is its own, and
 * generates into a fresh temporary directory whose tsconfig includes only the
 * output. A phase starts at the log line that announces it, whichever method
 * of the logger carries that line, so the phases survive a change to the
 * logger's shape; one no longer announced is left out of the table and its
 * time goes to the phase before it.
 *
 * Load on the machine skews the numbers; the report prints the load average
 * so two reports can be compared fairly.
 */

import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { cpus, loadavg, tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from 'yaml';

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_SPECS = ['test/demo.spec.json', 'test/openai.spec.yml'];

/** The log lines that open each phase, in the order the pipeline runs them. */
const PHASES = [
  ['configuration', /^Reading configuration/],
  ['parse', /^Parsing OpenAPI specification/],
  ['Wow model', /^Resolving bounded context aggregates/],
  ['analysis', /^Analysing the document/],
  ['emit modules', /^Writing generated modules/],
  ['index files', /^Generating index files/],
  ['format, imports, verify', /^Optimizing source files/],
  ['save', /^Saving project to disk/],
];

/** Times one spec in this process and prints the result as JSON. */
async function measure(spec) {
  const entry = join(PACKAGE_ROOT, 'dist/index.js');
  if (!existsSync(entry)) {
    throw new Error(
      `${relative(process.cwd(), entry)} is missing: build the package first.`,
    );
  }
  const { CodeGenerator } = await import(entry);
  const document = parse(readFileSync(spec, 'utf8'));
  const operations = Object.values(document.paths ?? {}).flatMap(item =>
    Object.keys(item).filter(key =>
      [
        'get',
        'put',
        'post',
        'delete',
        'options',
        'head',
        'patch',
        'trace',
      ].includes(key),
    ),
  ).length;

  const dir = mkdtempSync(join(tmpdir(), 'wow-generator-bench-'));
  try {
    const tsConfigFilePath = join(dir, 'tsconfig.json');
    writeFileSync(
      tsConfigFilePath,
      JSON.stringify({
        compilerOptions: {
          target: 'ES2022',
          module: 'ESNext',
          moduleResolution: 'bundler',
          strict: true,
          experimentalDecorators: true,
          skipLibCheck: true,
          noEmit: true,
        },
        include: ['src/**/*'],
      }),
    );
    const marks = [];
    const logger = new Proxy(
      {},
      {
        get: (_, method) =>
          typeof method === 'string' && method !== 'then'
            ? message => {
                const phase = PHASES.find(([, start]) => start.test(message));
                // A phase opens once; later lines that start alike are its detail.
                if (phase && !marks.some(([name]) => name === phase[0]))
                  marks.push([phase[0], performance.now()]);
              }
            : undefined,
      },
    );
    const cpuStart = process.cpuUsage();
    const start = performance.now();
    const generator = new CodeGenerator({
      inputPath: spec,
      outputDir: join(dir, 'src', 'generated'),
      tsConfigFilePath,
      logger,
    });
    marks.unshift(['setup', start]);
    const result = await generator.generate();
    const end = performance.now();
    const cpu = process.cpuUsage(cpuStart);
    const lines = result.files.reduce(
      (sum, file) => sum + readFileSync(file, 'utf8').split('\n').length - 1,
      0,
    );
    return {
      spec: relative(PACKAGE_ROOT, spec),
      schemas: Object.keys(document.components?.schemas ?? {}).length,
      operations,
      files: result.files.length,
      lines,
      warnings: result.warnings,
      wallMs: end - start,
      userCpuMs: cpu.user / 1000,
      systemCpuMs: cpu.system / 1000,
      peakRssMb: process.resourceUsage().maxRSS / 1024,
      phases: marks.map(([name, at], index) => ({
        name,
        ms: (marks[index + 1]?.[1] ?? end) - at,
      })),
    };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const seconds = ms => `${(ms / 1000).toFixed(2)} s`;

function report(results) {
  const [one, five, fifteen] = loadavg().map(load => load.toFixed(2));
  console.log(
    `wow-generator bench — Node ${process.version}, ${process.platform}/${process.arch}, ` +
      `${cpus().length} CPUs, load average ${one} ${five} ${fifteen}`,
  );
  for (const result of results) {
    console.log(
      `\n${result.spec}: ${result.schemas} schemas, ${result.operations} operations → ` +
        `${result.files} files, ${result.lines} lines, ${result.warnings} warnings`,
    );
    console.log(
      `  wall ${seconds(result.wallMs)}, user CPU ${seconds(result.userCpuMs)}, ` +
        `system CPU ${seconds(result.systemCpuMs)}, peak RSS ${Math.round(result.peakRssMb)} MB`,
    );
    const width = Math.max(...result.phases.map(({ name }) => name.length));
    for (const { name, ms } of result.phases) {
      const share = ((ms / result.wallMs) * 100).toFixed(1).padStart(5);
      console.log(
        `  ${name.padEnd(width)}  ${seconds(ms).padStart(10)}  ${share}%`,
      );
    }
  }
}

const args = process.argv.slice(2);
if (args[0] === '--measure') {
  process.stdout.write(JSON.stringify(await measure(resolve(args[1]))));
} else {
  const json = args.includes('--json');
  const specs = args.filter(arg => !arg.startsWith('--'));
  const paths =
    specs.length > 0
      ? specs.map(spec => resolve(spec))
      : DEFAULT_SPECS.map(spec => resolve(PACKAGE_ROOT, spec));
  const results = paths.map(spec =>
    JSON.parse(
      execFileSync(
        process.execPath,
        [fileURLToPath(import.meta.url), '--measure', spec],
        {
          encoding: 'utf8',
          maxBuffer: 16 * 1024 * 1024,
          stdio: ['ignore', 'pipe', 'inherit'],
        },
      ),
    ),
  );
  if (json) console.log(JSON.stringify(results, null, 2));
  else report(results);
}
