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
 * The refusals a command makes of its own input before anything is sent —
 * nothing here asks the store or a permission.
 */

import {
  configBytes,
  MAX_VIEW_CONFIG_BYTES,
  MAX_VIEW_TITLE_LENGTH,
  titleProblem,
  type Issue,
  type ViewConfig,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { stopsSave } from './dashboardRuntime.js';
import type { ManagedViewRuntime } from './viewRuntimeTypes.js';
import { ViewCommandError } from './write.js';

/**
 * A view needs a title, and the store keeps one of at most
 * `MAX_VIEW_TITLE_LENGTH` (`view.title.empty`, `view.title.too-long`): the
 * engine refuses it here rather than send what the store refuses. Answers
 * the title as it is stored, trimmed.
 */
export function requireTitle(title: string): string {
  switch (titleProblem(title)) {
    case 'empty':
      throw new ViewCommandError(issue('view.title.empty', ['title']));
    case 'too-long':
      throw new ViewCommandError(
        issue('view.title.too-long', ['title'], { max: MAX_VIEW_TITLE_LENGTH }),
      );
    default:
      return title.trim();
  }
}

/**
 * The draft as a runtime writes it to the store (`stored`, where its kind
 * stores less than it reads), refused when the store would not keep it.
 */
export function storable(
  runtime: ManagedViewRuntime,
  draft: ViewConfig,
): ViewConfig {
  return requireStorable(runtime.stored?.(draft) ?? draft);
}

/**
 * A config the store keeps: at most `MAX_VIEW_CONFIG_BYTES` of JSON
 * (`view.config.too-large`). A board of many panels is what reaches it.
 */
export function requireStorable<C extends ViewConfig>(config: C): C {
  const bytes = configBytes(config);
  if (bytes > MAX_VIEW_CONFIG_BYTES)
    throw new ViewCommandError(
      issue('view.config.too-large', [], {
        size: Math.ceil(bytes / 1024),
        max: MAX_VIEW_CONFIG_BYTES / 1024,
      }),
    );
  return config;
}

/** What stops a save of this runtime's kind (`stopsSave`). */
export function requireSavable(
  runtime: ManagedViewRuntime,
  issues: readonly Issue[],
): void {
  if (stopsSave(runtime.kind, issues))
    throw new ViewCommandError(issue('view.config.invalid', []));
}
