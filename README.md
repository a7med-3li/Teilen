# Teilen
A personal share-inbox: send anything to yourself from your phone (links, PDFs, photos, text), open it on any device through a paired, 
authenticated session, and watch it disappear automatically after a set time.

- Three properties define the product:

- **Zero-friction capture** — uses the OS native Share Sheet, no app to open on the sending end
- **Zero-friction retrieval** — device pairs once (QR), then just works, forever, across sessions
- **Zero-clutter storage** — everything expires; there is no library to manage

- Sequence — sharing an item

  1. User is in any app (browser, WhatsApp, college group PDF viewer) → taps native Share → selects your app
  2. Your app receives the shared content via Android/iOS share-extension intent
  3. App uploads content (or the link/text) to backend with `expires_in` (default e.g. 30 min, user-configurable)
  4. Backend stores it, starts a TTL countdown
  5. Web app (already open or opened later) receives it via WebSocket push, or fetches it on load — shows as a card in a feed
  6. User clicks the card → item opens inline (PDF/image renders in browser; link opens in new tab; text/number just displays)
  7. TTL expires → scheduled job deletes the row and the blob → item vanishes from the feed automatically (real-time push if the tab is open)

## Data Model

- #### User
  - ##### id, phone_or_email, created_at

- #### Device
  - ##### id, user_id, device_type (mobile/web), session_token_hash, paired_at, last_seen_at, name (e.g. "Ahmed's MacBook")

- #### ShareItem
  - ##### id, user_id, type (link | pdf | image | text | file), storage_ref (S3/disk path, null for plain text/links), content (inline text/URL, null for files), mime_type, size_bytes, created_at, expires_at, opened_at (optional — useful for "open once" mode later)
