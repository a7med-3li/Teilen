-- v5: web push subscriptions.
--
-- One row per browser that agreed to receive a signal when something lands in its account feed.
-- The subscription is the browser's own (endpoint + two public keys minted by the push service);
-- nothing in it is a Teilen secret and nothing here is item content. The server only ever sends a
-- zero-content "something arrived" signal, so a compromised row leaks no shared data.
--
-- Additive: with no rows the pipeline is simply silent, and the phone-side app is unaffected.

create table push_subscriptions
(
    id         uuid primary key,
    user_id    uuid         not null references users (id) on delete cascade,
    endpoint   varchar(2048) not null,
    p256dh     varchar(255)  not null,
    auth       varchar(255)  not null,
    created_at timestamptz   not null,
    -- one browser (identified by its endpoint) subscribes once, however many times it refreshes
    constraint uq_push_subscription_endpoint unique (user_id, endpoint)
);

create index idx_push_subscriptions_user_id on push_subscriptions (user_id);
