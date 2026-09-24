/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { ROOT, readProjectVersion } from './project-version.mjs';
import {
  HELD_BACK,
  PUBLISHED,
  pack,
  publishPlan,
  tarballName,
} from './publish-npm.mjs';

// Checks the npm packages as a consumer gets them: the tarballs, not the
// workspace. Run after `pnpm build:typescript`:
//
//   node .github/scripts/package-check.mjs [--tarballs <dir>]
//
// Without --tarballs it packs the PUBLISHED packages itself. It checks:
//
// 1. The manifests: peer ranges come from the `peers` catalog or the
//    workspace, and `engines.node` matches the workspace root.
// 2. publint (strict) on each tarball.
// 3. A fresh npm project installs the tarballs (npm adds the peers), then:
//    - ES module import and CommonJS require of every entry point; wow-react
//      is ESM only and is required through Node's require(esm);
//    - the `wow-generator` and `fetcher-generator` bins print the version;
//    - LICENSE and README.md are in every package;
//    - TypeScript (the workspace's) compiles consumers under node16, nodenext
//      and bundler resolution. Each consumer holds `@ts-expect-error` misuses,
//      so types that silently degrade to `any` fail the check too.

/** Runtime entry points and a value each must export. */
const ENTRIES = [
  ['@ahoo-wang/wow-client', 'filter'],
  ['@ahoo-wang/wow-client/legacy', 'zh_CN'],
  ['@ahoo-wang/wow-react', 'useFetcherPagedQuery'],
  ['@ahoo-wang/wow-generator', 'CodeGenerator'],
];
const BINS = ['wow-generator', 'fetcher-generator'];

/** Consumers compiled under each resolution mode. */
const CLIENT_AND_GENERATOR = `import { filter } from '@ahoo-wang/wow-client';
import { zh_CN } from '@ahoo-wang/wow-client/legacy';
import { CodeGenerator } from '@ahoo-wang/wow-generator';

// A condition is not a number.
// @ts-expect-error the client's types must not be any
export const condition: number = filter.eq('status', 'PAID');
// @ts-expect-error the generator's constructor takes options
export const generator = new CodeGenerator(42);
export const locale: string = zh_CN.AND;
`;
const REACT = `import { useFetcherPagedQuery } from '@ahoo-wang/wow-react';

// @ts-expect-error the hook takes options, not a number
export const query = () => useFetcherPagedQuery(42);
`;
const CONSUMERS = {
  'client.mts': CLIENT_AND_GENERATOR,
  'client.cts': CLIENT_AND_GENERATOR,
  'react.mts': REACT,
  // ESM only: CommonJS reaches it through require(esm), which TypeScript
  // models under nodenext but not node16.
  'react.cts': REACT,
  'client.ts': CLIENT_AND_GENERATOR,
  'react.ts': REACT,
};
const MODES = {
  node16: {
    compilerOptions: { module: 'node16', moduleResolution: 'node16' },
    files: ['client.mts', 'client.cts', 'react.mts'],
  },
  nodenext: {
    compilerOptions: { module: 'nodenext', moduleResolution: 'nodenext' },
    files: ['client.mts', 'client.cts', 'react.mts', 'react.cts'],
  },
  bundler: {
    compilerOptions: { module: 'esnext', moduleResolution: 'bundler' },
    files: ['client.ts', 'react.ts'],
  },
};

/** Problems in the workspace manifests of the public packages. */
export function manifestProblems(manifests, rootEngines) {
  const problems = [];
  for (const [dir, manifest] of Object.entries(manifests)) {
    for (const [name, range] of Object.entries(manifest.peerDependencies ?? {}))
      if (range !== 'catalog:peers' && !range.startsWith('workspace:'))
        problems.push(
          `${dir}: peer ${name} is ${range}; use catalog:peers (or workspace:~)`,
        );
    if (manifest.engines?.node !== rootEngines)
      problems.push(
        `${dir}: engines.node is ${manifest.engines?.node}; the workspace requires ${rootEngines}`,
      );
  }
  return problems;
}

/**
 * Splits tsc output into our diagnostics and fetcher's. The check runs without
 * skipLibCheck so it sees errors in our declarations; errors inside fetcher's
 * declarations, or about importing them, are fetcher's to fix (its packages
 * are peers from npm) and are reported without failing the check.
 */
export function typeDiagnostics(output) {
  const diagnostics = [];
  for (const line of output.split('\n'))
    if (/^\S.*\(\d+,\d+\): error TS\d+:/.test(line)) diagnostics.push(line);
    else if (line.trim() && diagnostics.length > 0)
      diagnostics[diagnostics.length - 1] += `\n${line}`;
  const upstream = diagnostics.filter(
    diagnostic =>
      diagnostic.startsWith('node_modules/@ahoo-wang/fetcher') ||
      /["'(]@ahoo-wang\/fetcher[\w-]*["')]/.test(diagnostic),
  );
  return {
    ours: diagnostics.filter(diagnostic => !upstream.includes(diagnostic)),
    upstream,
  };
}

/** Catalog versions the consumer project installs next to the tarballs. */
function catalogVersion(name) {
  const yaml = readFileSync(join(ROOT, 'pnpm-workspace.yaml'), 'utf8');
  const line = new RegExp(
    `^  '?${name.replace(/[/@.]/g, '\\$&')}'?: (.+)$`,
    'm',
  ).exec(yaml.slice(yaml.indexOf('\ncatalog:')));
  if (!line) throw new Error(`${name} is not in the default catalog`);
  return line[1].replace(/^'|'$/g, '');
}

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    ...options,
  });
}

function fail(problems) {
  if (problems.length === 0) return;
  throw new Error(`package check failed:\n  ${problems.join('\n  ')}`);
}

