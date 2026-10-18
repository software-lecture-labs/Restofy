package com.restofy.qr.domain.exception;

public class QrCodeConflictException extends RuntimeException {
    public QrCodeConflictException() {
        super("QR creation conflicts with the current table or QR state.");
    }
}
