package com.tickwise.tenant;

import com.tickwise.shared.security.Role;
import java.util.UUID;

/** Input for {@link UserService#create}. {@code rawPassword} is hashed and then discarded. */
public record NewUser(String email, String displayName, String rawPassword, Role role, UUID teamId, int maxOpenTickets) {

    @Override
    public String toString() {
        return "NewUser[email=" + email + ", role=" + role + ", teamId=" + teamId + "]";
    }
}
