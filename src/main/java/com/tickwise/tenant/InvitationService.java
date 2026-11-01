package com.tickwise.tenant;

import com.tickwise.shared.domain.Times;
import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;
import com.tickwise.shared.security.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin invites a person by email; they accept with a single-use token and choose their own
 * password. Tokens are 256-bit random values; only their SHA-256 hash is stored. An unknown,
 * expired, revoked or used token is indistinguishable to the caller (404).
 */
@Service
public class InvitationService {

    static final Duration VALIDITY = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InvitationRepository invitations;
    private final UserService users;
    private final TeamService teams;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, UserService users, TeamService teams,
                      ApplicationEventPublisher events, Clock clock) {
        this.invitations = invitations;
        this.users = users;
        this.teams = teams;
        this.events = events;
        this.clock = clock;
    }

    /** The invitation plus its raw token, which is shown to the inviting admin once and never stored. */
    public record Issued(InvitationView invitation, String token) {}

    /** What the accept page shows before the person sets a password. */
    public record Preview(UUID tenantId, String email, Role role) {}

    @Transactional
    public Issued invite(UUID tenantId, String email, Role role, UUID teamId, int maxOpenTickets, UUID invitedBy) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (role == Role.CUSTOMER) {
            throw new ConflictException("Customers are created from their emails, not invited");
        }
        if (teamId != null) {
            teams.get(tenantId, teamId);
        }
        if (users.existsByEmail(tenantId, normalized)) {
            throw new ConflictException(normalized + " already has an account");
        }
        Instant now = Times.now(clock);
        // Re-inviting replaces the previous invitation, so only the newest link works.
        invitations.findPendingFor(tenantId, normalized).ifPresent(old -> {
            old.revoke(now);
            invitations.flush();
        });
        String token = newToken();
        Invitation invitation = invitations.save(new Invitation(tenantId, normalized, role, teamId, maxOpenTickets,
                hash(token), invitedBy, now, now.plus(VALIDITY)));
        String inviter = users.get(tenantId, invitedBy).displayName();
        events.publishEvent(new InvitationEvents.Created(tenantId, invitation.toView().id(), normalized, token, inviter,
                now.plus(VALIDITY)));
        return new Issued(invitation.toView(), token);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> pending(UUID tenantId) {
        Instant now = Times.now(clock);
        return invitations.findPending(tenantId).stream().filter(i -> i.isUsable(now)).map(Invitation::toView).toList();
    }

    @Transactional
    public void revoke(UUID tenantId, UUID invitationId) {
        Invitation invitation = invitations.findByTenantIdAndId(tenantId, invitationId)
                .filter(i -> i.isUsable(Times.now(clock)))
                .orElseThrow(() -> new NotFoundException("Invitation", invitationId));
        invitation.revoke(Times.now(clock));
    }

    @Transactional(readOnly = true)
    public Optional<Preview> preview(String token) {
        return usable(token).map(i -> new Preview(i.getTenantId(), i.getEmail(), i.getRole()));
    }

    /** Creates the account and consumes the token; a second accept with the same token fails. */
    @Transactional
    public UserView accept(String token, String displayName, String rawPassword) {
        Invitation invitation = usable(token).orElseThrow(() -> new NotFoundException("Invitation", "token"));
        invitation.accept(Times.now(clock));
        invitations.flush(); // optimistic version check: two concurrent accepts cannot both succeed
        return users.create(invitation.getTenantId(), new NewUser(invitation.getEmail(), displayName, rawPassword,
                invitation.getRole(), invitation.getTeamId(), invitation.getMaxOpenTickets()));
    }

    private Optional<Invitation> usable(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        Instant now = Times.now(clock);
        return invitations.findByTokenHash(hash(token)).filter(i -> i.isUsable(now));
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
