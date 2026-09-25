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
  distTag,
  isPublished,
  pack,
  publishPlan,
  tarballName,
} from './publish-npm.mjs';

// Checks the npm packages as a consumer gets them: the tarballs, not the
// workspace. Run after `pnpm build:typescript`:
//
//   node .github/scripts/package-check.mjs [--tarballs <dir>]
//   node .github/scripts/package-check.mjs --registry
//
// Without --tarballs it packs the PUBLISHED packages itself.
//
// `--registry` is the release workflow's smoke test after npm-deploy. It needs
// no build and no workspace install: it waits, a bounded number of times,
// until npm serves every PUBLISHED package at the project version, checks that
// their dist-tag is that version, and runs step 3 on `<name>@<version>` from
// the registry.
//
// It checks:
//
// 1. The manifests: peer ranges come from the `peers` catalog or the
//    workspace, and `engines.node` matches the workspace root.
// 2. publint (strict) on each tarball.
// 3. A fresh npm project installs the tarballs, or with --registry the
//    published versions (npm adds the peers), then:
//    - ES module import and CommonJS require of every entry point; wow-react
//      is ESM only and is required through Node's require(esm);
//    - the `wow-generator` and `fetcher-generator` bins print the version;
//    - LICENSE and README.md are in every package;
//    - each of TYPESCRIPT_VERSIONS, installed in the project, compiles
//      consumers under node16, nodenext and bundler resolution, without
//      skipLibCheck: an error in fetcher's
//      declarations fails the check as well as one in ours, except the few
//      that ALLOWED_FETCHER_DIAGNOSTICS names. Each consumer
//      holds `@ts-expect-error` misuses, so types that silently degrade to
//      `any` fail the check too.

/** Runtime entry points and a value each must export. */
const ENTRIES = [
  ['@ahoo-wang/wow-client', 'filter'],
  ['@ahoo-wang/wow-client/dsl', 'filter'],
  ['@ahoo-wang/wow-client/legacy', 'zh_CN'],
  ['@ahoo-wang/wow-react', 'useFetcherPagedQuery'],
  ['@ahoo-wang/wow-generator', 'CodeGenerator'],
];
const BINS = ['wow-generator', 'fetcher-generator'];

/**
 * The TypeScript versions every consumer compiles under, installed in the
 * consumer project under an npm alias each: the floor, 6.0, and the latest
 * 7.x, which `^7.0.0` resolves to on every run. TypeScript 6 is the minimum
 * (user decided 2026-09-25, second-round review P1-15); no package declares
 * a `typescript` peer. Raise the floor only in an `x.Y.0` release, together
 * with the compatibility page.
 */
export const TYPESCRIPT_VERSIONS = {
  '6.0': '~6.0.0',
  '7.x': '^7.0.0',
};

/** The alias a TypeScript version is installed under, e.g. `typescript-6.0`. */
const typescriptAlias = label => `typescript-${label}`;

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

/**
 * The only fetcher diagnostics the check lets through, each by mode, code,
 * file and identifier. Empty: fetcher 5.1.5 (the floor) fixed the last known
 * ones (fetcher-eventstream's `Response` getters reported twice as TS2300 when
 * one program holds an ESM and a CJS consumer). Add an entry only for a known
 * upstream bug, with a link and the version that fixes it; a stale entry
 * fails the check.
 */
export const ALLOWED_FETCHER_DIAGNOSTICS = [];

/** Whether a tsc diagnostic is exactly the one an allowance names. */
function allows({ code, file, identifier }, diagnostic) {
  const match = /^(\S+)\(\d+,\d+\): error (TS\d+): (.*)$/.exec(
    diagnostic.split('\n')[0],
  );
  return (
    match !== null &&
    match[1] === file &&
    match[2] === code &&
    match[3] === `Duplicate identifier '${identifier}'.`
  );
}

/**
 * Splits fetcher's diagnostics of one mode into those the allowance lets
 * through and the rest, and names the allowances that matched nothing: a stale
 * allowance fails the check too, so it is removed once fetcher is fixed.
 */
export function applyAllowance(
  mode,
  upstream,
  allowance = ALLOWED_FETCHER_DIAGNOSTICS,
) {
  const entries = allowance.filter(entry => entry.mode === mode);
  const rest = upstream.filter(
    diagnostic => !entries.some(entry => allows(entry, diagnostic)),
  );
  const stale = entries
    .filter(entry => !upstream.some(diagnostic => allows(entry, diagnostic)))
    .map(
      ({ code, file, identifier }) =>
        `${code} ${identifier} in ${file} no longer appears; remove its allowance`,
    );
  return { allowed: upstream.length - rest.length, rest, stale };
}

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
 * skipLibCheck, and both kinds fail it: a consumer sees fetcher's errors as
 * much as ours. Errors inside fetcher's declarations, or about importing them,
 * are fixed in fetcher (its packages are peers from npm) and then by raising
 * the floor in the `peers` catalog, so they are labelled apart.
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

/** A GitHub Actions error annotation; its message keeps its line breaks. */
export function annotation(message) {
  return `::error title=package check::${`${message}`
    .replace(/%/g, '%25')
    .replace(/\r/g, '%0D')
    .replace(/\n/g, '%0A')}`;
}

/** How long the smoke test waits for npm to serve a version it accepted. */
const REGISTRY_ATTEMPTS = 20;
const REGISTRY_DELAY_MS = 15_000;

/**
 * Waits until the registry serves every name@version, asking at most
 * `attempts` times. A 404 and a registry error both mean "not yet"; the last
 * error of each package goes into the failure.
 */
