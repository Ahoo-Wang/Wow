import { Checkbox as CheckboxPrimitive } from "@base-ui/react/checkbox"
import { cn } from "cn"
import { CheckIcon } from "lucide-react"

function Checkbox({ className, ...props }: CheckboxPrimitive.Root.Props) {
  return (
    <CheckboxPrimitive.Root
      data-slot="checkbox"
      className={cn(
        "fve:peer fve:relative fve:flex fve:size-4 fve:shrink-0 fve:items-center fve:justify-center fve:rounded-[4px] fve:border fve:border-input fve:transition-colors fve:outline-none fve:group-has-disabled/field:opacity-50 fve:group-has-[:focus-visible]/field-label:ring-0 fve:group-has-[:focus-visible]/field-label:not-data-checked:border-input fve:after:absolute fve:after:-inset-x-3 fve:after:-inset-y-2 fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring/50 fve:disabled:cursor-not-allowed fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:aria-invalid:aria-checked:border-primary fve:dark:bg-input/30 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40 fve:data-checked:border-primary fve:data-checked:bg-primary fve:data-checked:text-primary-foreground fve:group-has-[:focus-visible]/field-label:data-checked:border-primary fve:dark:data-checked:bg-primary",
        className
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator
        data-slot="checkbox-indicator"
        className="fve:grid fve:place-content-center fve:text-current fve:transition-none fve:[&>svg]:size-3.5"
      >
        <CheckIcon
        />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
}

export { Checkbox }
