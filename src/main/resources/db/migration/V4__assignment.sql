-- Assignment module. See docs/adr/0004-assignment-locking.md.

create table assignment_settings (
    tenant_id        uuid primary key references tenants (id),
    default_team_id  uuid,
    auto_assign      boolean not null,
    version          bigint  not null,
    constraint fk_assignment_settings_team foreign key (tenant_id, default_team_id) references teams (tenant_id, id)
);

-- One row per team: the round-robin cursor, and the row locked (SELECT ... FOR UPDATE) to
-- serialise assignments into that team so capacity checks cannot race.
create table team_assignment_cursors (
    tenant_id              uuid not null,
    team_id                uuid not null,
    last_assigned_user_id  uuid,
    primary key (tenant_id, team_id),
    constraint fk_cursor_team foreign key (tenant_id, team_id) references teams (tenant_id, id)
);
