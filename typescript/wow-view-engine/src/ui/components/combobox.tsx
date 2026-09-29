"use client"

import * as React from "react"
import { Combobox as ComboboxPrimitive } from "@base-ui/react"
import { cn } from "cn"

import { Button } from "@/ui/components/button"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/ui/components/input-group"
import { ChevronDownIcon, XIcon, CheckIcon } from "lucide-react"

const Combobox = ComboboxPrimitive.Root

function ComboboxValue({ ...props }: ComboboxPrimitive.Value.Props) {
  return <ComboboxPrimitive.Value data-slot="combobox-value" {...props} />
}

function ComboboxTrigger({
  className,
  children,
  ...props
}: ComboboxPrimitive.Trigger.Props) {
  return (
    <ComboboxPrimitive.Trigger
      data-slot="combobox-trigger"
      className={cn("fve:[&_svg:not([class*='size-'])]:size-4", className)}
      {...props}
    >
      {children}
      <ChevronDownIcon className="fve:pointer-events-none fve:size-4 fve:text-muted-foreground" />
    </ComboboxPrimitive.Trigger>
  )
}

function ComboboxClear({ className, ...props }: ComboboxPrimitive.Clear.Props) {
  return (
    <ComboboxPrimitive.Clear
      data-slot="combobox-clear"
      render={<InputGroupButton variant="ghost" size="icon-xs" />}
      className={cn(className)}
      {...props}
    >
      <XIcon className="fve:pointer-events-none" />
    </ComboboxPrimitive.Clear>
  )
}

function ComboboxInput({
  className,
  children,
  disabled = false,
  showTrigger = true,
  showClear = false,
  ...props
}: ComboboxPrimitive.Input.Props & {
  showTrigger?: boolean
  showClear?: boolean
}) {
  return (
    <InputGroup className={cn("fve:w-auto", className)}>
      <ComboboxPrimitive.Input
        render={<InputGroupInput disabled={disabled} />}
        {...props}
      />
      <InputGroupAddon align="inline-end">
        {showTrigger && (
          <InputGroupButton
            size="icon-xs"
            variant="ghost"
            render={<ComboboxTrigger />}
            data-slot="input-group-button"
            className="fve:group-has-data-[slot=combobox-clear]/input-group:hidden fve:data-pressed:bg-transparent"
            disabled={disabled}
          />
        )}
        {showClear && <ComboboxClear disabled={disabled} />}
      </InputGroupAddon>
      {children}
    </InputGroup>
  )
}

function ComboboxContent({
  className,
  side = "bottom",
  sideOffset = 6,
  align = "start",
  alignOffset = 0,
  anchor,
  ...props
}: ComboboxPrimitive.Popup.Props &
  Pick<
    ComboboxPrimitive.Positioner.Props,
    "side" | "align" | "sideOffset" | "alignOffset" | "anchor"
  >) {
  return (
    <ComboboxPrimitive.Portal>
      <ComboboxPrimitive.Positioner
        side={side}
        sideOffset={sideOffset}
        align={align}
        alignOffset={alignOffset}
        anchor={anchor}
        className="fve:isolate fve:z-50"
      >
        <ComboboxPrimitive.Popup
          data-slot="combobox-content"
          data-chips={!!anchor}
          className={cn("fve:group/combobox-content fve:relative fve:max-h-(--available-height) fve:w-(--anchor-width) fve:max-w-(--available-width) fve:min-w-[calc(var(--anchor-width)+--spacing(7))] fve:origin-(--transform-origin) fve:overflow-hidden fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:data-[chips=true]:min-w-(--anchor-width) fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:*:data-[slot=input-group]:m-1 fve:*:data-[slot=input-group]:mb-0 fve:*:data-[slot=input-group]:h-8 fve:*:data-[slot=input-group]:border-input/30 fve:*:data-[slot=input-group]:bg-input/30 fve:*:data-[slot=input-group]:shadow-none fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95", className )}
          {...props}
        />
      </ComboboxPrimitive.Positioner>
    </ComboboxPrimitive.Portal>
  )
}

function ComboboxList({ className, ...props }: ComboboxPrimitive.List.Props) {
  return (
    <ComboboxPrimitive.List
      data-slot="combobox-list"
      className={cn(
        "fve:no-scrollbar fve:max-h-[min(calc(--spacing(72)---spacing(9)),calc(var(--available-height)---spacing(9)))] fve:scroll-py-1 fve:overflow-y-auto fve:overscroll-contain fve:p-1 fve:data-empty:p-0",
        className
      )}
      {...props}
    />
  )
}

