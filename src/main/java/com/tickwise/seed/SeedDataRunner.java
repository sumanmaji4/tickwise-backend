package com.tickwise.seed;

import com.tickwise.assignment.AssignmentService;
import com.tickwise.shared.domain.Priority;
import com.tickwise.shared.security.Role;
import com.tickwise.sla.BusinessCalendarService;
import com.tickwise.tenant.NewUser;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.TeamView;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.TenantView;
import com.tickwise.tenant.UserService;
import com.tickwise.ticket.Channel;
import com.tickwise.ticket.TicketService;
import com.tickwise.ticket.TicketStatus;
import com.tickwise.ticket.TicketView;
import com.tickwise.ticket.intake.InboundMessage;
import com.tickwise.ticket.intake.IntakeService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo data for {@code docker compose up}: two tenants in different timezones with an admin, a
 * manager, agents, routing settings and a realistic queue of tickets in different states.
 * Idempotent per tenant (a tenant whose slug exists is skipped). Uses only the modules' public
 * services, exactly as API clients would, so every ticket has real SLA clocks, audit entries,
 * escalation jobs and acknowledgement emails.
 */
@Component
@Profile("seed")
class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private record SeedTenant(String slug, String name, String timezone, List<String> teams, List<SeedUser> users,
                              List<SeedTicket> tickets) {}

    private record SeedUser(String localPart, String displayName, Role role, String team, int capacity) {}

    /** {@code replyAndThen}: null = untouched, otherwise an agent replies and moves the ticket to that status. */
    private record SeedTicket(String customer, String customerName, Channel channel, String subject, String body,
                              Priority priority, TicketStatus replyAndThen) {}

    private static final List<SeedTenant> TENANTS = List.of(
            new SeedTenant("acme", "Acme Corp", "America/New_York", List.of("Support", "Billing"), List.of(
                    new SeedUser("admin", "Avery Admin", Role.ADMIN, null, 0),
                    new SeedUser("manager", "Morgan Manager", Role.MANAGER, "Support", 0),
                    new SeedUser("alex", "Alex Agent", Role.AGENT, "Support", 10),
                    new SeedUser("sam", "Sam Agent", Role.AGENT, "Support", 10),
                    new SeedUser("jordan", "Jordan Agent", Role.AGENT, "Billing", 5)), List.of(
                    new SeedTicket("dana@globalfreight.test", "Dana Whitfield", Channel.EMAIL, "Checkout returns 500 for all EU customers",
                            "Since 08:00 every EU order fails at payment with an internal error.", Priority.URGENT, null),
                    new SeedTicket("lee@brightdental.test", "Lee Park", Channel.WEB, "Invoice #8841 charged twice",
                            "We were billed twice for October. Please refund one charge.", Priority.HIGH, TicketStatus.PENDING_CUSTOMER),
                    new SeedTicket("sasha@northwind.test", "Sasha Ivanova", Channel.EMAIL, "SSO login loop on mobile",
                            "After the update, the iOS app keeps redirecting to the login page.", Priority.HIGH, null),
                    new SeedTicket("omar@cityparks.test", "Omar Haddad", Channel.WEB, "How do I export reports to CSV?",
                            "Is there a way to export the monthly usage report?", Priority.LOW, TicketStatus.RESOLVED),
                    new SeedTicket("ana@lumen.test", "Ana Souza", Channel.EMAIL, "Webhook deliveries delayed",
                            "Our webhook endpoint receives events 10+ minutes late since yesterday.", Priority.MEDIUM, TicketStatus.OPEN),
                    new SeedTicket("li@harbor.test", "Li Wei", Channel.WEB, "Add a second admin to our account",
                            "Please give admin rights to li.wei@harbor.test.", Priority.LOW, null),
                    new SeedTicket("ravi@zenbank.test", "Ravi Nair", Channel.EMAIL, "API rate limit errors (429)",
                            "We hit 429s at 20 requests per second; our contract says 50.", Priority.MEDIUM, null),
                    new SeedTicket("mia@petal.test", "Mia Rossi", Channel.WEB, "Cannot change billing address",
                            "The billing form does not save my new address.", Priority.MEDIUM, TicketStatus.PENDING_CUSTOMER))),
            new SeedTenant("globex", "Globex Ltd", "Europe/Berlin", List.of("Support"), List.of(
                    new SeedUser("admin", "Robin Admin", Role.ADMIN, null, 0),
                    new SeedUser("manager", "Casey Manager", Role.MANAGER, "Support", 0),
                    new SeedUser("riley", "Riley Agent", Role.AGENT, "Support", 8),
                    new SeedUser("taylor", "Taylor Agent", Role.AGENT, "Support", 8)), List.of(
                    new SeedTicket("jonas@muellerbau.test", "Jonas Müller", Channel.EMAIL, "Lieferstatus fehlt im Portal",
                            "Im Kundenportal wird seit gestern kein Lieferstatus angezeigt.", Priority.HIGH, null),
                    new SeedTicket("eva@kleinmode.test", "Eva Klein", Channel.WEB, "Password reset email never arrives",
                            "I requested a reset three times; nothing in inbox or spam.", Priority.MEDIUM, TicketStatus.PENDING_CUSTOMER),
                    new SeedTicket("piet@vanderberg.test", "Piet van der Berg", Channel.EMAIL, "Data export for GDPR request",
                            "Please send all personal data you hold about me.", Priority.MEDIUM, null),
                    new SeedTicket("lena@fischer.test", "Lena Fischer", Channel.WEB, "Dark mode request",
                            "Would love a dark theme in the dashboard!", Priority.LOW, TicketStatus.RESOLVED))));

    private final TenantService tenants;
    private final TeamService teams;
    private final UserService users;
    private final AssignmentService assignments;
    private final BusinessCalendarService calendars;
    private final IntakeService intake;
    private final TicketService tickets;
    private final String password;

    SeedDataRunner(TenantService tenants, TeamService teams, UserService users, AssignmentService assignments,
                   BusinessCalendarService calendars, IntakeService intake, TicketService tickets,
                   @Value("${tickwise.seed.password:}") String password) {
        this.tenants = tenants;
        this.teams = teams;
        this.users = users;
        this.assignments = assignments;
        this.calendars = calendars;
        this.intake = intake;
        this.tickets = tickets;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password.length() < 12) {
            throw new IllegalStateException("The seed profile requires TICKWISE_SEED_PASSWORD (at least 12 characters)");
        }
        for (SeedTenant seed : TENANTS) {
            if (tenants.findBySlug(seed.slug()).isPresent()) {
                log.info("Seed: tenant '{}' already exists, skipping", seed.slug());
                continue;
            }
            seedTenant(seed);
        }
    }

    private void seedTenant(SeedTenant seed) {
        TenantView tenant = tenants.create(seed.slug(), seed.name(), seed.timezone());
        List<TeamView> created = seed.teams().stream().map(name -> teams.create(tenant.id(), name)).toList();
        for (SeedUser u : seed.users()) {
            UUID teamId = created.stream().filter(t -> t.name().equals(u.team())).map(TeamView::id).findFirst().orElse(null);
            String email = u.localPart() + "@" + seed.slug() + ".test";
            users.create(tenant.id(), new NewUser(email, u.displayName(), password, u.role(), teamId, u.capacity()));
        }
        int year = LocalDate.now().getYear();
        calendars.addHoliday(tenant.id(), LocalDate.of(year, 12, 25), "Christmas Day");
        calendars.addHoliday(tenant.id(), LocalDate.of(year + 1, 1, 1), "New Year's Day");
        // New tickets go to the first team and are distributed round-robin.
        assignments.updateSettings(tenant.id(), created.getFirst().id(), true);

        for (SeedTicket t : seed.tickets()) {
            long id = intake.receive(new InboundMessage(tenant.id(), t.channel(), t.customer(), t.customerName(),
                    t.subject(), t.body(), t.channel() == Channel.EMAIL ? "<seed-" + UUID.randomUUID() + "@mail.test>" : null,
                    null, null, null)).ticketId();
            TicketView ticket = tickets.get(tenant.id(), id);
            if (t.priority() != ticket.priority()) {
                ticket = tickets.update(tenant.id(), id, ticket.version(), null, t.priority(), null);
            }
            if (t.replyAndThen() != null && ticket.assigneeId() != null) {
                tickets.reply(tenant.id(), id, ticket.assigneeId(), reply(t), t.replyAndThen());
            }
        }
        log.info("Seed: created tenant '{}' with {} teams, {} users and {} tickets", seed.slug(), created.size(),
                seed.users().size(), seed.tickets().size());
    }

    private static String reply(SeedTicket t) {
        String name = t.customerName().split(" ")[0];
        return switch (t.replyAndThen()) {
            case PENDING_CUSTOMER -> "Hi " + name + ", thanks for reaching out. Could you send us a screenshot and the time it happened?";
            case RESOLVED -> "Hi " + name + ", this is now done. Let us know if anything else comes up!";
            default -> "Hi " + name + ", we're looking into this and will update you shortly.";
        };
    }
}
