create table merchant_rate_limit (
    merchant_id text not null,
    operation text not null,
    sustained_limit integer not null,
    burst_limit integer not null default 90,
    window_seconds integer not null default 60,
    policy_version text not null default 'limits-2026-b',
    primary key (merchant_id, operation)
);

create unique index merchant_rate_limit_active_idx
    on merchant_rate_limit (merchant_id, operation, policy_version);

insert into merchant_rate_limit
    (merchant_id, operation, sustained_limit, burst_limit, window_seconds, policy_version)
values
    ('sandbox-merchant-17', 'charge', 120, 90, 60, 'limits-2026-b'),
    ('sandbox-merchant-17', 'refund', 35, 12, 60, 'limits-2026-b'),
    ('merchant-enterprise-8', 'charge', 850, 180, 60, 'limits-2026-b');

create or replace function remaining_burst(p_merchant text, p_operation text)
returns integer
language sql
as $$
    select greatest(burst_limit - (select count(*) from recent_requests
                                   where merchant_id = p_merchant and operation = p_operation), 0)
    from merchant_rate_limit
    where merchant_id = p_merchant and operation = p_operation;
$$;
