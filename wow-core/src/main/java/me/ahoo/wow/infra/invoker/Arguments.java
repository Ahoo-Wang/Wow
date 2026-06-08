package me.ahoo.wow.infra.invoker;

public final class Arguments {
    public static final Object[] EMPTY_ARGS = new Object[0];

    private Arguments() {
    }

    public static Object[] actualArgs(Object[] args) {
        return args == null ? EMPTY_ARGS : args;
    }

    public static Object[] prependReceiver(Object receiver, Object[] args) {
        if (args == null) {
            return new Object[]{receiver};
        }
        Object[] newArgs = new Object[args.length + 1];
        newArgs[0] = receiver;
        System.arraycopy(args, 0, newArgs, 1, args.length);
        return newArgs;
    }

}