function checkManifests() {
  const manifest = dir =>
    JSON.parse(readFileSync(join(ROOT, dir, 'package.json'), 'utf8'));
  fail(
    manifestProblems(
      Object.fromEntries(
        [...PUBLISHED, ...HELD_BACK].map(dir => [dir, manifest(dir)]),
      ),
      manifest('.').engines.node,
    ),
  );
  console.log('manifests: peers from catalog:peers, engines match the root');
}

async function checkPublint(tarballs) {
  // Imported here, not at the top: quality runs the CI script tests, which
  // import this module, before it installs dependencies.
  const { publint } = await import('publint');
  const { formatMessage } = await import('publint/utils');
  const problems = [];
  for (const tarball of tarballs) {
    const { messages, pkg } = await publint({
      // A copy: a small Buffer may be a view into a larger shared pool.
      pack: { tarball: new Uint8Array(readFileSync(tarball)).buffer },
      level: 'suggestion',
      strict: true,
    });
    for (const message of messages)
      problems.push(
        `${pkg.name} (publint ${message.type}): ${formatMessage(message, pkg, { color: false })}`,
      );
  }
  fail(problems);
  console.log('publint: no messages');
}

function checkConsumer(plan, tarballs, version) {
  const project = mkdtempSync(join(tmpdir(), 'wow-package-check-'));
  try {
    writeFileSync(
      join(project, 'package.json'),
      JSON.stringify({ name: 'wow-package-check', private: true }),
    );
    run(
      'npm',
      [
        'install',
        '--no-audit',
        '--no-fund',
        '--loglevel=error',
        ...tarballs,
        `@types/node@${catalogVersion('@types/node')}`,
        `@types/react@${catalogVersion('@types/react')}`,
      ],
      { cwd: project, stdio: ['ignore', 'inherit', 'inherit'] },
    );

    const problems = [];
    for (const { name } of plan) {
      const dir = join(project, 'node_modules', ...name.split('/'));
      for (const file of ['LICENSE', 'README.md'])
        if (!existsSync(join(dir, file)))
          problems.push(`${name}: ${file} is missing`);
      const manifest = readFileSync(join(dir, 'package.json'), 'utf8');
      if (/"(?:catalog|workspace):/.test(manifest))
        problems.push(
          `${name}: package.json keeps a catalog: or workspace: range`,
        );
    }

    for (const [entry, exported] of ENTRIES) {
      const esm = `import * as m from '${entry}'; if (!m.${exported}) throw new Error('no ${exported}');`;
      const cjs = `const m = require('${entry}'); if (!m.${exported}) throw new Error('no ${exported}');`;
      for (const [kind, args] of [
        ['import', ['--input-type=module', '-e', esm]],
        ['require', ['--input-type=commonjs', '-e', cjs]],
      ])
        try {
          run(process.execPath, args, { cwd: project });
        } catch (error) {
          problems.push(
            `${kind} ${entry}: ${error.stderr.trim().split('\n').slice(0, 3).join(' | ')}`,
          );
        }
    }

    for (const bin of BINS) {
      let output;
      try {
        output = run(
          join(project, 'node_modules', '.bin', bin),
          ['--version'],
          {
            cwd: project,
          },
        ).trim();
      } catch (error) {
        output = error.stderr?.trim();
      }
      if (output !== version)
        problems.push(`${bin} --version printed ${output}, not ${version}`);
    }

    for (const [file, source] of Object.entries(CONSUMERS))
      writeFileSync(join(project, file), source);
    const tsc = join(ROOT, 'node_modules', 'typescript', 'bin', 'tsc');
    for (const [mode, { compilerOptions, files }] of Object.entries(MODES)) {
      const config = `tsconfig.${mode}.json`;
      writeFileSync(
        join(project, config),
        JSON.stringify({
          compilerOptions: {
            ...compilerOptions,
            target: 'es2022',
            lib: ['es2022', 'dom', 'dom.iterable'],
            types: [],
            strict: true,
            noEmit: true,
            // Our declarations must hold up to a full check, too.
            skipLibCheck: false,
          },
          files,
        }),
      );
      let output = '';
      try {
        run(process.execPath, [tsc, '-p', config], { cwd: project });
      } catch (error) {
        output = error.stdout;
      }
      const { ours, upstream } = typeDiagnostics(output);
      if (ours.length > 0)
        problems.push(`types (${mode}):\n${ours.join('\n')}`);
      console.log(
        `types (${mode}): ${files.join(', ')}` +
          (upstream.length > 0
            ? `; ${upstream.length} diagnostic(s) in or about fetcher's own declarations, not ours:\n    ${upstream
                .slice(0, 5)
                .join('\n    ')}`
            : ''),
      );
    }
    fail(problems);
    console.log(
      `consumer: installed, imported, required and ran the bins of ${version}`,
    );
  } finally {
    rmSync(project, { recursive: true, force: true });
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const args = process.argv.slice(2);
  const index = args.indexOf('--tarballs');
  const given = index === -1 ? undefined : resolve(args[index + 1]);
  const version = readProjectVersion();
  const plan = publishPlan(ROOT, version);

  checkManifests();
  const scratch = given ? undefined : mkdtempSync(join(tmpdir(), 'wow-npm-'));
  try {
    if (scratch) mkdirSync(scratch, { recursive: true });
    const tarballs = plan.map(({ dir, name }) =>
      given
        ? join(given, tarballName(name, version))
        : pack(ROOT, dir, scratch),
    );
    for (const tarball of tarballs)
      if (!existsSync(tarball)) throw new Error(`${tarball} does not exist`);
    await checkPublint(tarballs);
    checkConsumer(plan, tarballs, version);
  } finally {
    if (scratch) rmSync(scratch, { recursive: true, force: true });
  }
}
