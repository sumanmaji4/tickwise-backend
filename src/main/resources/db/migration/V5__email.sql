-- Email module. Both tables are durable work queues consumed with FOR UPDATE SKIP LOCKED.
-- See docs/adr/0006-email-intake-and-threading.md.

-- Raw provider payload, stored before the webhook returns 200. Not tenant-scoped yet: the
-- tenant is only known after parsing the recipient address.
create table inbound_emails (
    id               bigint generated always as identity primary key,
    received_at      timestamptz  not null,
    payload          jsonb        not null,
    status           varchar(12)  not null,
    attempts         integer      not null default 0,
    next_attempt_at  timestamptz  not null,
    tenant_id        uuid,
    ticket_id        bigint,
    outcome          varchar(300),
    processed_at     timestamptz,
    constraint ck_inbound_emails_status check (status in ('PENDING', 'PROCESSED', 'IGNORED', 'DUPLICATE', 'FAILED'))
);

create index ix_inbound_emails_pending on inbound_emails (next_attempt_at) where status = 'PENDING';

-- Transactional outbox: a row is written in the same transaction as the reply it delivers.
create table outbound_emails (
    id                bigint generated always as identity primary key,
    tenant_id         uuid          not null references tenants (id),
    ticket_id         bigint        not null,
    message_id        uuid,
    to_address        varchar(320)  not null,
    to_name           varchar(200),
    reply_to          varchar(320)  not null,
    subject           varchar(400)  not null,
    body              text          not null,
    email_message_id  varchar(998)  not null,
    in_reply_to       varchar(998),
    references_header text,
    auto_submitted    boolean       not null,
    status            varchar(10)   not null,
    attempts          integer       not null default 0,
    next_attempt_at   timestamptz   not null,
    last_error        varchar(500),
    created_at        timestamptz   not null,
    sent_at           timestamptz,
    constraint ck_outbound_emails_status check (status in ('PENDING', 'SENT', 'FAILED')),
    constraint fk_outbound_emails_ticket foreign key (tenant_id, ticket_id) references tickets (tenant_id, id)
);

create index ix_outbound_emails_pending on outbound_emails (next_attempt_at) where status = 'PENDING';
