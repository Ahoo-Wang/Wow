/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

// Checks only the files this change touched; .prettierignore limits Prettier to
// the TypeScript toolchain, and --ignore-unknown skips everything it can't parse.
const prettier = createRequire(
  new URL('../../package.json', import.meta.url),
).resolve('prettier/bin/prettier.cjs');
const { BASE_SHA, HEAD_SHA } = process.env;
// Manual dispatch checks the latest commit; never run a repository-wide formatter.
const base = BASE_SHA && !/^0+$/.test(BASE_SHA) ? BASE_SHA : 'HEAD^';
const files = execFileSync(
  'git',
  ['diff', '--name-only', '--diff-filter=ACMR', '-z', base, HEAD_SHA || 'HEAD'],
  { encoding: 'utf8' },
)
  .split('\0')
  .filter(Boolean);
for (let index = 0; index < files.length; index += 100)
  execFileSync(
    process.execPath,
    [
      prettier,
      '--check',
      '--ignore-unknown',
      '--',
      ...files.slice(index, index + 100),
    ],
    { stdio: 'inherit' },
  );
