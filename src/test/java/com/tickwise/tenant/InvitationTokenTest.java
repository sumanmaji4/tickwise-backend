package com.tickwise.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvitationTokenTest {

    @Test
    void tokensAreUrlSafeAnd256Bit() {
        String token = InvitationService.newToken();
        assertThat(token).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, base64url without padding
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(InvitationService.newToken())).isTrue();
        }
    }

    @Test
    void onlyAStableHashIsStored() {
        String token = InvitationService.newToken();
        assertThat(InvitationService.hash(token)).hasSize(64).isEqualTo(InvitationService.hash(token)).doesNotContain(token);
        assertThat(InvitationService.hash(token)).isNotEqualTo(InvitationService.hash(token + "x"));
    }

    @Test
    void acceptLinkPointsAtTheFrontend() {
        assertThat(InvitationLinks.acceptUrl("https://desk.example/", "abc"))
                .isEqualTo("https://desk.example/accept-invite?token=abc");
    }
}
