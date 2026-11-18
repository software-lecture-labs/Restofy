package com.restofy.auth.application.service;

import java.util.UUID;
import com.restofy.auth.application.service.revocation.RevocationCoordinator;
import com.restofy.auth.application.service.revocation.RevocationTransactions;
import com.restofy.auth.infrastructure.client.QrRevocationClient;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class RevocationCoordinatorTests {
    @Test
    void durableAcknowledgementFinalizesWithoutQrTransport() {
        var transactions = mock(RevocationTransactions.class);
        var peer = mock(QrRevocationClient.class);
        var operation = mock(RevocationTransactions.Operation.class);
        var id = UUID.randomUUID();
        when(operation.status()).thenReturn("ACKNOWLEDGED");
        when(transactions.load(id)).thenReturn(operation);
        new RevocationCoordinator(transactions, peer).deliver(id);
        verify(transactions).finalizeOperation(id);
        verifyNoInteractions(peer);
    }
}
