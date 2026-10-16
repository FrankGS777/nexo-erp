alter table sale_line add column igv_affectation_code varchar(2) not null default '10';
alter table sale_line add column tax_rate numeric(6, 3) not null default 18;
alter table sale_line add column sunat_product_code varchar(8);

create index idx_sale_payment_condition on sale(payment_condition);
create index idx_sale_due_date on sale(due_date);
