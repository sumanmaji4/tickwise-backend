package com.tickwise.tenant;

import com.tickwise.shared.error.ConflictException;
import com.tickwise.shared.error.NotFoundException;
import com.tickwise.shared.security.Role;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final TeamRepository teams;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    UserService(UserRepository users, TeamRepository teams, TenantRepository tenants,
                PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.teams = teams;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public UserView create(UUID tenantId, NewUser input) {
        String email = normalizeEmail(input.email());
        if (input.teamId() != null && teams.findByTenantIdAndId(tenantId, input.teamId()).isEmpty()) {
            throw new NotFoundException("Team", input.teamId());
        }
        if (users.existsByTenantIdAndEmail(tenantId, email)) {
            throw new ConflictException("A user with email " + email + " already exists");
        }
        UserAccount user = new UserAccount(UUID.randomUUID(), tenantId, email, input.displayName().strip(),
                passwordEncoder.encode(input.rawPassword()), input.role(), input.teamId(),
                input.maxOpenTickets(), clock.instant());
        return users.save(user).toView();
    }

    @Transactional(readOnly = true)
    public List<UserView> list(UUID tenantId) {
        return users.findAllByTenantIdOrderByEmail(tenantId).stream().map(UserAccount::toView).toList();
    }

    @Transactional(readOnly = true)
    public UserView get(UUID tenantId, UUID userId) {
        return users.findByTenantIdAndId(tenantId, userId).map(UserAccount::toView)
                .orElseThrow(() -> new NotFoundException("User", userId));
    }

    /** Active members of a team with one of the given roles, ordered by id (a stable round-robin order). */
    @Transactional(readOnly = true)
    public List<UserView> activeTeamMembers(UUID tenantId, UUID teamId, Set<Role> roles) {
        return users.findAllByTenantIdAndTeamIdAndActiveTrueAndRoleInOrderById(tenantId, teamId, roles).stream()
                .map(UserAccount::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<UserView> activeWithRoles(UUID tenantId, Set<Role> roles) {
        return users.findAllByTenantIdAndActiveTrueAndRoleIn(tenantId, roles).stream().map(UserAccount::toView).toList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, UserView> getAll(UUID tenantId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllByTenantIdAndIdIn(tenantId, userIds).stream()
                .map(UserAccount::toView)
                .collect(Collectors.toMap(UserView::id, Function.identity()));
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(UUID tenantId, String email) {
        return users.existsByTenantIdAndEmail(tenantId, normalizeEmail(email));
    }

    @Transactional(readOnly = true)
    public Optional<UserView> find(UUID tenantId, UUID userId) {
        return users.findByTenantIdAndId(tenantId, userId).map(UserAccount::toView);
    }

    /** Resolves login input. Empty when either the tenant or the user is unknown; callers must not tell which. */
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentials(String tenantSlug, String email) {
        return tenants.findBySlug(tenantSlug)
                .flatMap(t -> users.findByTenantIdAndEmail(t.getId(), normalizeEmail(email)))
                .map(UserAccount::toCredentials);
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
