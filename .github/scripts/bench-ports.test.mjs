/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';

// The benchmark's stores and tools publish their ports on 127.0.0.1 only: a wildcard binding (0.0.0.0) cannot be
// taken while another tool holds the port on any loopback address (kubefwd's 127.1.27.x:27017), and Docker then
// reports the container healthy while the host is refused.

const HARNESS = join(import.meta.dirname, '../../test/benchmarks/harness');

/** Every port a compose file or a `docker run` in [text] publishes, as written. */
export function publishedPorts(text) {
  const ports = [];
  let inPorts = false;
  let indent = 0;
  for (const line of text.split('\n')) {
    const depth = line.search(/\S/);
    if (/^\s*ports:\s*$/.test(line)) {
      inPorts = true;
      indent = depth;
      continue;
    }
    if (inPorts) {
      const entry = /^\s*-\s*"?([^"\s]+)"?/.exec(line);
      if (entry && depth > indent) {
        ports.push(entry[1]);
        continue;
      }
      if (line.trim() && depth <= indent + 1) inPorts = false;
    }
  }
  for (const command of text
    .replace(/\\\n/g, ' ')
    .split('\n')
    .filter(l => /docker\s+(run|create)\b/.test(l)))
    for (const m of command.matchAll(
      /\s(?:-p|--publish)(?:\s+|=)"?([^"\s]+)"?/g,
    ))
      ports.push(m[1]);
  return ports;
}

const loopback = port => port.startsWith('127.0.0.1:');

test('every port the harness publishes binds to 127.0.0.1', () => {
  const files = readdirSync(HARNESS).filter(f => /\.(ya?ml|sh)$/.test(f));
  const published = files.flatMap(f =>
    publishedPorts(readFileSync(join(HARNESS, f), 'utf8')).map(
      port => `${f}: ${port}`,
    ),
  );
  assert.ok(
    published.length >= 5,
    `the harness publishes its stores and tools: ${published.join(', ')}`,
  );
  assert.deepEqual(
    published.filter(p => !loopback(p.split(': ')[1])),
    [],
    'a port published on every interface',
  );
});

test('a published port is found in a compose list and in a docker run, quoted or not', () => {
  const compose = [
    'services:',
    '  mongo:',
    '    ports:',
    '      - "27017:27017"',
    '      - 127.0.0.1:9092:9092',
    '    healthcheck:',
    '      test: ["CMD"]',
  ].join('\n');
  assert.deepEqual(publishedPorts(compose), [
    '27017:27017',
    '127.0.0.1:9092:9092',
  ]);
  assert.deepEqual(
    publishedPorts('docker run -d -p 18123:8123 --publish=127.0.0.1:1:2 x'),
    ['18123:8123', '127.0.0.1:1:2'],
  );
  assert.deepEqual(
    publishedPorts('docker run -d \\\n  -p 19200:9200 x'),
    ['19200:9200'],
    'a continued line',
  );
  assert.deepEqual(
    publishedPorts('mkdir -p "$OUT"\nps -o rss= -p "$pid"'),
    [],
    'not a docker command',
  );
});
