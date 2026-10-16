alter table product add column estimate_basis text;
alter table commercial_order add column stock_reserved boolean not null default false;
update commercial_order set stock_reserved=true where status in ('CONFIRMED','PARTIAL','PREPARED');
