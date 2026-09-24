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

import { readFile } from 'fs';

/** How long a remote document may take before the request is abandoned. */
export const DEFAULT_HTTP_TIMEOUT_MS = 30_000;

/**
 * Options for loading a resource over HTTP. Files ignore them.
 */
export interface LoadResourceOptions {
  /** Request headers, for example `Authorization`. */
  readonly headers?: Record<string, string>;
  /** Milliseconds before the request is abandoned. */
  readonly timeoutMs?: number;
}

/**
 * Tells whether a location names an http(s) resource rather than a file.
 *
 * @param path - A file path or URL
 * @returns True for an `http://` or `https://` URL
 */
export function isHttpLocation(path: string): boolean {
  return /^https?:\/\//i.test(path);
}

export function loadResource(
  path: string,
  options: LoadResourceOptions = {},
): Promise<string> {
  if (isHttpLocation(path)) {
    return loadHttpResource(path, options);
  }
  return loadFile(path);
}

/**
 * Fetches a resource and returns its body.
 *
 * A response outside 2xx is a failure: a 401 or 404 page is not the document
 * the caller asked for, and parsing it would only fail later with a message
 * about the page instead of the request.
 *
 * @param url - The http(s) URL
 * @param options - Headers and timeout
 * @returns The response body as text
 * @throws Error naming the status, the timeout or the network failure; the
 * caller names the URL
 */
export async function loadHttpResource(
  url: string,
  options: LoadResourceOptions = {},
): Promise<string> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_HTTP_TIMEOUT_MS;
  let response: Response;
  try {
    response = await fetch(url, {
      headers: options.headers,
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    const reason =
      error instanceof Error && error.name === 'TimeoutError'
        ? `no response within ${timeoutMs} ms`
        : networkReason(error);
    throw new Error(reason, { cause: error });
  }
  if (!response.ok) {
    const status = [response.status, response.statusText]
      .filter(Boolean)
      .join(' ');
    throw new Error(`HTTP ${status}`);
  }
  return await response.text();
}

/**
 * Explains a failed fetch. Node reports every network failure as "fetch
 * failed" and keeps the actual reason - DNS, refused connection, TLS - in
 * `cause`.
 */
function networkReason(error: unknown): string {
  if (!(error instanceof Error)) return String(error);
  const cause = error.cause;
  if (cause instanceof Error && cause.message) {
    return `${error.message} (${cause.message})`;
  }
  return error.message;
}

export function loadFile(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    readFile(path, 'utf-8', (err, data) => {
      if (err) {
        reject(err);
      } else {
        resolve(data);
      }
    });
  });
}
