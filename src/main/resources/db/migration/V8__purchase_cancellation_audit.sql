alter table purchase add column updated_at timestamp with time zone not null default current_timestamp;
alter table purchase add column cancelled_at timestamp with time zone;
alter table purchase add column cancelled_by bigint references app_user(id);
alter table purchase add column cancellation_reason varchar(500);

update purchase set supplier_series = '' where supplier_series is null;
alter table purchase alter column supplier_series set default '';
alter table purchase alter column supplier_series set not null;

create index idx_purchase_status on purchase(status);
