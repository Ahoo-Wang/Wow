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

import { createContext, useContext } from 'react';
import { usePortalTheme, type PortalTheme } from '../../lib/usePortalTheme.js';
import { Menu as MenuPrimitive } from '@base-ui/react/menu';
import { cn } from '../../lib/utils.js';
import { CheckIcon } from 'lucide-react';

const MenuTheme = createContext<PortalTheme>({ style: {} });
function DropdownMenu(props: MenuPrimitive.Root.Props) {
  const { scope, theme, captureTheme } = usePortalTheme(
    props.open,
    props.defaultOpen,
  );
  return (
    <span ref={scope} className="fve-root fve:inline-flex">
      <MenuTheme.Provider value={theme}>
        <MenuPrimitive.Root
          {...props}
          onOpenChange={(open, details) => {
            captureTheme(open);
            props.onOpenChange?.(open, details);
          }}
        />
      </MenuTheme.Provider>
    </span>
  );
}

function DropdownMenuTrigger({ ...props }: MenuPrimitive.Trigger.Props) {
  return <MenuPrimitive.Trigger data-slot="dropdown-menu-trigger" {...props} />;
}

function DropdownMenuContent({
  align = 'start',
  alignOffset = 0,
  side = 'bottom',
  sideOffset = 4,
  className,
  ...props
}: MenuPrimitive.Popup.Props &
  Pick<
    MenuPrimitive.Positioner.Props,
    'align' | 'alignOffset' | 'side' | 'sideOffset'
  >) {
  const theme = useContext(MenuTheme);
  return (
    <MenuPrimitive.Portal className="fve-root" {...theme}>
      <MenuPrimitive.Positioner
        className="fve-root fve:isolate fve:z-50 fve:outline-none"
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
      >
        <MenuPrimitive.Popup
          data-slot="dropdown-menu-content"
          className={cn(
            '  fve:z-50 fve:max-h-(--available-height) fve:w-(--anchor-width) fve:min-w-32 fve:origin-(--transform-origin) fve:overflow-x-hidden fve:overflow-y-auto fve:rounded-lg fve:bg-popover fve:p-1 fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:outline-none fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:overflow-hidden fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95',
            className,
          )}
          {...props}
        />
      </MenuPrimitive.Positioner>
    </MenuPrimitive.Portal>
  );
}

function DropdownMenuGroup({ ...props }: MenuPrimitive.Group.Props) {
  return <MenuPrimitive.Group data-slot="dropdown-menu-group" {...props} />;
}

function DropdownMenuItem({
  className,
  inset,
  variant = 'default',
  ...props
}: MenuPrimitive.Item.Props & {
  inset?: boolean;
  variant?: 'default' | 'destructive';
}) {
  return (
    <MenuPrimitive.Item
      data-slot="dropdown-menu-item"
      data-inset={inset}
      data-variant={variant}
      className={cn(
        'fve:group/dropdown-menu-item fve:relative fve:flex fve:cursor-default fve:items-center fve:gap-1.5 fve:rounded-md fve:px-1.5 fve:py-1 fve:text-sm fve:outline-hidden fve:select-none fve:focus:bg-accent fve:focus:text-accent-foreground fve:not-data-[variant=destructive]:focus:**:text-accent-foreground fve:data-inset:pl-7 fve:data-[variant=destructive]:text-destructive fve:data-[variant=destructive]:focus:bg-destructive/10 fve:data-[variant=destructive]:focus:text-destructive fve:dark:data-[variant=destructive]:focus:bg-destructive/20 fve:data-disabled:pointer-events-none fve:data-disabled:opacity-50 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*=size-])]:size-4 fve:data-[variant=destructive]:*:[svg]:text-destructive',
        className,
      )}
      {...props}
    />
  );
}

function DropdownMenuRadioGroup({ ...props }: MenuPrimitive.RadioGroup.Props) {
  return (
    <MenuPrimitive.RadioGroup
      data-slot="dropdown-menu-radio-group"
      {...props}
    />
  );
}

function DropdownMenuRadioItem({
  className,
  children,
  inset,
  ...props
}: MenuPrimitive.RadioItem.Props & {
  inset?: boolean;
}) {
  return (
    <MenuPrimitive.RadioItem
      data-slot="dropdown-menu-radio-item"
      data-inset={inset}
      className={cn(
        'fve:relative fve:flex fve:cursor-default fve:items-center fve:gap-1.5 fve:rounded-md fve:py-1 fve:pr-8 fve:pl-1.5 fve:text-sm fve:outline-hidden fve:select-none fve:focus:bg-accent fve:focus:text-accent-foreground fve:focus:**:text-accent-foreground fve:data-inset:pl-7 fve:data-disabled:pointer-events-none fve:data-disabled:opacity-50 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*=size-])]:size-4',
        className,
      )}
      {...props}
    >
      <span
        className="fve:pointer-events-none fve:absolute fve:right-2 fve:flex fve:items-center fve:justify-center"
        data-slot="dropdown-menu-radio-item-indicator"
      >
        <MenuPrimitive.RadioItemIndicator>
          <CheckIcon />
        </MenuPrimitive.RadioItemIndicator>
      </span>
      {children}
    </MenuPrimitive.RadioItem>
  );
}

function DropdownMenuSeparator({
  className,
  ...props
}: MenuPrimitive.Separator.Props) {
  return (
    <MenuPrimitive.Separator
      data-slot="dropdown-menu-separator"
      className={cn('fve:-mx-1 fve:my-1 fve:h-px fve:bg-border', className)}
      {...props}
    />
  );
}

export {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
};
