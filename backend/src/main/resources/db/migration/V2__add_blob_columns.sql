-- v2: blobs. The phone can now share files, not just text.
alter table share_items add column storage_ref varchar(255);
alter table share_items add column mime_type    varchar(128);
alter table share_items add column size_bytes   bigint;

-- "no blobs without an id on disk, no id without a blob" — the text half of the table is exempt
create index idx_share_items_storage_ref on share_items (storage_ref) where storage_ref is not null;
