import * as React from "react"
import { mergeProps } from "@base-ui/react/merge-props"
import { useRender } from "@base-ui/react/use-render"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

import { Separator } from "@/ui/components/separator"

function ItemGroup({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      role="list"
      data-slot="item-group"
      className={cn(
        "fve:group/item-group fve:flex fve:w-full fve:flex-col fve:gap-4 fve:has-data-[size=sm]:gap-2.5 fve:has-data-[size=xs]:gap-2",
        className
      )}
      {...props}
    />
  )
}

function ItemSeparator({
  className,
  ...props
}: React.ComponentProps<typeof Separator>) {
  return (
    <Separator
      data-slot="item-separator"
      orientation="horizontal"
      className={cn("fve:my-2", className)}
      {...props}
    />
  )
}

const itemVariants = cva(
  "fve:group/item fve:flex fve:w-full fve:flex-wrap fve:items-center fve:rounded-lg fve:border fve:text-sm fve:transition-colors fve:duration-100 fve:outline-none fve:focus-visible:border-ring fve:focus-visible:ring-[3px] fve:focus-visible:ring-ring/50 fve:[a]:transition-colors fve:[a]:hover:bg-muted",
  {
    variants: {
      variant: {
        default: "fve:border-transparent",
        outline: "fve:border-border",
        muted: "fve:border-transparent fve:bg-muted/50",
      },
      size: {
        default: "fve:gap-2.5 fve:px-3 fve:py-2.5",
        sm: "fve:gap-2.5 fve:px-3 fve:py-2.5",
        xs: "fve:gap-2 fve:px-2.5 fve:py-2 fve:in-data-[slot=dropdown-menu-content]:p-0",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Item({
  className,
  variant = "default",
  size = "default",
  render,
  ...props
}: useRender.ComponentProps<"div"> & VariantProps<typeof itemVariants>) {
  return useRender({
    defaultTagName: "div",
    props: mergeProps<"div">(
      {
        className: cn(itemVariants({ variant, size, className })),
      },
      props
    ),
    render,
    state: {
      slot: "item",
      variant,
      size,
    },
  })
}

const itemMediaVariants = cva(
  "fve:flex fve:shrink-0 fve:items-center fve:justify-center fve:gap-2 fve:group-has-data-[slot=item-description]/item:translate-y-0.5 fve:group-has-data-[slot=item-description]/item:self-start fve:[&_svg]:pointer-events-none",
  {
    variants: {
      variant: {
        default: "fve:bg-transparent",
        icon: "fve:[&_svg:not([class*='size-'])]:size-4",
        image:
          "fve:size-10 fve:overflow-hidden fve:rounded-sm fve:group-data-[size=sm]/item:size-8 fve:group-data-[size=xs]/item:size-6 fve:[&_img]:size-full fve:[&_img]:object-cover",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function ItemMedia({
  className,
  variant = "default",
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof itemMediaVariants>) {
  return (
    <div
      data-slot="item-media"
      data-variant={variant}
      className={cn(itemMediaVariants({ variant, className }))}
      {...props}
    />
  )
}

function ItemContent({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="item-content"
      className={cn(
        "fve:flex fve:flex-1 fve:flex-col fve:gap-1 fve:group-data-[size=xs]/item:gap-0 fve:[&+[data-slot=item-content]]:flex-none",
        className
      )}
      {...props}
    />
  )
}

function ItemTitle({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="item-title"
      className={cn(
        "fve:line-clamp-1 fve:flex fve:w-fit fve:items-center fve:gap-2 fve:text-sm fve:leading-snug fve:font-medium fve:underline-offset-4",
        className
      )}
      {...props}
    />
  )
}

function ItemDescription({ className, ...props }: React.ComponentProps<"p">) {
  return (
    <p
      data-slot="item-description"
      className={cn(
        "fve:line-clamp-2 fve:text-left fve:text-sm fve:leading-normal fve:font-normal fve:text-muted-foreground fve:group-data-[size=xs]/item:text-xs fve:[&>a]:underline fve:[&>a]:underline-offset-4 fve:[&>a:hover]:text-primary",
        className
      )}
      {...props}
    />
  )
}

function ItemActions({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="item-actions"
      className={cn("fve:flex fve:items-center fve:gap-2", className)}
      {...props}
    />
  )
}

function ItemHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="item-header"
      className={cn(
        "fve:flex fve:basis-full fve:items-center fve:justify-between fve:gap-2",
        className
      )}
      {...props}
    />
  )
}

function ItemFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="item-footer"
      className={cn(
        "fve:flex fve:basis-full fve:items-center fve:justify-between fve:gap-2",
        className
      )}
      {...props}
    />
  )
}

export {
  Item,
  ItemMedia,
  ItemContent,
  ItemActions,
  ItemGroup,
  ItemSeparator,
  ItemTitle,
  ItemDescription,
  ItemHeader,
  ItemFooter,
}
