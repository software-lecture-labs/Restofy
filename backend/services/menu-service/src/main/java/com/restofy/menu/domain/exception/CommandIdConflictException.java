package com.restofy.menu.domain.exception;

/** A command id that was already used for a command with different content. The first command stays in force. */
public class CommandIdConflictException extends RuntimeException {

    public CommandIdConflictException() {
        super("The command id was already used for a different command.");
    }
}
