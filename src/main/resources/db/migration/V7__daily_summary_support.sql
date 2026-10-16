alter table electronic_document add column summary_reference varchar(40);
alter table electronic_document add column summary_xml_object_key varchar(500);
alter table electronic_document add column summary_xml_sha256 varchar(64);

insert into document_series (branch_id, document_type, series, next_number)
select id, '07', 'BC01', 1 from branch where code = '0000';

insert into document_series (branch_id, document_type, series, next_number)
select id, '08', 'BD01', 1 from branch where code = '0000';
