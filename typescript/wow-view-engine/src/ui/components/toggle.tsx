import { Toggle as TogglePrimitive } from "@base-ui/react/toggle"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

const toggleVariants = cva(
  "fve:group/toggle fve:inline-flex fve:items-center fve:justify-center fve:gap-1 fve:rounded-lg fve:text-sm fve:font-medium fve:whitespace-nowrap fve:transition-all fve:outline-none fve:hover:bg-muted fve:hover:text-foreground fve:focus-visible:border-ring fve:focus-visible:ring-[3px] fve:focus-visible:ring-ring/50 fve:disabled:pointer-events-none fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-destructive/20 fve:aria-pressed:bg-muted fve:data-[state=on]:bg-muted fve:dark:aria-invalid:ring-destructive/40 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "fve:bg-transparent",
        outline: "fve:border fve:border-input fve:bg-transparent fve:hover:bg-muted",
      },
      size: {
        default:
          "fve:h-8 fve:min-w-8 fve:px-2.5 fve:has-data-[icon=inline-end]:pr-2 fve:has-data-[icon=inline-start]:pl-2",
        sm: "fve:h-7 fve:min-w-7 fve:rounded-[min(var(--radius-md),12px)] fve:px-2.5 fve:text-[0.8rem] fve:has-data-[icon=inline-end]:pr-1.5 fve:has-data-[icon=inline-start]:pl-1.5 fve:[&_svg:not([class*='size-'])]:size-3.5",
        lg: "fve:h-9 fve:min-w-9 fve:px-2.5 fve:has-data-[icon=inline-end]:pr-2 fve:has-data-[icon=inline-start]:pl-2",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Toggle({
  className,
  variant = "default",
  size = "default",
  ...props
}: TogglePrimitive.Props & VariantProps<typeof toggleVariants>) {
  return (
    <TogglePrimitive
      data-slot="toggle"
      className={cn(toggleVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Toggle, toggleVariants }
