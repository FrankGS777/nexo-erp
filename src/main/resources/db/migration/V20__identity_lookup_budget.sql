create table identity_lookup_budget (
    id integer primary key,
    month_key varchar(7) not null,
    requests integer not null
);
insert into identity_lookup_budget(id,month_key,requests) values(1,'0000-00',0);
