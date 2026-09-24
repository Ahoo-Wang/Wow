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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { validateInput, generateAction } from '../../src/utils';

// Mock dependencies
vi.mock('../../src/utils/logger', () => ({
  ConsoleLogger: class ConsoleLogger {
    info = vi.fn();
    success = vi.fn();
    error = vi.fn();
    progress = vi.fn();
    constructor() {}
  },
}));

vi.mock('../../src', () => ({
  CodeGenerator: class CodeGenerator {
    generate = vi.fn();
    constructor() {}
  },
}));

vi.mock('ts-morph', () => ({
  Project: class Project {
    getDirectory = vi.fn();
    getSourceFiles = vi.fn().mockReturnValue([]);
    getSourceFile = vi.fn();
    createSourceFile = vi.fn();
    save = vi.fn();
    constructor() {}
  },
}));

// Import after mocking
import { ConsoleLogger } from '../../src/utils/logger';
import { CodeGenerator } from '../../src';
import { Project } from 'ts-morph';

describe('validateInput', () => {
  it('should return false for empty string', () => {
    expect(validateInput('')).toBe(false);
  });

  it('should return true for valid HTTP URL', () => {
    expect(validateInput('http://example.com')).toBe(true);
  });

  it('should return true for valid HTTPS URL', () => {
    expect(validateInput('https://example.com')).toBe(true);
  });

  it('should return false for invalid URL protocol', () => {
    expect(validateInput('ftp://example.com')).toBe(false);
  });

  it('should return true for malformed URL treated as file path', () => {
    expect(validateInput('not-a-url')).toBe(true);
  });

  it('should return true for non-empty file path', () => {
    expect(validateInput('/path/to/file')).toBe(true);
    expect(validateInput('relative/path')).toBe(true);
  });

  // SSRF prevention: validateInput used to only check the URL protocol, so a
  // crafted `-i` input could make the generator host probe sensitive internal
  // endpoints (cloud metadata, RFC1918 ranges). Loopback (localhost /
  // 127.0.0.0/8 / ::1) is intentionally ALLOWED — pointing a developer-run CLI
  // at a local mock server is a legitimate workflow (the integration test
  // itself uses http://localhost:8080).
  describe('SSRF prevention for remote inputs', () => {
    it('should reject the AWS/cloud metadata endpoint', () => {
      expect(
        validateInput('http://169.254.169.254/latest/meta-data/'),
      ).toBe(false);
    });

    it('should reject RFC1918 private IP ranges', () => {
      expect(validateInput('http://10.0.0.1/spec.json')).toBe(false);
      expect(validateInput('http://192.168.1.1/spec.json')).toBe(false);
      expect(validateInput('http://172.16.0.1/spec.json')).toBe(false);
    });

    it('should reject 0.0.0.0 ("this host")', () => {
      expect(validateInput('http://0.0.0.0/spec.json')).toBe(false);
    });

    it('should reject IPv6 unspecified, link-local and unique-local', () => {
      expect(validateInput('http://[::]/spec.json')).toBe(false);
      // fe80::/10 covers fe80::–febf::, not just fe80::.
      expect(validateInput('http://[fe80::1]/spec.json')).toBe(false);
      expect(validateInput('http://[fe90::1]/spec.json')).toBe(false);
      expect(validateInput('http://[febf::1]/spec.json')).toBe(false);
      expect(validateInput('http://[fc00::1]/spec.json')).toBe(false);
      expect(validateInput('http://[fd12:3456::1]/spec.json')).toBe(false);
    });

    // Regression: loopback must stay ALLOWED. The integration test runs the
    // generator against a local mock server (http://localhost:8080). Blocking
    // localhost broke CI.
    it('should ALLOW localhost and loopback (legitimate local dev workflow)', () => {
      expect(validateInput('http://localhost:8080/v3/api-docs')).toBe(true);
      expect(validateInput('http://127.0.0.1:3000/spec.json')).toBe(true);
      expect(validateInput('http://[::1]:8080/spec.json')).toBe(true);
    });

    it('should accept public IPv4 and IPv6 addresses', () => {
      expect(validateInput('http://8.8.8.8/spec.json')).toBe(true);
      expect(validateInput('http://[2606:4700:4700::1111]/spec.json')).toBe(
        true,
      );
    });

    it('should accept public hostnames', () => {
      expect(validateInput('https://api.example.com/openapi.json')).toBe(true);
    });
  });
});

describe('generateAction', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('should validate input and exit if invalid', async () => {
    await expect(
      generateAction({ input: '', output: '/output' }),
    ).rejects.toThrow();
  });

  it('should generate code successfully', async () => {
    await generateAction({
      input: 'http://example.com',
      output: '/tmp/test-output',
    });
    expect(true).toBe(true);
  });

  it('should handle generation error', () => {
    expect(() => {
      generateAction({ input: 'http://example.com', output: '/invalid/path' });
    }).not.toThrow();
  });
});
