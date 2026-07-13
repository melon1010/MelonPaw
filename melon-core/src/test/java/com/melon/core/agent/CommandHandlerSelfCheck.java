package com.melon.core.agent;

public final class CommandHandlerSelfCheck {

    private CommandHandlerSelfCheck() {
    }

    public static void main(String[] args) {
        CommandHandler handler = new CommandHandler();
        assertType(handler.handle("/skills list"), CommandHandler.CommandType.SKILLS);
        assertType(handler.handle("/skills public-apis里有什么呢？"), CommandHandler.CommandType.SKILLS);
    }

    private static void assertType(CommandHandler.CommandResult result, CommandHandler.CommandType expected) {
        if (result.getType() != expected) {
            throw new AssertionError("expected " + expected + ", got " + result.getType());
        }
    }
}
