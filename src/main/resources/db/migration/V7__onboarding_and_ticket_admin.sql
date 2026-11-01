-- Org signup and agent invitations (tenant module); agent-created and soft-deleted tickets.

-- An invitation is redeemed with a random token sent by email. Only its SHA-256 hash is stored,
-- so a database leak does not expose usable invite links.
create table invitations (
    id                uuid primary key,
    tenant_id         uuid         not null references tenants (id),
    email             varchar(320) not null,
    role              varchar(20)  not null,
    team_id           uuid,
    max_open_tickets  integer      not null,
    token_hash        varchar(64)  not null,
    invited_by        uuid         not null,
    created_at        timestamptz  not null,
    expires_at        timestamptz  not null,
    accepted_at       timestamptz,
    revoked_at        timestamptz,
    version           bigint       not null,
    constraint uq_invitations_token unique (token_hash),
    constraint ck_invitations_role check (role in ('AGENT', 'MANAGER', 'ADMIN')),
    constraint ck_invitations_email_lower check (email = lower(email)),
    constraint ck_invitations_capacity check (max_open_tickets between 0 and 1000),
    constraint fk_invitations_team foreign key (tenant_id, team_id) references teams (tenant_id, id),
    constraint fk_invitations_inviter foreign key (tenant_id, invited_by) references users (tenant_id, id)
);

-- At most one open invitation per address and tenant.
create unique index uq_invitations_pending on invitations (tenant_id, email)
    where accepted_at is null and revoked_at is null;

-- Invitation emails are not about a ticket.
alter table outbound_emails alter column ticket_id drop not null;

-- Tickets opened by an agent on a customer's behalf (phone, chat, walk-in).
alter table tickets drop constraint ck_tickets_channel;
alter table tickets add constraint ck_tickets_channel check (channel in ('EMAIL', 'WEB', 'AGENT'));

-- Soft delete: the row and its append-only audit trail stay; the ticket disappears from the app.
alter table tickets add column deleted_at timestamptz;
alter table tickets add column deleted_by uuid;
