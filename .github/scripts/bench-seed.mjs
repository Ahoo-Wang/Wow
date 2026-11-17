/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { bench } from './bench-common.mjs';

// Seeding a primed data set once (test/benchmarks/harness/seed.sh): the first session of an arm that needs one seeds a
// copy with `bench.sh seed-copy`, and every later session of an arm with the same servers and properties restores it
// (`--restore=<dir>`) instead of seeding the accounts again. Priming stays in every session: a restore carries no
// node's cache and no store's buffers. One copy per arm build, store and data set; in a paired job, one an arm.

/** The property that makes an arm seed afresh in every session: the gate's A/A arm, the comparison's other side. */
export const FRESH = 'bench.seed';

// Each copy made in this process, by its key (the suite's directory for copies, then the arm's servers, properties and
// accounts): its directory, or null when seeding it failed (those sessions seed).
const copies = new Map();
let cleanup = false;

/** The volume a copy's seed.txt names, undefined without one. */
const volumeOf = dir => {
  const file = join(dir, 'seed.txt');
  if (!existsSync(file)) return undefined;
  return /^volume=(.+)$/m.exec(readFileSync(file, 'utf8'))?.[1];
};

/** Removes every copy's volume when the process ends: a runner's disk, or a machine's, keeps none past the job. */
const removeAtExit = () => {
  if (cleanup) return;
  cleanup = true;
  process.on('exit', () => {
    for (const dir of copies.values()) {
      const volume = dir && volumeOf(dir);
      if (volume)
        spawnSync('docker', ['volume', 'rm', '-f', volume], {
          stdio: 'ignore',
        });
    }
  });
};

/**
 * The options a fresh session of an arm adds to restore its seeded data set: `['--restore=<dir>']`, seeding the copy
 * first when none exists for its servers ([server], bench.sh's options), [props] and [accounts]; none when the session
 * is not [primed] (a data set below PRIME_FROM, or cold), when the arm seeds afresh ([props] has FRESH = 'fresh'), or
 * when seeding the copy failed (then every session of that arm seeds, as before). [run] runs bench.sh (bench's
 * signature), [out] is the directory the copies go under, [seed] the rest of the copy's options (the seed concurrency
 * and the CPU lists).
 */
export function restoreSeeded({
  benchPath,
  out,
  server,
  props,
  accounts,
  primed,
  seed = [],
  run = bench,
}) {
  if (!primed || props?.[FRESH] === 'fresh') return [];
  const key = JSON.stringify([out, server, props ?? {}, accounts]);
  if (!copies.has(key)) {
    const made = [...copies.keys()].filter(
      other => JSON.parse(other)[0] === out,
    );
    const dir = join(out, `seed-${made.length + 1}`);
    removeAtExit();
    const status = run(
      benchPath,
      [
        'seed-copy',
        ...server,
        `--accounts=${accounts}`,
        ...seed,
        `--label=seed`,
        `--out=${dir}`,
      ],
      props,
    );
    copies.set(key, status === 0 ? dir : null);
    if (status !== 0)
      console.log(
        `::warning::seeding a copy failed (${status}); these sessions seed afresh: ${server.join(' ')}`,
      );
  }
  const dir = copies.get(key);
  return dir ? [`--restore=${dir}`] : [];
}
