package com.restofy.mail.domain.entity;
import java.util.UUID;
public record MailJob(UUID jobId, String status, int attempts) { }
