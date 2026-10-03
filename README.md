# Teilen
A personal share-inbox: send anything to yourself from your phone (links, PDFs, photos, text), open it on any device through a paired, 
authenticated session, and watch it disappear automatically after a set time.

- Three properties define the product:

- **Zero-friction capture** — uses the OS native Share Sheet, no app to open on the sending end
- **Zero-friction retrieval** — a device pairs once (scan a QR), then just works, forever, across sessions
- **Zero-clutter storage** — everything expires; there is no library to manage

- Sequence — sharing an item

  1. User is in any app (browser, WhatsApp, college group PDF viewer) → taps native Share → selects your app
  2. Your app receives the shared content via the Android share intent (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)
  3. App uploads the content (or the link/text) to the backend with `ttlSeconds` (30 minutes by default), carrying this device's bearer token
  4. Backend stores it against that account and starts a TTL countdown
  5. Web app (already open or opened later) receives it via WebSocket push, or fetches it on load — shows as a card in a feed
  6. User clicks the card → item opens inline (PDF/image renders in browser; link opens in new tab; text/number just displays)
  7. TTL expires → scheduled job deletes the row and the blob → item vanishes from the feed automatically (real-time push if the tab is open)

## Data Model

- #### User
  - ##### id, phone (unique), display_name, created_at

- #### Device
  - ##### id, user_id, device_type (PHONE | WEB), name (e.g. "Ahmed's MacBook"), token_hash, created_at, last_seen_at

  Only the SHA-256 of the token is stored, so a database dump is not a set of usable tokens.

- #### PairingRequest
  - ##### id, device_code_hash, user_code_hash, device_name, device_type, platform, status (PENDING | APPROVED | DENIED), device_id, token_value, created_at, expires_at, resolved_at

  The two hashes are the two secrets of one pairing: `user_code_hash` is the short code a human reads
  off a screen, `device_code_hash` is the long secret the waiting device polls with. A resolved
  request keeps its plaintext token in `token_value` until it is collected, exactly once, then only
  the device's `token_hash` remains.

- #### ShareItem
  - ##### id, user_id, type (link | pdf | image | text | file), storage_ref (S3/disk path, null for plain text/links), content (inline text/URL, null for files), mime_type, size_bytes, created_at, expires_at, opened_at (optional — useful for "open once" mode later)

## MVP status — text and files up to 30 MB, paired devices

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
  - **The app itself** — an **account card** (claim your number, or pair this phone from a device you
    already have, or sign out), then a screen to paste into, **pick a file** (30 MB cap, checked on the
    bytes as they stream), pick a TTL and watch the server URL. The three cards below the account need a
    token, so they are hidden outright until this phone is paired — and any of the four ways in above
    hands its content over to the account card rather than failing, so nothing is lost to a 401.
  All four upload in the background over plain `HttpURLConnection`. The app follows the system light/dark
  setting; the palette is paper, ink and one deep teal, declared once in `values/colors.xml` and
  swapped by `values-night/colors.xml`.
- `backend/` — Spring Boot: three auth tables (`users`, `devices`, `pairing_requests`) and one content
  table (`share_items`), `POST/GET/DELETE /api/items`, a WebSocket at `/ws` pushing `created`/`deleted`
  events to that account's sockets only, and a scheduled sweep that deletes expired rows **and their
  blobs** and tells the open feeds. Blobs live on local disk under `backend/data/blobs` (git-ignored),
  one file per item, named after the item id. It also serves the web app.
- The web app — plain HTML/CSS/JS in `backend/src/main/resources/static`, no build step. Behind a
  **pair this browser** screen that shows the QR, then the feed, an account panel listing every paired
  device you can revoke, and the upload affordance: drag files onto **Attach files…**, pick how long they
  should live, press send. Items load once and then follow the WebSocket, so a share shows up without a
  refresh and disappears when it expires; files appear as cards with the name, the size and a way
  through — images preview inline, PDFs open in a new tab. Anything over 30 MB is refused before it
  leaves the browser.

### Pairing — how a device gets in

An account is a phone number. The first device claims it and is paired on the spot; every device after
that has to be approved by one that is already paired. There is one set of endpoints for both flows,
shaped like RFC 8628 so it reads like something you already know:

```text
POST /api/auth/account         the first phone creates the account, is paired at once   (open)
POST /api/auth/device/code     a newcomer asks for a code                              (open)
GET  /api/auth/device/qr.svg   that code as a QR, encoding teilen://pair?code=…        (open)
GET  /api/auth/device/pending  what that code is asking for, for the prompt           (open)
POST /api/auth/device/approve  a paired device approves it
POST /api/auth/device/deny     …or does not
POST /api/auth/device/token    the newcomer polls; 202 while waiting, 200 with a token (open)
GET  /api/me                   who am I
GET  /api/devices              everything paired to this account
DELETE /api/devices/{id}       unpair one
```

A browser asks for a code, draws it as a QR, and polls `/token` every few seconds. You point your
phone's **camera** at the QR; the QR holds `teilen://pair?code=K7PM-3XQD`, the phone opens Teilen and
shows a dialog naming the device that is asking and what allowing it means. Allow, and the browser's
next poll returns its token. Deny, and the poll answers `403 pairing_denied` and stops. The second
phone pairs the same way round: it shows a code, and you type it into the account menu of a device
that is already paired.

