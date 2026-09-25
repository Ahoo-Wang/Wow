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
 * `datePattern` against the JVM, pattern by pattern.
 *
 * `fixtures/java-date-patterns.json` is written by `DatePatternCorpusTest` in
 * wow-api, which hands every pattern to a relative-time filter's constructor
 * — the server's entry for `datePattern` — and records whether it was
 * accepted. That Kotlin test also fails when the file no longer matches the
 * JVM, so the file is the JVM's answer, not a hand-picked sample.
 * Regenerate it from the repository root with
 * `./gradlew :wow-api:test --tests "*DatePatternCorpusTest" -Dwow.snapshot.update=true`.
 */

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { filter } from '../../src';

interface DatePatternCorpus {
  source: string;
  javaSpecificationVersion: string;
  patterns: [pattern: string, accepted: boolean][];
}

const corpus = JSON.parse(
  readFileSync(
    new URL('../fixtures/java-date-patterns.json', import.meta.url),
    'utf8',
  ),
) as DatePatternCorpus;

function accepts(datePattern: string): boolean {
  try {
    filter.today('createTime', { datePattern });
    return true;
  } catch {
    return false;
  }
}

describe('datePattern against DateTimeFormatter.ofPattern', () => {
  it('reads a corpus with both answers in it', () => {
    const answers = new Set(corpus.patterns.map(([, accepted]) => accepted));
    expect(answers).toEqual(new Set([true, false]));
    expect(new Set(corpus.patterns.map(([pattern]) => pattern)).size).toBe(
      corpus.patterns.length,
    );
  });

  it('accepts what the JVM accepts and refuses what it refuses', () => {
    const disagreements = corpus.patterns
      .filter(([pattern, accepted]) => accepts(pattern) !== accepted)
      .map(([pattern, accepted]) => ({ pattern, jvm: accepted }));
    expect(disagreements).toEqual([]);
  });
});
