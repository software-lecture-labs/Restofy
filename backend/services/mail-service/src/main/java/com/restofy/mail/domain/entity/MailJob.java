package com.restofy.mail.domain.entity;

import java.util.UUID;

import com.restofy.mail.domain.enums.MailJobStatus;

public record MailJob(UUID jobId, MailJobStatus status, int attempts) { }
