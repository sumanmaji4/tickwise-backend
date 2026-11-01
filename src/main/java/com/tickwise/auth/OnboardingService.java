package com.tickwise.auth;

import com.tickwise.assignment.AssignmentService;
import com.tickwise.shared.error.NotFoundException;
import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.security.Role;
import com.tickwise.tenant.InvitationService;
import com.tickwise.tenant.NewUser;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.TeamView;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import com.tickwise.tenant.UserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service organisation signup and invitation acceptance; both end signed in. */
@Service
class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    static final String DEFAULT_TEAM = "Support";

    private final TenantService tenants;
    private final TeamService teams;
    private final com.tickwise.tenant.UserService users;
    private final AssignmentService assignments;
    private final InvitationService invitations;
    private final JwtTokenService tokens;

    OnboardingService(TenantService tenants, TeamService teams, com.tickwise.tenant.UserService users,
                      AssignmentService assignments, InvitationService invitations, JwtTokenService tokens) {
        this.tenants = tenants;
        this.teams = teams;
        this.users = users;
        this.assignments = assignments;
        this.invitations = invitations;
        this.tokens = tokens;
    }

    record SignedIn(UserView user, TenantView tenant, JwtTokenService.IssuedToken token) {}

    /**
     * One transaction: the tenant (which provisions default SLA policies and business hours),
     * a "Support" team that new tickets are routed to, and the first admin. Either all of it
     * exists or none of it does.
     */
    @Transactional
    public SignedIn signUp(String organizationName, String slug, String timezone, String adminName, String email,
                           String password) {
        TenantView tenant = tenants.create(slug, organizationName.strip(), timezone);
        TeamView team = teams.create(tenant.id(), DEFAULT_TEAM);
        assignments.updateSettings(tenant.id(), team.id(), true);
        UserView admin = users.create(tenant.id(), new NewUser(email, adminName, password, Role.ADMIN, null, 0));
        log.info("Signup: created tenant {} ({})", tenant.id(), tenant.slug());
        return new SignedIn(admin, tenant, issue(admin));
    }

    @Transactional
    public SignedIn acceptInvitation(String token, String displayName, String password) {
        UserView user = invitations.accept(token, displayName, password);
        TenantView tenant = tenants.get(user.tenantId());
        log.info("Invitation accepted: user {} joined tenant {}", user.id(), tenant.id());
        return new SignedIn(user, tenant, issue(user));
    }

    record InvitationPreview(String tenantName, String tenantSlug, String email, Role role) {}

    @Transactional(readOnly = true)
    public InvitationPreview preview(String token) {
        InvitationService.Preview p = invitations.preview(token)
                .orElseThrow(() -> new NotFoundException("Invitation", "token"));
        TenantView tenant = tenants.get(p.tenantId());
        return new InvitationPreview(tenant.name(), tenant.slug(), p.email(), p.role());
    }

    private JwtTokenService.IssuedToken issue(UserView user) {
        return tokens.issue(new AuthenticatedUser(user.id(), user.tenantId(), user.role()));
    }
}
