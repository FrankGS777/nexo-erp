alter table purchase add column inventory_status varchar(20) not null default 'RECEIVED';
alter table purchase add column inventory_received_at timestamp with time zone;
alter table purchase add column inventory_received_by bigint references app_user(id);

update purchase
set inventory_received_at = created_at,
    inventory_received_by = created_by
where exists (
    select 1 from inventory_movement movement
    where movement.reference_type = 'PURCHASE'
      and movement.reference_id = cast(purchase.id as varchar)
      and movement.movement_type = 'PURCHASE_IN'
);

create index idx_purchase_inventory_status on purchase(inventory_status);
