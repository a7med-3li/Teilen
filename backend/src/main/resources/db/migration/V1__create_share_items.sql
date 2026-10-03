-- v1: the only table in the product. Everything expires, nothing is kept.
create table share_items
(
    id         uuid primary key,
    type       varchar(16)  not null,
    content    text         not null,
    created_at timestamptz  not null,
    expires_at timestamptz  not null
);

create index idx_share_items_expires_at on share_items (expires_at);
create index idx_share_items_created_at on share_items (created_at desc);