function ComboboxItem({
  className,
  children,
  ...props
}: ComboboxPrimitive.Item.Props) {
  return (
    <ComboboxPrimitive.Item
      data-slot="combobox-item"
      className={cn(
        "fve:relative fve:flex fve:w-full fve:cursor-default fve:items-center fve:gap-2 fve:rounded-md fve:py-1 fve:pr-8 fve:pl-1.5 fve:text-sm fve:outline-hidden fve:select-none fve:data-highlighted:bg-accent fve:data-highlighted:text-accent-foreground fve:not-data-[variant=destructive]:data-highlighted:**:text-accent-foreground fve:data-disabled:pointer-events-none fve:data-disabled:opacity-50 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    >
      {children}
      <ComboboxPrimitive.ItemIndicator
        render={
          <span className="fve:pointer-events-none fve:absolute fve:right-2 fve:flex fve:size-4 fve:items-center fve:justify-center" />
        }
      >
        <CheckIcon className="fve:pointer-events-none" />
      </ComboboxPrimitive.ItemIndicator>
    </ComboboxPrimitive.Item>
  )
}

function ComboboxGroup({ className, ...props }: ComboboxPrimitive.Group.Props) {
  return (
    <ComboboxPrimitive.Group
      data-slot="combobox-group"
      className={cn(className)}
      {...props}
    />
  )
}

function ComboboxLabel({
  className,
  ...props
}: ComboboxPrimitive.GroupLabel.Props) {
  return (
    <ComboboxPrimitive.GroupLabel
      data-slot="combobox-label"
      className={cn("fve:px-2 fve:py-1.5 fve:text-xs fve:text-muted-foreground", className)}
      {...props}
    />
  )
}

function ComboboxCollection({ ...props }: ComboboxPrimitive.Collection.Props) {
  return (
    <ComboboxPrimitive.Collection data-slot="combobox-collection" {...props} />
  )
}

function ComboboxEmpty({ className, ...props }: ComboboxPrimitive.Empty.Props) {
  return (
    <ComboboxPrimitive.Empty
      data-slot="combobox-empty"
      className={cn(
        "fve:hidden fve:w-full fve:justify-center fve:py-2 fve:text-center fve:text-sm fve:text-muted-foreground fve:group-data-empty/combobox-content:flex",
        className
      )}
      {...props}
    />
  )
}

function ComboboxSeparator({
  className,
  ...props
}: ComboboxPrimitive.Separator.Props) {
  return (
    <ComboboxPrimitive.Separator
      data-slot="combobox-separator"
      className={cn("fve:-mx-1 fve:my-1 fve:h-px fve:bg-border", className)}
      {...props}
    />
  )
}

function ComboboxChips({
  className,
  ...props
}: React.ComponentPropsWithRef<typeof ComboboxPrimitive.Chips> &
  ComboboxPrimitive.Chips.Props) {
  return (
    <ComboboxPrimitive.Chips
      data-slot="combobox-chips"
      className={cn(
        "fve:flex fve:min-h-8 fve:flex-wrap fve:items-center fve:gap-1 fve:rounded-lg fve:border fve:border-input fve:bg-transparent fve:bg-clip-padding fve:px-2.5 fve:py-1 fve:text-sm fve:transition-colors fve:focus-within:border-ring fve:focus-within:ring-3 fve:focus-within:ring-ring/50 fve:has-aria-invalid:border-destructive fve:has-aria-invalid:ring-3 fve:has-aria-invalid:ring-destructive/20 fve:has-data-[slot=combobox-chip]:px-1 fve:dark:bg-input/30 fve:dark:has-aria-invalid:border-destructive/50 fve:dark:has-aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  )
}

function ComboboxChip({
  className,
  children,
  showRemove = true,
  ...props
}: ComboboxPrimitive.Chip.Props & {
  showRemove?: boolean
}) {
  return (
    <ComboboxPrimitive.Chip
      data-slot="combobox-chip"
      className={cn(
        "fve:flex fve:h-[calc(--spacing(5.25))] fve:w-fit fve:items-center fve:justify-center fve:gap-1 fve:rounded-sm fve:bg-muted fve:px-1.5 fve:text-xs fve:font-medium fve:whitespace-nowrap fve:text-foreground fve:has-disabled:pointer-events-none fve:has-disabled:cursor-not-allowed fve:has-disabled:opacity-50 fve:has-data-[slot=combobox-chip-remove]:pr-0",
        className
      )}
      {...props}
    >
      {children}
      {showRemove && (
        <ComboboxPrimitive.ChipRemove
          render={<Button variant="ghost" size="icon-xs" />}
          className="fve:-ml-1 fve:opacity-50 fve:hover:opacity-100"
          data-slot="combobox-chip-remove"
        >
          <XIcon className="fve:pointer-events-none" />
        </ComboboxPrimitive.ChipRemove>
      )}
    </ComboboxPrimitive.Chip>
  )
}

function ComboboxChipsInput({
  className,
  ...props
}: ComboboxPrimitive.Input.Props) {
  return (
    <ComboboxPrimitive.Input
      data-slot="combobox-chip-input"
      className={cn("fve:min-w-16 fve:flex-1 fve:outline-none", className)}
      {...props}
    />
  )
}

function useComboboxAnchor() {
  return React.useRef<HTMLDivElement | null>(null)
}

export {
  Combobox,
  ComboboxInput,
  ComboboxContent,
  ComboboxList,
  ComboboxItem,
  ComboboxGroup,
  ComboboxLabel,
  ComboboxCollection,
  ComboboxEmpty,
  ComboboxSeparator,
  ComboboxChips,
  ComboboxChip,
  ComboboxChipsInput,
  ComboboxTrigger,
  ComboboxValue,
  useComboboxAnchor,
}
