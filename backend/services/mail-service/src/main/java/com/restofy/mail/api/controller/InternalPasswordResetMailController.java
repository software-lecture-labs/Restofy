package com.restofy.mail.api.controller;

import java.util.UUID;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.application.service.MailJobService;
import com.restofy.mail.domain.entity.MailJob;
import com.restofy.mail.infrastructure.security.MailSecurityConfiguration;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalPasswordResetMailController {
    private final MailJobService service;
    public InternalPasswordResetMailController(MailJobService service) { this.service = service; }
    @PostMapping(MailSecurityConfiguration.JOB_PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailJob accept(@AuthenticationPrincipal UUID messageId, @Valid @RequestBody PasswordResetMailRequest request) {
        if (!request.messageId().equals(messageId)) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        return service.accept(request);
    }
}
