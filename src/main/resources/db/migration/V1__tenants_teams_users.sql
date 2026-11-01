-- Tenancy and identity.
-- Every tenant-owned table carries tenant_id. Cross-table references include tenant_id in the
-- foreign key (composite FK onto a (tenant_id, id) unique key), so the database itself rejects
-- a row that points at another tenant's data even if application code has a bug.

create table tenants (
    id          uuid primary key,
    slug        varchar(63)  not null,
    name        varchar(200) not null,
    timezone    varchar(64)  not null,
    created_at  timestamptz  not null,
    version     bigint       not null,
    constraint uq_tenants_slug unique (slug),
    constraint ck_tenants_slug check (slug ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$')
);

create table teams (
    id          uuid primary key,
    tenant_id   uuid         not null references tenants (id),
    name        varchar(100) not null,
    created_at  timestamptz  not null,
    version     bigint       not null,
    constraint uq_teams_tenant_id unique (tenant_id, id),
    constraint uq_teams_tenant_name unique (tenant_id, name)
);

create table users (
    id                uuid primary key,
    tenant_id         uuid         not null references tenants (id),
    email             varchar(320) not null,
    display_name      varchar(200) not null,
    password_hash     varchar(100) not null,
    role              varchar(20)  not null,
    team_id           uuid,
    max_open_tickets  integer      not null,
    active            boolean      not null,
    created_at        timestamptz  not null,
    version           bigint       not null,
    constraint uq_users_tenant_id unique (tenant_id, id),
    constraint uq_users_tenant_email unique (tenant_id, email),
    constraint ck_users_role check (role in ('CUSTOMER', 'AGENT', 'MANAGER', 'ADMIN')),
    constraint ck_users_email_lower check (email = lower(email)),
    constraint ck_users_capacity check (max_open_tickets between 0 and 1000),
    constraint fk_users_team foreign key (tenant_id, team_id) references teams (tenant_id, id)
);

create index ix_users_tenant_team on users (tenant_id, team_id);
