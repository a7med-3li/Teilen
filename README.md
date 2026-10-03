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

## MVP status — text and files up to 30 MB, no auth

The first vertical slice is implemented and working end to end:

- `android/` — a Kotlin app with four ways in, none of which needs the app to be open first:
  - **Send what I copy** (works everywhere, including WhatsApp and Telegram) — turn on the switch in the app
    and a quiet ongoing notification watches the clipboard. Copy anything and that notification changes to
    `Copied — send to Teilen?` with a **Send** button. One tap sends it. **Nothing is ever sent until you tap** —
    it never ships your clipboard anywhere on its own.
  - **Copy to Teilen** — in any editable field (chat input, notes, browser address bar) the selection toolbar
    offers `Copy to Teilen` (`ACTION_PROCESS_TEXT`); the text is sent and handed straight back, so the app you
    were in is unchanged.
  - **Share sheet** — Teilen is a share target for **any** content type (`ACTION_SEND` and
    `ACTION_SEND_MULTIPLE` with `text/*`, `image/*`, `application/pdf`, `*/*`), so it shows up in the
    system "Send to…" menu from WhatsApp, Telegram, Gmail, LinkedIn, the file manager and anything else.
    Text and links are sent as text; **files are uploaded**, one feed item per file, so a multi-select
    share of six photos becomes six cards that each expire on their own. Each share is
    upload-and-close: a small card with a progress bar, then a toast — no full app launch.
  - **The app itself** — a screen to paste into, **pick a file** (30 MB cap, checked on the bytes as
    they stream), pick a TTL and watch the server URL.
  All four upload in the background over plain `HttpURLConnection`. The app follows the system light/dark
  setting; the palette is paper, ink and one deep teal, declared once in `values/colors.xml` and
  swapped by `values-night/colors.xml`.
- `backend/` — Spring Boot: one model (`share_items`), `POST/GET/DELETE /api/items`, a WebSocket at `/ws`
  pushing `created`/`deleted` events, and a scheduled sweep that deletes expired rows **and their blobs**
  and tells the open feeds. Blobs live on local disk under `backend/data/blobs` (git-ignored), one file per
  item, named after the item id. It also serves the web app.
- The web app — plain HTML/CSS/JS in `backend/src/main/resources/static`, no build step. It loads the current
  items once and then follows the WebSocket, so a share shows up without a refresh and disappears when it expires.
  Files appear as cards with the name, the size and a way through: images preview inline, PDFs and other
  files open in a new tab. It can also upload files itself — drag them onto **Attach files…**, pick how long
  they should live, and press send; anything over 30 MB is refused before it leaves the browser.

**No authentication at all yet — do not expose this to a network you do not control.** Pairing and device
tokens are the next step. `PLAN.md` (git-ignored) has the phased breakdown.

### Run it

```bash
docker compose up -d                       # postgres on :5432, db "teilen"
cd backend && ./mvnw spring-boot:run       # API + web app on http://localhost:8081 (PORT=… to change)

curl -X POST localhost:8081/api/items \
  -H 'Content-Type: application/json' \
  -d '{"content":"hello from curl","ttlSeconds":60}'
# open http://localhost:8081 — the item appears without reloading, then vanishes on its own

cd android && ./gradlew installDebug      # server defaults to http://10.0.2.2:8081 (emulator);
                                           # set a LAN IP in the app for a real phone
```

Check the text action is registered on a connected device:

```bash
adb shell pm query-activities -a android.intent.action.PROCESS_TEXT -t text/plain | grep -i teilen
```

### Why the clipboard notification is shaped that way

On Android 10+ an app may only read the clipboard while it is in the foreground (or while it is the
keyboard). A background service cannot grab what you just copied, so the send cannot happen in the
background — and it should not, because a clipboard watcher that ships silently would upload half
your day. So the copy is *offered* by a notification, and tapping **Send** starts a transparent
activity. That tap is the foreground moment that makes the read legal, and it is also your explicit
"yes, this one".

The `minSdk` is 26 rather than 24 because the flow is built on notification channels.

Database connection is overridable with `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.

### API

| method | path                   | body                                                        |
|--------|------------------------|-------------------------------------------------------------|
| POST   | `/api/items` (JSON)    | `{"content": "…", "type": "TEXT\|LINK", "ttlSeconds": 1800}` — all but `content` optional |
| POST   | `/api/items` (multipart) | `file=<binary>`, optional `ttlSeconds` — one item per file   |
| GET    | `/api/items`           | live (non-expired) items, newest first                       |
| GET    | `/api/items/{id}/blob` | the bytes of a shared file, inline, with its original name    |
| DELETE | `/api/items/{id}`      | delete now (row and blob)                                    |
| WS     | `/ws`                  | `{"event":"created","item":{…}}` / `{"event":"deleted","id":"…"}` |

`ttlSeconds` is clamped to 10…86400, `type` is auto-detected (links from the content, uploads from their
MIME type with the file name as the tie-breaker), and the share sheet sends 30 minutes.

Uploads are capped at **30 MB** (`413` above it). The cap is enforced twice: Spring refuses a body over
the limit, and `BlobStore` counts the bytes as it writes them, so a lying `Content-Length` cannot get
past it — and a rejected upload leaves no partial file behind. On the phone the same check runs before
and during the upload, so an oversized file is never put on the wire.

```bash
curl -X POST localhost:8081/api/items -F 'file=@invoice.pdf' -F ttlSeconds=3600
curl -O -J localhost:8081/api/items/<id>/blob
```

