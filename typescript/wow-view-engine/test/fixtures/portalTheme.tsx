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

import { Dialog as DialogPrimitive } from '@base-ui/react/dialog';
import { useLayoutEffect, useState, type ReactNode } from 'react';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../../src/components/ui/popover.js';
import {
  Dialog,
  DialogContent,
  DialogTitle,
} from '../../src/components/ui/dialog.js';
import { FilterSelect } from '../../src/filter/FilterSelect.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../src/components/ui/dropdown-menu.js';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../../src/components/ui/tooltip.js';
import { cn } from '../../src/lib/utils.js';
import { Input } from '../../src/components/ui/input.js';
import { FilterChoiceSelect } from '../../src/filter/FilterChoiceSelect.js';
import { FilterSearchSelect } from '../../src/filter/FilterSearchSelect.js';

export const portalKinds = [
  'popover',
  'dialog',
  'select',
  'menu',
  'tooltip',
  'choice',
  'search',
] as const;
export type PortalKind = (typeof portalKinds)[number];
export function PortalThemeExample({
  kind,
  appearance = 'dark',
  globalAppearance,
  darkClass = false,
  children,
}: {
  kind: PortalKind;
  appearance?: 'dark' | 'light';
  globalAppearance?: 'dark' | 'light';
  darkClass?: boolean;
  children?: ReactNode;
}) {
  useLayoutEffect(() => {
    if (!globalAppearance) return;
    const html = document.documentElement;
    const previous = html.getAttribute('data-theme');
    const colorScheme = html.style.colorScheme;
    html.setAttribute('data-theme', globalAppearance);
    html.style.colorScheme = globalAppearance;
    return () => {
      if (previous === null) html.removeAttribute('data-theme');
      else html.setAttribute('data-theme', previous);
      html.style.colorScheme = colorScheme;
    };
  }, [globalAppearance]);
  const [selected, setSelected] = useState<string | null>(null);
  const input = <Input aria-label="弹层输入" aria-invalid />;
  let content: ReactNode;
  switch (kind) {
    case 'popover':
      content = (
        <Popover>
          <PopoverTrigger>打开弹层</PopoverTrigger>
          <PopoverContent>
            <PopoverTitle>主题弹层</PopoverTitle>
            {input}
            {children}
          </PopoverContent>
        </Popover>
      );
      break;
    case 'dialog':
      content = (
        <Dialog>
          <DialogPrimitive.Trigger>打开弹层</DialogPrimitive.Trigger>
          <DialogContent className="fve:dark:border-input">
            <DialogTitle>主题弹层</DialogTitle>
            {input}
            {children}
          </DialogContent>
        </Dialog>
      );
      break;
    case 'select':
      content = (
        <FilterSelect
          label="打开弹层"
          options={[{ value: 'one', label: '候选项' }]}
          value={selected}
          onValueChange={setSelected}
        />
      );
      break;
    case 'menu':
      content = (
        <DropdownMenu>
          <DropdownMenuTrigger>打开弹层</DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuGroup>
              <DropdownMenuItem>候选项</DropdownMenuItem>
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      );
      break;
    case 'tooltip':
      content = (
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger>打开弹层</TooltipTrigger>
            <TooltipContent>主题提示</TooltipContent>
          </Tooltip>
        </TooltipProvider>
      );
      break;
    case 'choice':
      content = (
        <FilterChoiceSelect
          label="打开弹层"
          values={[]}
          options={[{ value: 'one', label: '候选项' }]}
          onValuesChange={() => {}}
        />
      );
      break;
    case 'search':
      content = (
        <FilterSearchSelect
          label="打开弹层"
          options={[{ value: 'one', label: '候选项' }]}
          onValueChange={() => {}}
        />
      );
      break;
  }
  return (
    <div
      className={cn(
        'fve-root fve:bg-background fve:text-foreground',
        darkClass && 'dark',
      )}
      data-theme={appearance}
      data-testid="theme-scope"
      style={{ padding: 24 }}
    >
      <Input aria-label="宿主输入" aria-invalid />
      <div
        data-testid="border-reference"
        className="fve:border-input"
        aria-hidden
      />
      {content}
    </div>
  );
}

export function LivePortalThemeExample() {
  const [appearance, setAppearance] = useState<'dark' | 'light'>('dark');
  return (
    <PortalThemeExample kind="popover" appearance={appearance}>
      <button
        onClick={() =>
          setAppearance(current => (current === 'dark' ? 'light' : 'dark'))
        }
      >
        切换外观
      </button>
    </PortalThemeExample>
  );
}
