package com.tickwise.shared.security;

/** Per-tenant role of a principal. Spring authorities are {@code ROLE_<name>}. */
public enum Role {
    CUSTOMER,
    AGENT,
    MANAGER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
