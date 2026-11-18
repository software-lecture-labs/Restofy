package com.restofy.auth.application.service;

import com.restofy.auth.application.service.revocation.*;
import com.restofy.auth.infrastructure.client.QrRevocationClient;
import com.restofy.auth.infrastructure.config.AuthSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RevocationRetryWorkerTests {
    @Test
    void failedBootstrapDoesNotFallBackToQueueEvenWhenEarlierSourceWasActive() {
        var transactions = mock(RevocationTransactions.class);
        var coordinator = mock(RevocationCoordinator.class);
        var bootstrap = mock(RevocationBootstrapTransactions.class);
        var peer = mock(QrRevocationClient.class);
        var nonce = java.util.UUID.randomUUID();
        when(peer.challenge(any(), any())).thenReturn(new QrRevocationClient.Challenge(nonce, false));
        when(bootstrap.active()).thenReturn(true);
        when(bootstrap.prepare(any(), any(), eq(nonce))).thenThrow(new IllegalStateException("baseline unavailable"));
        new RevocationRetryWorker(transactions, coordinator, bootstrap, peer, mock(AuthSettings.class)).retry();
        verifyNoInteractions(transactions);
    }

    @Test
    void oneFailedDeliveryDoesNotStarveOtherClaimedOperations() {
        var transactions = mock(RevocationTransactions.class);
        var coordinator = mock(RevocationCoordinator.class);
        var bootstrap = mock(RevocationBootstrapTransactions.class);
        var peer = mock(QrRevocationClient.class);
        var first = java.util.UUID.randomUUID();
        var second = java.util.UUID.randomUUID();
        when(bootstrap.active()).thenReturn(true);
        when(peer.challenge(any(), any())).thenThrow(new IllegalStateException("offline"));
        when(transactions.queueStats()).thenReturn(new RevocationTransactions.QueueStats(2, 0));
        when(transactions.claimRetries()).thenReturn(java.util.List.of(first, second));
        doThrow(new IllegalStateException("offline")).when(coordinator).deliver(first);
        new RevocationRetryWorker(transactions, coordinator, bootstrap, peer, mock(AuthSettings.class)).retry();
        verify(coordinator).deliver(second);
    }

    @Test
    void activeSourceProcessesQueueDespiteQrOutage() {
        var transactions = mock(RevocationTransactions.class);
        var coordinator = mock(RevocationCoordinator.class);
        var bootstrap = mock(RevocationBootstrapTransactions.class);
        var peer = mock(QrRevocationClient.class);
        var id = java.util.UUID.randomUUID();
        when(bootstrap.active()).thenReturn(true);
        when(peer.challenge(any(), any())).thenThrow(new IllegalStateException("offline"));
        when(transactions.queueStats()).thenReturn(new RevocationTransactions.QueueStats(1, 0));
        when(transactions.claimRetries()).thenReturn(java.util.List.of(id));
        new RevocationRetryWorker(transactions, coordinator, bootstrap, peer, mock(AuthSettings.class)).retry();
        verify(coordinator).deliver(id);
    }

    @Test
    void closedSourceNeverClaimsQueueWhenQrIsUnavailable() {
        var transactions = mock(RevocationTransactions.class);
        var coordinator = mock(RevocationCoordinator.class);
        var bootstrap = mock(RevocationBootstrapTransactions.class);
        var peer = mock(QrRevocationClient.class);
        when(peer.challenge(any(), any())).thenThrow(new IllegalStateException("offline"));
        new RevocationRetryWorker(transactions, coordinator, bootstrap, peer, mock(AuthSettings.class)).retry();
        verifyNoInteractions(transactions);
    }

    @Test
    void identityFailureStopsPeerTrafficAndLogsOnlySafeClassification(CapturedOutput output) {
        var transactions = mock(RevocationTransactions.class);
        var coordinator = mock(RevocationCoordinator.class);
        var bootstrap = mock(RevocationBootstrapTransactions.class);
        var peer = mock(QrRevocationClient.class);
        var settings = mock(AuthSettings.class);
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"sensitive-fixture-must-not-be-logged"))
                .when(bootstrap).verifyConfiguredIdentity();
        new RevocationRetryWorker(transactions,coordinator,bootstrap,peer,settings).retry();
        verifyNoInteractions(peer,transactions,coordinator,settings);
        assertThat(output.getAll()).contains("stage=SOURCE_IDENTITY","category=HTTP_503")
                .doesNotContain("sensitive-fixture-must-not-be-logged","ResponseStatusException");
    }
}
