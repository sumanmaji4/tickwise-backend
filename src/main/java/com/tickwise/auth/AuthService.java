package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.tenant.UserCredentials;
import com.tickwise.tenant.UserService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    record LoginResult(AuthenticatedUser user, JwtTokenService.IssuedToken token) {}

    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    // Compared against when the user does not exist, so both paths cost one BCrypt check and
    // response timing does not reveal which emails are registered.
    private final String dummyHash;

    AuthService(UserService users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    LoginResult login(String tenantSlug, String email, String password) {
        Optional<UserCredentials> found = users.findCredentials(tenantSlug, email);
        boolean passwordMatches = passwordEncoder.matches(password, found.map(UserCredentials::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !passwordMatches || !found.get().active()) {
            log.info("Login failed for tenant slug '{}'", tenantSlug);
            throw new InvalidCredentialsException();
        }
        UserCredentials credentials = found.get();
        AuthenticatedUser user = new AuthenticatedUser(credentials.userId(), credentials.tenantId(), credentials.role());
        log.info("Login succeeded for user {} in tenant {}", user.userId(), user.tenantId());
        return new LoginResult(user, tokens.issue(user));
    }
}
