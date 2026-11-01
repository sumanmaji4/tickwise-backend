-- SLA configuration, owned by the sla module.

create table sla_policies (
    id                      uuid primary key,
    tenant_id               uuid        not null references tenants (id),
    priority                varchar(10) not null,
    first_response_minutes  integer     not null,
    resolution_minutes      integer     not null,
    version                 bigint      not null,
    constraint uq_sla_policies_tenant_priority unique (tenant_id, priority),
    constraint ck_sla_policies_priority check (priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    constraint ck_sla_policies_minutes check (first_response_minutes between 1 and 525600
                                              and resolution_minutes between 1 and 525600)
);

-- Working windows in the tenant's local time. end_time <= start_time means the window runs past midnight.
create table business_hours (
    id           uuid primary key,
    tenant_id    uuid     not null references tenants (id),
    day_of_week  integer  not null,
    start_time   time     not null,
    end_time     time     not null,
    constraint ck_business_hours_day check (day_of_week between 1 and 7)
);

create index ix_business_hours_tenant on business_hours (tenant_id);

create table holidays (
    id            uuid primary key,
    tenant_id     uuid         not null references tenants (id),
    holiday_date  date         not null,
    name          varchar(100) not null,
    constraint uq_holidays_tenant_date unique (tenant_id, holiday_date)
);
