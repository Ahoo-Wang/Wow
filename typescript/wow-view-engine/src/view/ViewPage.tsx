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

import {
  ViewPageContent,
  type ViewPageContentProps,
} from './ViewPageContent.js';
import type { ViewEngineBinding } from '../react/useViewEngine.js';
export { ViewPageContent } from './ViewPageContent.js';
export type { ViewPageContentProps } from './ViewPageContent.js';
export interface ViewPageProps
  extends
    Omit<ViewPageContentProps, 'engine' | 'extensions'>,
    ViewEngineBinding {}
/** Composes an engine binding; useViewEngine or the caller owns its lifecycle. */
export function ViewPage({ engine, error, ...props }: ViewPageProps) {
  if (!engine)
    return (
      <div className="fve-root fve:p-4" role={error ? 'alert' : 'status'}>
        {error ?? '正在加载视图…'}
      </div>
    );
  return <ViewPageContent {...props} engine={engine} />;
}
