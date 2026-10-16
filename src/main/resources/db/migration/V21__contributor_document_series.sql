-- E-prefixed portal series must not be allocated by the contributor's software.
-- Existing documents and their identifiers are not rewritten.
update document_series set active=false where series like 'E%';
insert into document_series(branch_id,document_type,series,next_number)
select id,'01','F001',1 from branch where code='0000'
and not exists(select 1 from document_series where document_type='01' and series='F001');
insert into document_series(branch_id,document_type,series,next_number)
select id,'07','FC01',1 from branch where code='0000'
and not exists(select 1 from document_series where document_type='07' and series='FC01');
insert into document_series(branch_id,document_type,series,next_number)
select id,'08','FD01',1 from branch where code='0000'
and not exists(select 1 from document_series where document_type='08' and series='FD01');
