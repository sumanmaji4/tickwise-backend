package com.tickwise.tenant;

/** Builds the frontend accept-invite link; shared by the admin API response and the invite email. */
public final class InvitationLinks {

    private InvitationLinks() {}

    public static String acceptUrl(String frontendUrl, String token) {
        return frontendUrl.replaceAll("/+$", "") + "/accept-invite?token=" + token;
    }
}
