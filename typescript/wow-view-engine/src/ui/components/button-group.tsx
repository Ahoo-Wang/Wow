import { mergeProps } from "@base-ui/react/merge-props"
import { useRender } from "@base-ui/react/use-render"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

import { Separator } from "@/ui/components/separator"

const buttonGroupVariants = cva(
  "fve:flex fve:w-fit fve:items-stretch fve:*:focus-visible:relative fve:*:focus-visible:z-10 fve:has-[>[data-slot=button-group]]:gap-2 fve:has-[select[aria-hidden=true]:last-child]:[&>[data-slot=select-trigger]:last-of-type]:rounded-r-lg fve:[&>[data-slot=select-trigger]:not([class*='w-'])]:w-fit fve:[&>input]:flex-1",
  {
    variants: {
      orientation: {
        horizontal:
          "fve:*:data-slot:rounded-r-none fve:[&>[data-slot]:not(:has(~[data-slot]))]:rounded-r-lg! fve:[&>[data-slot]~[data-slot]]:rounded-l-none fve:[&>[data-slot]~[data-slot]]:border-l-0",
        vertical:
          "fve:flex-col fve:*:data-slot:rounded-b-none fve:[&>[data-slot]:not(:has(~[data-slot]))]:rounded-b-lg! fve:[&>[data-slot]~[data-slot]]:rounded-t-none fve:[&>[data-slot]~[data-slot]]:border-t-0",
      },
    },
    defaultVariants: {
      orientation: "horizontal",
    },
  }
)

function ButtonGroup({
  className,
  orientation,
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof buttonGroupVariants>) {
  return (
    <div
      role="group"
      data-slot="button-group"
      data-orientation={orientation}
      className={cn(buttonGroupVariants({ orientation }), className)}
      {...props}
    />
  )
}

function ButtonGroupText({
  className,
  render,
  ...props
}: useRender.ComponentProps<"div">) {
  return useRender({
    defaultTagName: "div",
    props: mergeProps<"div">(
      {
        className: cn(
          "fve:flex fve:items-center fve:gap-2 fve:rounded-lg fve:border fve:bg-muted fve:px-2.5 fve:text-sm fve:font-medium fve:[&_svg]:pointer-events-none fve:[&_svg:not([class*='size-'])]:size-4",
          className
        ),
      },
      props
    ),
    render,
    state: {
      slot: "button-group-text",
    },
  })
}

function ButtonGroupSeparator({
  className,
  orientation = "vertical",
  ...props
}: React.ComponentProps<typeof Separator>) {
  return (
    <Separator
      data-slot="button-group-separator"
      orientation={orientation}
      className={cn(
        "fve:relative fve:self-stretch fve:bg-input fve:data-horizontal:mx-px fve:data-horizontal:w-auto fve:data-vertical:my-px fve:data-vertical:h-auto",
        className
      )}
      {...props}
    />
  )
}

export {
  ButtonGroup,
  ButtonGroupSeparator,
  ButtonGroupText,
  buttonGroupVariants,
}
