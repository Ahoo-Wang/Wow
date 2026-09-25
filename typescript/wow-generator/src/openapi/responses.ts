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

import type {
  MediaType,
  Reference,
  Response,
  Schema,
} from '@ahoo-wang/fetcher-openapi';
import { isReference } from './references';

/** The media type of JSON. */
export const APPLICATION_JSON = 'application/json';

/** The media type of a server-sent event stream. */
export const TEXT_EVENT_STREAM = 'text/event-stream';

/**
 * The media type part of a content type, without parameters and in lower
 * case: `application/json;charset=UTF-8` → `application/json`.
 */
export function mediaTypeOf(contentType: string): string {
  return contentType.split(';')[0].trim().toLowerCase();
}

/**
 * Tells whether a content type carries JSON: `application/json`, or any
 * `+json` type such as `application/hal+json` or `application/problem+json`,
 * with or without parameters.
 */
export function isJsonContentType(contentType: string): boolean {
  const mediaType = mediaTypeOf(contentType);
  return (
    mediaType === APPLICATION_JSON ||
    /^application\/[^/]+\+json$/.test(mediaType)
  );
}

/**
 * Tells whether a content type is text other than an event stream.
 */
export function isTextContentType(contentType: string): boolean {
  const mediaType = mediaTypeOf(contentType);
  return mediaType.startsWith('text/') && mediaType !== TEXT_EVENT_STREAM;
}

/**
 * Finds the entry of a content map that matches, preferring an exact key.
 *
 * @param content - The content map of a request body or a response
 * @param exact - The content type to prefer as written
 * @param matches - What else qualifies
 * @returns The media type object, or undefined
 */
export function findMediaType(
  content: Record<string, MediaType> | undefined,
  exact: string,
  matches: (contentType: string) => boolean = contentType =>
    mediaTypeOf(contentType) === exact,
): MediaType | undefined {
  if (!content) return undefined;
  if (content[exact]) return content[exact];
  const key = Object.keys(content).find(matches);
  return key === undefined ? undefined : content[key];
}

export function extractResponseSchema(
  contentType: string,
  response?: Response | Reference,
): Schema | Reference | undefined {
  if (!response) {
    return;
  }
  if (isReference(response)) {
    return undefined;
  }
  return findMediaType(response.content, contentType)?.schema;
}

/**
 * Extracts the JSON schema of a response: `application/json` or any `+json`
 * type, with or without parameters.
 * @param response - The response object or reference
 * @returns The JSON schema from the response content or undefined if not found
 */
export function extractResponseJsonSchema(
  response?: Response | Reference,
): Schema | Reference | undefined {
  if (!response || isReference(response)) return undefined;
  return findMediaType(response.content, APPLICATION_JSON, isJsonContentType)
    ?.schema;
}

export function extractResponseEventStreamSchema(
  response?: Response | Reference,
): Schema | Reference | undefined {
  return extractResponseSchema(TEXT_EVENT_STREAM, response);
}

export function extractResponseWildcardSchema(
  response?: Response | Reference,
): Schema | Reference | undefined {
  return extractResponseSchema('*/*', response);
}

/**
 * Tells whether a response carries text other than an event stream, such as
 * `text/plain`.
 */
export function hasTextResponse(response?: Response | Reference): boolean {
  if (!response || isReference(response)) return false;
  return Object.keys(response.content ?? {}).some(isTextContentType);
}
