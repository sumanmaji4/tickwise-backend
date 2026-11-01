package com.tickwise.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickwise.shared.security.Role;
import com.tickwise.support.IntegrationTest;
import com.tickwise.tenant.TenantView;
import com.tickwise.tenant.UserView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles({"test", "seed"})
@TestPropertySource(properties = "tickwise.seed.password=" + IntegrationTest.PASSWORD)
class SeedDataIT extends IntegrationTest {

    @Autowired SeedDataRunner runner;

    @Test
    void seedsTwoTenantsWhoseUsersCanLogIn() throws Exception {
        TenantView acme = tenantService.findBySlug("acme").orElseThrow();
        TenantView globex = tenantService.findBySlug("globex").orElseThrow();

        assertThat(userService.list(acme.id())).extracting(UserView::role).contains(Role.ADMIN, Role.MANAGER, Role.AGENT);
        assertThat(userService.list(globex.id())).isNotEmpty();
        assertThat(login(acme, "admin@acme.test", PASSWORD)).startsWith("Bearer ");
    }

    @Test
    void runningAgainIsANoOp() {
        TenantView acme = tenantService.findBySlug("acme").orElseThrow();
        int before = userService.list(acme.id()).size();

        runner.run(new DefaultApplicationArguments());

        assertThat(userService.list(acme.id())).hasSize(before);
    }
}
