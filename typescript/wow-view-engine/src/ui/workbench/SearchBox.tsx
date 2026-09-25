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

import { SearchIcon, XIcon } from 'lucide-react';
import { useId } from 'react';
import type { SearchBoxController } from '../../react/index.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from '../components/input-group.js';
import { isPlainEnter } from '../filter/enter.js';
import { IconTooltip } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The view's search, at the end of the applied band — beside the conditions
 * it is one of (`useSearchBox`).
 *
 * Typing edits the draft, as any condition does, so the editor's pending
 * count moves with it; Enter puts it in force — not each keystroke, since
 * each would be a query over the whole store, and not while an input
 * method is composing, whose Enter picks a candidate rather than asking
 * (`isPlainEnter`). ✕ takes the search away and asks again, because the
 * rows on screen were found by it. The box is named by the search field's
 * own label — 「搜索错误」 says what is searched, 「搜索」 would not — and
 * what Enter does is said once, to a screen reader, in its description.
 */
export function SearchBox({ search }: { search: SearchBoxController }) {
  const messages = useViewMessages();
  const hint = useId();
  const { field, value } = search;
  return (
    <InputGroup data-slot="view-search" className="w-56 max-w-full">
      <InputGroupAddon>
        <SearchIcon />
      </InputGroupAddon>
      <InputGroupInput
        // Text with the search role, not `type="search"`: WebKit draws its
        // own ✕ into a search input, beside the one this box carries.
        type="text"
        role="searchbox"
        value={value}
        // The label, or — where the source matches a phrase only as words —
        // the label saying so (「按词检索」, capabilities.md 5).
        placeholder={
          search.byWords
            ? messages.label('label.search.by-words', { field: field.label })
            : field.label
        }
        aria-label={field.label}
        aria-describedby={hint}
        onChange={event => search.set(event.target.value)}
        onKeyDown={event => {
          if (!isPlainEnter(event)) return;
          event.preventDefault();
          search.submit();
        }}
      />
      <span id={hint} className="sr-only">
        {messages.label('label.search.hint')}
      </span>
      {(value !== '' || search.applied !== '') && (
        <InputGroupAddon align="inline-end">
          <IconTooltip
            label={messages.label('label.search.clear')}
            render={<InputGroupButton size="icon-xs" onClick={search.clear} />}
          >
            <XIcon />
          </IconTooltip>
        </InputGroupAddon>
      )}
    </InputGroup>
  );
}