export async function waitForRegistry(
  names,
  version,
  {
    attempts = REGISTRY_ATTEMPTS,
    delayMs = REGISTRY_DELAY_MS,
    published = isPublished,
    sleep = ms => new Promise(done => setTimeout(done, ms)),
    log = console.log,
  } = {},
) {
  let missing = [...names];
  const errors = new Map();
  for (let attempt = 1; ; attempt++) {
    missing = missing.filter(name => {
      try {
        return !published(name, version);
      } catch (error) {
        errors.set(name, `${error.stderr || error.message}`.trim());
        return true;
      }
    });
    if (missing.length === 0) {
      log(
        `registry: serves ${names.map(name => `${name}@${version}`).join(', ')}`,
      );
      return;
    }
    const specs = missing.map(name => `${name}@${version}`).join(', ');
    if (attempt >= attempts)
      throw new Error(
        `the registry does not serve ${specs} after ${attempts} attempts ${delayMs / 1000}s apart` +
          missing
            .filter(name => errors.has(name))
            .map(name => `\n  ${name}: ${errors.get(name).split('\n')[0]}`)
            .join(''),
      );
    log(`registry: waiting for ${specs} (attempt ${attempt}/${attempts})`);
    await sleep(delayMs);
  }
}

/** Packages whose dist-tag `tag` is not `version`, given each one's dist-tags. */
export function distTagProblems(distTags, tag, version) {
  return Object.entries(distTags)
    .filter(([, tags]) => tags?.[tag] !== version)
    .map(
      ([name, tags]) =>
        `${name}: dist-tag ${tag} is ${tags?.[tag] ?? 'missing'}, not ${version}`,
    );
}

/** The dist-tag publish-npm.mjs gave this release points at it on npm. */
function checkDistTags(plan, version) {
  const tag = distTag(
    version,
    run('git', ['tag', '--list', 'v*'], { cwd: ROOT })
      .split('\n')
      .filter(Boolean),
  );
  fail(
    distTagProblems(
      Object.fromEntries(
        plan.map(({ name }) => [
          name,
          JSON.parse(run('npm', ['view', name, 'dist-tags', '--json'])),
        ]),
      ),
      tag,
      version,
    ),
  );
  console.log(`dist-tags: ${tag} is ${version}`);
}

/**
 * Installs `packages` (tarball paths, or name@version specs) into a fresh npm
 * project and checks it as a consumer, compiling it under each of
 * TYPESCRIPT_VERSIONS.
 */
function checkConsumer(plan, packages, version) {
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
        // Versions published minutes ago: ask the registry, not a cache.
        '--prefer-online',
        ...packages,
        `@types/node@${catalogVersion('@types/node')}`,
        `@types/react@${catalogVersion('@types/react')}`,
        ...Object.entries(TYPESCRIPT_VERSIONS).map(
          ([label, range]) =>
            `${typescriptAlias(label)}@npm:typescript@${range}`,
        ),
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
    for (const label of Object.keys(TYPESCRIPT_VERSIONS)) {
      const typescript = join(project, 'node_modules', typescriptAlias(label));
      const tsc = join(typescript, 'bin', 'tsc');
      const installed = JSON.parse(
        readFileSync(join(typescript, 'package.json'), 'utf8'),
      ).version;
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
        let failure;
        try {
          run(process.execPath, [tsc, '-p', config], { cwd: project });
        } catch (error) {
          output = error.stdout ?? '';
          failure = `${error.stderr ?? ''}${output}`.trim() || error.message;
        }
        const where = `${mode}, TypeScript ${installed}`;
        const { ours, upstream } = typeDiagnostics(output);
        // A compiler that fails without a diagnostic (an option it rejects, a
        // crash) must not pass as a clean compile.
        if (failure !== undefined && ours.length + upstream.length === 0)
          problems.push(`types (${where}): tsc failed:\n${failure}`);
        if (ours.length > 0)
          problems.push(`types (${where}):\n${ours.join('\n')}`);
        const { allowed, rest, stale } = applyAllowance(mode, upstream);
        if (rest.length > 0)
          problems.push(
            `types (${where}), in or about fetcher's own declarations (fix them in fetcher, then raise the floor in the peers catalog):\n${rest.join('\n')}`,
          );
        if (stale.length > 0)
          problems.push(
            `types (${where}), stale allowance:\n${stale.join('\n')}`,
          );
        console.log(
          `types (${where}): ${files.join(', ')}` +
            (allowed > 0
              ? `; ${allowed} allowed fetcher diagnostic(s), see ALLOWED_FETCHER_DIAGNOSTICS`
              : ''),
        );
      }
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

  try {
    if (args.includes('--registry')) {
      await waitForRegistry(
        plan.map(({ name }) => name),
        version,
      );
      checkDistTags(plan, version);
      checkConsumer(
        plan,
        plan.map(({ name }) => `${name}@${version}`),
        version,
      );
    } else {
      checkManifests();
      const scratch = given
        ? undefined
        : mkdtempSync(join(tmpdir(), 'wow-npm-'));
      try {
        if (scratch) mkdirSync(scratch, { recursive: true });
        const tarballs = plan.map(({ dir, name }) =>
          given
            ? join(given, tarballName(name, version))
            : pack(ROOT, dir, scratch),
        );
        for (const tarball of tarballs)
          if (!existsSync(tarball))
            throw new Error(`${tarball} does not exist`);
        await checkPublint(tarballs);
        checkConsumer(plan, tarballs, version);
      } finally {
        if (scratch) rmSync(scratch, { recursive: true, force: true });
      }
    }
  } catch (error) {
    // An annotation on the run's summary page, not only a line in the log.
    if (process.env.GITHUB_ACTIONS) console.log(annotation(error.message));
    throw error;
  }
}
