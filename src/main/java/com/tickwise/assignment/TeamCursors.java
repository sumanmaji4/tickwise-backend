package com.tickwise.assignment;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The per-team round-robin cursor, which doubles as the team's assignment lock.
 * {@link #lock} must be called inside a transaction; the row lock is held until it ends.
 */
@Repository
class TeamCursors {

    private final JdbcTemplate jdbc;

    TeamCursors(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the team's cursor row ({@code SELECT ... FOR UPDATE}), creating it on first use, and
     * returns the agent who received the team's previous ticket (or null). Concurrent assignments
     * into the same team queue here, so their capacity checks see each other's results.
     */
    UUID lock(UUID tenantId, UUID teamId) {
        jdbc.update("""
                insert into team_assignment_cursors (tenant_id, team_id) values (?, ?)
                on conflict (tenant_id, team_id) do nothing""", tenantId, teamId);
        List<UUID> last = jdbc.query("""
                select last_assigned_user_id from team_assignment_cursors
                where tenant_id = ? and team_id = ? for update""",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, teamId);
        return last.isEmpty() ? null : last.getFirst();
    }

    void advance(UUID tenantId, UUID teamId, UUID assignedTo) {
        jdbc.update("update team_assignment_cursors set last_assigned_user_id = ? where tenant_id = ? and team_id = ?",
                assignedTo, tenantId, teamId);
    }
}
