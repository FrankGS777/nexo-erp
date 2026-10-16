alter table commercial_dispatch add column carrier_name varchar(220);
alter table commercial_dispatch add column driver_name varchar(160);
alter table commercial_dispatch add column driver_document varchar(20);
alter table commercial_dispatch add column driver_license varchar(20);
alter table commercial_dispatch add column vehicle_brand varchar(60);
alter table commercial_dispatch add column gross_weight numeric(14,3);
alter table commercial_dispatch add column packages integer;
