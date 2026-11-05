package com.restofy.auth.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findBySessionId(UUID sessionId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken token set token.revokedAt = :now
            where token.sessionId = :sessionId and token.revokedAt is null
            """)
    int revokeForSession(UUID sessionId, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken token set token.revokedAt = :now
            where token.revokedAt is null and token.sessionId in
                (select session.id from UserSession session where session.staffUserId = :userId
                    and (:preservedSessionId is null or session.id <> :preservedSessionId))
            """)
    int revokeForUser(UUID userId, UUID preservedSessionId, Instant now);
}
