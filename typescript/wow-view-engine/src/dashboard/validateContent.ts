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
 * A content panel admitted: a heading's or a markdown panel's text within
 * its kind's length, an image's addresses, a links panel's items — and a
 * kind the board does not know refused (`validateContentPanel`).
 */

import {
  MAX_HEADING_LENGTH,
  MAX_MARKDOWN_LENGTH,
  MAX_PANEL_LINKS,
  type DashboardContentPanel,
  type DashboardPanel,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue, isPlainObject } from '../filter/index.js';
import { isSafeContentUrl } from './panels.js';
import { shape } from './shape.js';

export function validateContentPanel(
  panel: DashboardContentPanel,
  path: IssuePath,
): Issue[] {
  switch (panel.kind) {
    case 'heading':
      return validateText(
        panel.content,
        [...path, 'content'],
        MAX_HEADING_LENGTH,
        'dashboard.heading.too-long',
      );
    case 'markdown':
      return validateText(
        panel.content,
        [...path, 'content'],
        MAX_MARKDOWN_LENGTH,
        'dashboard.markdown.too-long',
      );
    case 'image':
      return validateImage(panel, path);
    case 'links':
      return validateLinks(panel, path);
    default:
      return [
        issue('dashboard.panel.unknown-kind', path, {
          kind: (panel as DashboardPanel).kind,
        }),
      ];
  }
}

/** A content panel's text: a string no longer than its kind holds. */
function validateText(
  content: unknown,
  path: IssuePath,
  max: number,
  code: string,
): Issue[] {
  if (typeof content !== 'string') return [shape(path, 'string')];
  return content.length > max ? [issue(code, path, { max })] : [];
}

function validateImage(
  panel: Extract<DashboardContentPanel, { kind: 'image' }>,
  path: IssuePath,
): Issue[] {
  const issues: Issue[] = [];
  if (typeof panel.src !== 'string' || !isSafeContentUrl(panel.src))
    issues.push(issue('dashboard.url.unsupported-scheme', [...path, 'src']));
  if (
    panel.href !== undefined &&
    (typeof panel.href !== 'string' || !isSafeContentUrl(panel.href))
  )
    issues.push(issue('dashboard.url.unsupported-scheme', [...path, 'href']));
  return issues;
}

function validateLinks(
  panel: Extract<DashboardContentPanel, { kind: 'links' }>,
  path: IssuePath,
): Issue[] {
  if (!Array.isArray(panel.items)) return [shape([...path, 'items'], 'array')];
  if (panel.items.length > MAX_PANEL_LINKS)
    return [
      issue('dashboard.links.too-many', [...path, 'items'], {
        max: MAX_PANEL_LINKS,
      }),
    ];

  const issues: Issue[] = [];
  panel.items.forEach((item, index) => {
    const at: IssuePath = [...path, 'items', index];
    if (!isPlainObject(item)) {
      issues.push(shape(at, 'object'));
      return;
    }
    if (typeof item.label !== 'string' || item.label.trim().length === 0)
      issues.push(issue('dashboard.link.label-empty', [...at, 'label']));
    if (typeof item.href !== 'string' || !isSafeContentUrl(item.href))
      issues.push(issue('dashboard.url.unsupported-scheme', [...at, 'href']));
  });
  return issues;
}