That is deliberately one mechanism for both directions: no QR library, no camera permission, nothing
to scan from the inside of the app. The camera app does the scanning, and the code is also readable
aloud for anyone who would rather type it.

Codes are eight characters from an alphabet with no `I`, `O`, `0` or `1`, live for five minutes, and
are stored only as hashes. They are single-use: `approve` mints the newcomer's token and hands it to
the waiting device exactly once, after which the request is spent and the code answers `410`.

**Devices can be revoked** from the account menu in the web app, which is also what signing out does.
A device may revoke itself, because that is what signing out means — with one exception: the *last*
device cannot unpair itself, since the account would then have nobody left who could approve anything
and there would be no way back in. It answers `400 cannot_revoke_self` and says so.

**The one real gap: account creation proves nothing about the phone number.** Whoever asks for a number
first owns it. Adding an OTP means checking one code inside `AuthService.createAccount` and nothing
else would move. Everything after that first claim is approval-based, and there is no other
unauthenticated path into the feed.

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

### Or run it as an image

```bash
docker build -t teilen-backend backend/

docker run --rm -p 8081:8081 \
  -e DB_HOST=host.docker.internal -e DB_NAME=teilen \
  -e DB_USER=postgres -e DB_PASSWORD=postgres \
  -v teilen-blobs:/data/blobs \
  teilen-backend
```

`backend/Dockerfile` builds the jar with Maven and ships only the runtime, split into layers so a
code change rebuilds the small top one. It runs as a non-root user, and its `HEALTHCHECK` asks for
`/` rather than `/api/items` — the API needs a token now, so a healthy container would have reported
itself unhealthy. Everything is configured by environment variable — `PORT`, `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, `DB_PASSWORD`, `TEILEN_STORAGE_DIR`, `TEILEN_MAX_BLOB_BYTES`,
`TEILEN_DEFAULT_TTL_SECONDS`, `TEILEN_BLOB_LINK_SECRET` — so nothing is baked into the image. Shared
files live in the `/data/blobs` volume; without that mount they disappear with the container.

**This image has never actually been built.** It is written and looks right; nobody has run
`docker build` on it.

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

Everything below `/api` needs a paired device's token, as `Authorization: Bearer <token>`. Nothing is
session-based: there are no cookies, no CSRF token and no login form, because every call carries its
own proof and nothing else would do. A missing or unknown token is `401` with a `message` of
`not_authenticated`.

| method | path                   | body                                                        |
|--------|------------------------|-------------------------------------------------------------|
| POST   | `/api/items` (JSON)    | `{"content": "…", "type": "TEXT\|LINK", "ttlSeconds": 1800}` — all but `content` optional |
| POST   | `/api/items` (multipart) | `file=<binary>`, optional `ttlSeconds` — one item per file   |
| GET    | `/api/items`           | live (non-expired) items, newest first, for this account only |
| GET    | `/api/items/{id}/blob` | the bytes of a shared file, inline, with its original name    |
| DELETE | `/api/items/{id}`      | delete now (row and blob)                                    |
| WS     | `/ws?token=…`          | `{"event":"created","item":{…}}` / `{"event":"deleted","id":"…"}` |

Every item carries an `owner` — the phone number it belongs to — and a file item also carries a
`blobUrl`. Rows are filtered by the caller's account on the way out, so there is no id-guessing to
worry about: another account's id answers `404`, the same as one that never existed.

`ttlSeconds` is clamped to 10…86400, `type` is auto-detected (links from the content, uploads from their
MIME type with the file name as the tie-breaker), and the share sheet sends 30 minutes.

Uploads are capped at **30 MB** (`413` above it). The cap is enforced twice: Spring refuses a body over
the limit, and `BlobStore` counts the bytes as it writes them, so a lying `Content-Length` cannot get
past it — and a rejected upload leaves no partial file behind. On the phone the same check runs before
and during the upload, so an oversized file is never put on the wire.

#### Why blobs are the one unauthenticated thing

A browser cannot put an `Authorization` header on `<img src>` or a PDF the user opens in a tab, so a
`blobUrl` is signed instead: `?t=<expiryMillis>-<HMAC-SHA-256>` over the item id and its expiry, keyed
by `teilen.auth.blob-link-secret`. The link works until the item expires, which is exactly as long as
the item is meant to live, and it cannot be edited to reach a different item or a later expiry.

It is still a bearer credential, so one rule applies: **a paired stranger is refused, valid link or
not.** Someone who has already identified themselves to us as a different account does not get to
borrow a forwarded URL; they get the same `404` as an id that never existed. That is what stops a link
pasted into the wrong account from quietly becoming readable.

```bash
curl -X POST localhost:8081/api/items -H "Authorization: Bearer $TOKEN" \
     -F 'file=@invoice.pdf' -F ttlSeconds=3600
curl -OJ "$BLOB_URL"          # signed, so no header needed
```

