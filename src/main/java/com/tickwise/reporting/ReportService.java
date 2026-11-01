package com.tickwise.reporting;

import com.tickwise.reporting.ReportViews.AgentRow;
import com.tickwise.reporting.ReportViews.Daily;
import com.tickwise.reporting.ReportViews.DailyPoint;
import com.tickwise.reporting.ReportViews.GroupRow;
import com.tickwise.reporting.ReportViews.Metrics;
import com.tickwise.shared.security.Role;
import com.tickwise.tenant.TeamService;
import com.tickwise.tenant.TeamView;
import com.tickwise.tenant.TenantService;
import com.tickwise.tenant.UserService;
import com.tickwise.tenant.UserView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performance reports over the {@code ticket_metrics} projection. "Created in range" defines
 * the cohort; open counts are current. A breach rate's denominator is the number of tickets
 * whose target was decided (met, or breached).
 */
@Service
public class ReportService {

    /** Aggregate columns shared by every grouping; {@code %s} is the grouping column or a constant. */
    private static final String AGGREGATES = """
            select %s as group_key,
                   count(*) filter (where created_at >= ? and created_at < ?) as created,
                   count(*) filter (where is_open) as open,
                   avg(first_response_seconds) filter (where created_at >= ? and created_at < ?) as fr_avg,
                   percentile_cont(0.5) within group (order by first_response_seconds)
                       filter (where created_at >= ? and created_at < ?) as fr_median,
                   avg(resolution_seconds) filter (where created_at >= ? and created_at < ?) as res_avg,
                   percentile_cont(0.5) within group (order by resolution_seconds)
                       filter (where created_at >= ? and created_at < ?) as res_median,
                   count(*) filter (where created_at >= ? and created_at < ?
                                    and (first_response_seconds is not null or first_response_breached)) as fr_decided,
                   count(*) filter (where created_at >= ? and created_at < ? and first_response_breached) as fr_breached,
                   count(*) filter (where created_at >= ? and created_at < ?
                                    and (resolution_seconds is not null or resolution_breached)) as res_decided,
                   count(*) filter (where created_at >= ? and created_at < ? and resolution_breached) as res_breached,
                   count(*) filter (where created_at >= ? and created_at < ?
                                    and (first_response_seconds is not null or resolution_seconds is not null
                                         or first_response_breached or resolution_breached)) as any_decided,
                   count(*) filter (where created_at >= ? and created_at < ?
                                    and (first_response_breached or resolution_breached)) as any_breached
            from ticket_metrics
            where tenant_id = ?
            """;
    /** Every placeholder in AGGREGATES is a (from, to) pair except the final tenant id; counted, not hand-maintained. */
    private static final int RANGE_PAIRS = (int) (AGGREGATES.chars().filter(c -> c == '?').count() - 1) / 2;

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final TeamService teams;
    private final UserService users;

    ReportService(JdbcTemplate jdbc, TenantService tenants, TeamService teams, UserService users) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.teams = teams;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public ReportViews.Summary summary(UUID tenantId, Instant from, Instant to) {
        List<Metrics> rows = jdbc.query(AGGREGATES.formatted("null"), (rs, i) -> metrics(rs), params(tenantId, from, to));
        return new ReportViews.Summary(from, to, rows.getFirst());
    }

    @Transactional(readOnly = true)
    public List<GroupRow> byTeam(UUID tenantId, Instant from, Instant to) {
        Map<UUID, Metrics> rows = grouped("team_id", tenantId, from, to);
        List<GroupRow> result = new ArrayList<>();
        for (TeamView team : teams.list(tenantId)) {
            result.add(new GroupRow(team.id(), team.name(), rows.getOrDefault(team.id(), empty())));
        }
        if (rows.containsKey(null)) {
            result.add(new GroupRow(null, "No team", rows.get(null)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AgentRow> byAgent(UUID tenantId, Instant from, Instant to) {
        Map<UUID, Metrics> rows = grouped("assignee_id", tenantId, from, to);
        return users.activeWithRoles(tenantId, Set.of(Role.AGENT, Role.MANAGER)).stream()
                .sorted(Comparator.comparing(UserView::displayName))
                .map(u -> new AgentRow(u.id(), u.displayName(), u.teamId(), u.maxOpenTickets(),
                        rows.getOrDefault(u.id(), empty())))
                .toList();
    }

    /** Tickets created and breached per calendar day in the tenant's timezone. */
    @Transactional(readOnly = true)
    public Daily daily(UUID tenantId, Instant from, Instant to) {
        String zone = tenants.get(tenantId).timezone().getId();
        List<DailyPoint> points = jdbc.query("""
                select (created_at at time zone ?)::date as day, count(*) as created,
                       count(*) filter (where first_response_breached or resolution_breached) as breached
                from ticket_metrics
                where tenant_id = ? and created_at >= ? and created_at < ?
                group by day order by day""",
                (rs, i) -> new DailyPoint(rs.getObject("day", java.time.LocalDate.class), rs.getLong("created"),
                        rs.getLong("breached")),
                zone, tenantId, Timestamp.from(from), Timestamp.from(to));
        return new Daily(from, to, zone, points);
    }

    private Map<UUID, Metrics> grouped(String column, UUID tenantId, Instant from, Instant to) {
        Map<UUID, Metrics> result = new HashMap<>();
        jdbc.query(AGGREGATES.formatted(column) + " group by " + column,
                rs -> {
                    result.put(rs.getObject("group_key", UUID.class), metrics(rs));
                },
                params(tenantId, from, to));
        return result;
    }

    private static Object[] params(UUID tenantId, Instant from, Instant to) {
        Object[] p = new Object[RANGE_PAIRS * 2 + 1];
        for (int i = 0; i < RANGE_PAIRS; i++) {
            p[2 * i] = Timestamp.from(from);
            p[2 * i + 1] = Timestamp.from(to);
        }
        p[p.length - 1] = tenantId;
        return p;
    }

    private static Metrics metrics(ResultSet rs) throws SQLException {
        return new Metrics(rs.getLong("created"), rs.getLong("open"),
                minutes(rs, "fr_avg"), minutes(rs, "fr_median"), minutes(rs, "res_avg"), minutes(rs, "res_median"),
                rate(rs.getLong("fr_breached"), rs.getLong("fr_decided")),
                rate(rs.getLong("res_breached"), rs.getLong("res_decided")),
                rate(rs.getLong("any_breached"), rs.getLong("any_decided")));
    }

    private static Double minutes(ResultSet rs, String column) throws SQLException {
        double seconds = rs.getDouble(column);
        return rs.wasNull() ? null : Math.round(seconds / 60.0 * 10) / 10.0;
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static Metrics empty() {
        return new Metrics(0, 0, null, null, null, null, null, null, null);
    }
}
