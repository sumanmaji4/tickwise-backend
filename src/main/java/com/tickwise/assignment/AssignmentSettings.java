package com.tickwise.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "assignment_settings")
class AssignmentSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "default_team_id")
    private UUID defaultTeamId;

    @Column(name = "auto_assign", nullable = false)
    private boolean autoAssign;

    @Version
    private Long version;

    protected AssignmentSettings() {}

    AssignmentSettings(UUID tenantId) {
        this.tenantId = tenantId;
        this.autoAssign = true;
    }

    void update(UUID defaultTeamId, boolean autoAssign) {
        this.defaultTeamId = defaultTeamId;
        this.autoAssign = autoAssign;
    }

    AssignmentSettingsView toView() {
        return new AssignmentSettingsView(defaultTeamId, autoAssign);
    }
}
