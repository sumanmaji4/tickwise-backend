package com.tickwise.tenant;

import java.time.Instant;
import java.util.UUID;

public final class InvitationEvents {

    private InvitationEvents() {}

    /**
     * Published in the creating transaction so the invite email is queued atomically. Carries the
     * raw token because the email must contain it; the database only ever stores its hash.
     */
    public record Created(UUID tenantId, UUID invitationId, String email, String rawToken, String inviterName,
                          Instant expiresAt) {

        @Override
        public String toString() {
            return "InvitationEvents.Created[tenantId=" + tenantId + ", invitationId=" + invitationId + "]";
        }
    }
}
