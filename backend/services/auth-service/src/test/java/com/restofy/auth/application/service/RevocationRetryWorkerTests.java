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
