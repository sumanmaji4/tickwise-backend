-- Tickets, conversation and audit (ticket module) plus per-ticket SLA clocks (sla module).

create table customers (
    id          uuid primary key,
    tenant_id   uuid         not null references tenants (id),
    email       varchar(320) not null,
    name        varchar(200),
    created_at  timestamptz  not null,
    constraint uq_customers_tenant_id unique (tenant_id, id),
    constraint uq_customers_tenant_email unique (tenant_id, email),
    constraint ck_customers_email_lower check (email = lower(email))
);

create table tickets (
    id                      bigint generated always as identity primary key,
    tenant_id               uuid         not null references tenants (id),
    subject                 varchar(300) not null,
    status                  varchar(20)  not null,
    priority                varchar(10)  not null,
    channel                 varchar(10)  not null,
    customer_id             uuid         not null,
    assignee_id             uuid,
    team_id                 uuid,
    first_response_due_at   timestamptz,
    resolution_due_at       timestamptz,
    -- earliest active deadline / warning, denormalised for "sort by SLA due" and badge colours
    next_sla_due_at         timestamptz,
    next_sla_warning_at     timestamptz,
    first_responded_at      timestamptz,
    resolved_at             timestamptz,
    sla_breached            boolean      not null default false,
    created_at              timestamptz  not null,
    updated_at              timestamptz  not null,
    version                 bigint       not null,
    constraint uq_tickets_tenant_id unique (tenant_id, id),
    constraint ck_tickets_status check (status in ('NEW', 'OPEN', 'PENDING_CUSTOMER', 'RESOLVED', 'CLOSED')),
    constraint ck_tickets_priority check (priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    constraint ck_tickets_channel check (channel in ('EMAIL', 'WEB')),
    constraint fk_tickets_customer foreign key (tenant_id, customer_id) references customers (tenant_id, id),
    constraint fk_tickets_assignee foreign key (tenant_id, assignee_id) references users (tenant_id, id),
    constraint fk_tickets_team foreign key (tenant_id, team_id) references teams (tenant_id, id)
);

create index ix_tickets_queue on tickets (tenant_id, status, next_sla_due_at);
create index ix_tickets_assignee_open on tickets (tenant_id, assignee_id) where status in ('NEW', 'OPEN', 'PENDING_CUSTOMER');
create index ix_tickets_team on tickets (tenant_id, team_id);

create table messages (
    id                  uuid primary key,
    tenant_id           uuid        not null,
    ticket_id           bigint      not null,
    direction           varchar(10) not null,
    author_user_id      uuid,
    author_customer_id  uuid,
    body                text        not null,
    email_message_id    varchar(998),
    created_at          timestamptz not null,
    constraint uq_messages_tenant_id unique (tenant_id, id),
    constraint ck_messages_direction check (direction in ('INBOUND', 'OUTBOUND')),
    constraint ck_messages_author check ((author_user_id is null) <> (author_customer_id is null)),
    constraint fk_messages_ticket foreign key (tenant_id, ticket_id) references tickets (tenant_id, id),
    constraint fk_messages_user foreign key (tenant_id, author_user_id) references users (tenant_id, id),
    constraint fk_messages_customer foreign key (tenant_id, author_customer_id) references customers (tenant_id, id)
);

create index ix_messages_ticket on messages (tenant_id, ticket_id, created_at);
-- Idempotency for inbound email and the lookup used by In-Reply-To / References threading.
create unique index uq_messages_email_message_id on messages (tenant_id, email_message_id) where email_message_id is not null;

create table attachments (
    id            uuid primary key,
    tenant_id     uuid         not null,
    message_id    uuid         not null,
    filename      varchar(255) not null,
    content_type  varchar(255) not null,
    size_bytes    bigint       not null,
    storage_key   varchar(512) not null,
    created_at    timestamptz  not null,
    constraint fk_attachments_message foreign key (tenant_id, message_id) references messages (tenant_id, id)
);

create index ix_attachments_message on attachments (tenant_id, message_id);

-- Append-only audit trail of every status, priority, assignment and SLA change.
create table ticket_audit (
    id              bigint generated always as identity primary key,
    tenant_id       uuid        not null,
    ticket_id       bigint      not null,
    actor_user_id   uuid,
    type            varchar(40) not null,
    details         jsonb       not null,
    created_at      timestamptz not null,
    constraint fk_ticket_audit_ticket foreign key (tenant_id, ticket_id) references tickets (tenant_id, id)
);

create index ix_ticket_audit_ticket on ticket_audit (tenant_id, ticket_id, id);

create function forbid_audit_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'ticket_audit is append-only';
end;
$$;

create trigger trg_ticket_audit_append_only
    before update or delete on ticket_audit
    for each row execute function forbid_audit_mutation();

-- One row per ticket and SLA metric. Deadlines are derived from these fields; see SlaClock.
create table ticket_sla (
    id             uuid primary key,
    tenant_id      uuid        not null,
    ticket_id      bigint      not null,
    metric         varchar(20) not null,
    target_ms      bigint      not null,
    consumed_ms    bigint      not null,
    running_since  timestamptz,
    met_at         timestamptz,
    warned_at      timestamptz,
    breached_at    timestamptz,
    version        bigint      not null,
    constraint uq_ticket_sla unique (tenant_id, ticket_id, metric),
    constraint ck_ticket_sla_metric check (metric in ('FIRST_RESPONSE', 'RESOLUTION')),
    constraint ck_ticket_sla_state check (running_since is null or met_at is null),
    constraint fk_ticket_sla_ticket foreign key (tenant_id, ticket_id) references tickets (tenant_id, id)
);

create index ix_ticket_sla_running on ticket_sla (running_since) where running_since is not null;
