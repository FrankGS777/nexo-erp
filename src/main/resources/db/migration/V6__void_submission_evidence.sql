alter table fiscal_action_request add column file_name varchar(120);
alter table fiscal_action_request add column xml_object_key varchar(500);
alter table fiscal_action_request add column xml_sha256 varchar(64);
alter table fiscal_action_request add column signed_xml_object_key varchar(500);
alter table fiscal_action_request add column signed_xml_sha256 varchar(64);
alter table fiscal_action_request add column cdr_object_key varchar(500);
alter table fiscal_action_request add column cdr_sha256 varchar(64);
alter table fiscal_action_request add column submitted_at timestamp with time zone;
