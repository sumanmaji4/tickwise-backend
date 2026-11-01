-- In-app notifications (notification module).
create table notifications (
    id          uuid primary key,
    tenant_id   uuid         not null,
    user_id     uuid         not null,
    type        varchar(30)  not null,
    ticket_id   bigint,
    message     varchar(500) not null,
    created_at  timestamptz  not null,
    read_at     timestamptz,
    constraint fk_notifications_user foreign key (tenant_id, user_id) references users (tenant_id, id)
);

create index ix_notifications_user on notifications (tenant_id, user_id, created_at desc);

-- Reporting read model (reporting module), maintained from domain events so that reports never
-- query other modules' tables. Durations are business time in seconds.
create table ticket_metrics (
    tenant_id                uuid        not null,
    ticket_id                bigint      not null,
    team_id                  uuid,
    assignee_id              uuid,
    priority                 varchar(10) not null,
    channel                  varchar(10) not null,
    is_open                  boolean     not null,
    created_at               timestamptz not null,
    first_responded_at       timestamptz,
    resolved_at              timestamptz,
    first_response_seconds   bigint,
    resolution_seconds       bigint,
    first_response_breached  boolean     not null default false,
    resolution_breached      boolean     not null default false,
    primary key (tenant_id, ticket_id)
);

create index ix_ticket_metrics_created on ticket_metrics (tenant_id, created_at);
create index ix_ticket_metrics_open on ticket_metrics (tenant_id, assignee_id) where is_open;
