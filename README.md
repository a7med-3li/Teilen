# Teilen

A personal, zero-knowledge share-inbox: send anything to yourself from your phone (links, PDFs, photos, text, and files), open it on any device through a paired, authenticated session, and watch it disappear automatically after a set time.

> [!NOTE]
> For the exhaustive technical specification, cryptographic wire formats, WebRTC state machines, and sequence diagrams, refer to the complete [Technical Architecture & System Specification](teilen_system_architecture_implementation_plan.md).

---

## Core Product Invariants

- **Zero-Friction Capture** — integrates directly with the OS native Share Sheet, clipboard watcher notification, and text selection toolbar; no need to open the app first.
- **Zero-Knowledge Privacy** — content and metadata (filenames, MIME types, byte sizes) are encrypted client-side using `libsodium` primitives (`crypto_secretstream_xchacha20poly1305`) before leaving the device. The server only sees and relays opaque ciphertext.
- **Dual-Path Routing with 2500ms Fallback** — transfers attempt a direct peer-to-peer WebRTC LAN connection first. If connection negotiation exceeds 2,500 ms, the system seamlessly falls back to the ephemeral cloud relay.
- **Zero-Clutter Storage** — everything expires automatically (default 30–60 minutes, configurable from 10s to 24h). A scheduled background reaper deletes expired database records and disk storage blobs, broadcasting deletion events to open client feeds in real time.

---

## Sharing Sequence

1. **Capture:** User taps native Share in any Android app (browser, WhatsApp, PDF viewer) and selects **Teilen**.
2. **Transparent Dispatch:** Android `ShareActivity` opens translucently, hands off file descriptors to `TransferService` (Foreground Service), triggers confirmation haptics, and dismisses itself in under 50 ms.
3. **Encryption:** Payload and metadata are sealed into a uniform SecretStream ciphertext envelope under the Account Master Key ($K_{master}$).
4. **Dual-Path Transmission:**
   - **Fast Path:** Single-file shares query `/ws/signal` and attempt WebRTC LAN DataChannel transfer within a 2,500 ms window.
   - **Cloud Relay Fallback:** If WebRTC times out, or for batch shares, the envelope streams to the backend via chunked multipart HTTP.
5. **Real-Time Feed Notification:**
   - Active web tabs receive a push event via WebSocket (`/ws`) and render the item card with an SVG countdown ring.
   - Closed or backgrounded browsers receive a zero-content wake-up signal via VAPID Web Push.
6. **Decryption & Retrieval:**
   - Small files (< 10 MB) decrypt automatically in the background via an off-main-thread Web Worker (`decrypt.worker.js`).
   - Clicking a card opens images/PDFs inline, copies text, or triggers direct download via memory-backed `blob:` URLs.
7. **Expiration:** When TTL expires, the scheduled reaper purges the database row and disk blob; the item vanishes from active browser feeds instantly.

---

## Data Model

The schema consists of five PostgreSQL tables managed by Flyway migrations (V1 through V6):

- **`users`**
  - `id` (UUID PK), `phone` (VARCHAR unique), `password_hash` (VARCHAR, BCrypt salted hash), `display_name` (VARCHAR), `created_at` (TIMESTAMPTZ).
- **`devices`**
  - `id` (UUID PK), `user_id` (UUID FK), `device_type` (`PHONE` | `WEB`), `name` (VARCHAR), `token_hash` (VARCHAR unique, SHA-256 of bearer token), `created_at` (TIMESTAMPTZ), `last_seen_at` (TIMESTAMPTZ).
- **`pairing_requests`**
  - `id` (UUID PK), `device_code_hash` (VARCHAR unique), `user_code_hash` (VARCHAR unique), `device_name` (VARCHAR), `device_type` (VARCHAR), `platform` (VARCHAR), `status` (`PENDING` | `APPROVED` | `DENIED`), `device_id` (UUID FK), `token_value` (VARCHAR, temporary clear token held until collection), `newcomer_public_key` (VARCHAR, single-use X25519 key), `key_package` (VARCHAR, sealed master key bundle), `created_at` (TIMESTAMPTZ), `expires_at` (TIMESTAMPTZ), `resolved_at` (TIMESTAMPTZ).
- **`push_subscriptions`**
  - `id` (UUID PK), `user_id` (UUID FK), `endpoint` (VARCHAR), `p256dh` (VARCHAR), `auth` (VARCHAR), `created_at` (TIMESTAMPTZ).
- **`share_items`**
  - `id` (UUID PK), `user_id` (UUID FK), `type` (`TEXT` | `LINK` | `IMAGE` | `PDF` | `FILE`), `content` (TEXT, inline content or original filename), `storage_ref` (VARCHAR, disk filename under `/data/blobs`), `mime_type` (VARCHAR), `size_bytes` (BIGINT), `created_at` (TIMESTAMPTZ), `expires_at` (TIMESTAMPTZ), `opened_at` (TIMESTAMPTZ, optional).

---

## Subsystem Architecture

### 1. Android Native Client (`android/`)
- **Technology Stack:** Kotlin 1.9+, Android SDK 36 (minSdk 26), Jetpack Compose / Material 3, LazySodium JNI (`libsodium`), Google WebRTC SDK (`io.github.webrtc-sdk:android:125.6422.07`), OkHttp 4.
- **UI Architecture:** Tabbed layout in `MainActivity` with 4 pages: **Send**, **Files**, **Feed**, and **Account**.
- **Capture Methods:**
  - **Share Sheet Target:** Pinned to top row via `shortcuts.xml` (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
  - **Process Text:** System text toolbar action (`ACTION_PROCESS_TEXT`) via `ProcessTextActivity`.
  - **Send What I Copy:** Quiet clipboard monitor (`ClipboardWatchService`) with silent foreground notification and one-tap send action.
  - **In-App Send:** Quick compose box, file picker (30 MB limit), TTL spinner (1m to 4h).
- **Security & Background Services:**
  - `SecureKeyManager`: Master Account Key ($K_{master}$) and Ed25519 identity seed wrapped in Android Keystore using hardware-backed AES-256-GCM.
  - `TransferService`: Foreground Service (`dataSync` type) ensuring uploads outlive UI destruction.
  - `LanTransferEngine`: WebRTC DataChannel engine with backpressure control (`bufferedAmount <= 2MB`) and 2500ms fallback timeout.

### 2. Spring Boot Backend (`backend/`)
- **Technology Stack:** Spring Boot 4.1.1, Java 21 LTS, PostgreSQL 16, Flyway, Spring Security, Spring WebSocket, ZXing Core.
- **Stateless Authentication:** Every protected endpoint requires `Authorization: Bearer <token>`. `BearerTokenFilter` validates against SHA-256 hashes in `devices.token_hash`.
- **Dual WebSocket Engines:**
  - `/ws`: Real-time account feed push (`created`, `extended`, `deleted` events).
  - `/ws/signal`: Zero-trust WebRTC signaling relay for opaque SDP offers/answers and ICE candidates stamped with verified `from: deviceId`.
- **Scheduled Storage Purge:** `@Scheduled(fixedDelay = 5000)` sweeps expired items, deletes disk blobs from `/data/blobs`, and broadcasts deletion frames to active WebSocket sessions.
- **Web Push Engine:** RFC 8291 / RFC 8292 VAPID push sender transmitting zero-content arrival pings (`ITEM_AVAILABLE`).

### 3. Modern Web Client (`backend/src/main/resources/static/`)
- **Technology Stack:** Lightweight, zero-build vanilla ES6+ Single Page Application served directly by Spring Boot.
- **Decryption Worker (`decrypt.worker.js`):** Off-main-thread Web Worker decrypts SecretStream ciphertext envelopes to keep the UI at 60 fps.
- **Key Store (`cryptoStore.js`):** Non-extractable Master Account Key storage in IndexedDB (`teilen_sec_db`), Diffie-Hellman X25519 key exchange, and Ed25519 signature verification.
- **WebRTC Fast-Path (`lan.js`):** Connects to `/ws/signal`, verifies sender Ed25519 signatures, and receives streams directly over local DataChannels.
- **Web Push Service Worker (`sw.js`):** Listens for incoming VAPID push pings and displays local notifications.
- **Interactive Deck:** Feed cards with animated SVG countdown rings, dynamic TTL extension (`+1 Hour`), full-window drag-and-drop overlay (`#drop-veil`), and global `Ctrl+V` paste interceptor.

---

## Authentication & Device Pairing

Teilen provides two seamless ways to get into an account:

### 1. Phone Number + Password (V6)
- **Register:** `POST /api/auth/account` with phone number, password, display name, and device name.
- **Log In:** `POST /api/auth/login` with phone and password. Returns an authenticated bearer token immediately.

### 2. Symmetric RFC 8628 Device Pairing (QR / Short Code)
- A newcomer device calls `POST /api/auth/device/code` (submitting an ephemeral X25519 public key) and displays an 8-character human-readable code or QR code (`teilen://pair?code=...`).
- An already-paired device approves the code:
  - Scans QR or enters the 8-character code.
  - Derives a shared secret via Diffie-Hellman scalar multiplication ($SS = X25519(sk_{app}, pk_{new})$).
  - Derives wrapping key $K_{wrap} = HKDF\text{-}SHA256(SS)$.
  - Seals the Master Account Key: $C_{master} = crypto\_secretbox(K_{master}, nonce, K_{wrap})$.
  - Signs the bundle with its Ed25519 identity key and calls `POST /api/auth/device/approve`.
- The newcomer device polls `POST /api/auth/device/token`, claims its bearer token and the encrypted `keyPackage`, verifies the signature, and unwraps $K_{master}$ into IndexedDB.

---

## Complete API Surface

### Authentication & Devices
| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/account` | Open | Create account with phone and password |
| `POST` | `/api/auth/login` | Open | Log in with phone and password |
| `GET` | `/api/me` | Bearer | Current account and device information |
| `POST` | `/api/auth/device/code` | Open | Request pairing code (submits newcomer public key) |
| `GET` | `/api/auth/device/qr.svg` | Open | SVG QR code for `teilen://pair?code=...` |
| `GET` | `/api/auth/device/pending`| Open | Inspect pending pairing request details |
| `POST` | `/api/auth/device/approve`| Bearer | Approve pairing and attach sealed key package |
| `POST` | `/api/auth/device/deny` | Bearer | Reject pairing request |
| `POST` | `/api/auth/device/token` | Open | Poll for token and sealed key package |
| `GET` | `/api/devices` | Bearer | List all paired devices for account |
| `DELETE`| `/api/devices/{id}` | Bearer | Unpair device (last device cannot unpair itself) |

### Content & Feeds
| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/items` (JSON) | Bearer | Share text or URL snippet |
| `POST` | `/api/items` (Multipart) | Bearer | Upload encrypted file or blob (30 MB limit) |
| `GET` | `/api/items` | Bearer | List active, non-expired items for account |
| `GET` | `/api/items/{id}/blob` | Signed | Download blob via HMAC-SHA256 link |
| `DELETE`| `/api/items/{id}` | Bearer | Delete item and disk blob immediately |
| `POST` | `/api/v1/items/init` | Bearer | Register ephemeral cloud relay upload |
| `GET` | `/api/v1/items/pending`| Bearer | List pending ciphertexts |
| `POST` | `/api/v1/items/{id}/extend`| Bearer | Extend item TTL by 1 hour |

### Web Push & WebSockets
| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/push/public-key` | Bearer | Get VAPID public key |
| `POST` | `/api/push/subscribe` | Bearer | Register Web Push subscription |
| `DELETE`| `/api/push/subscribe` | Bearer | Unsubscribe Web Push endpoint |
| `WS` | `/ws?token=...` | Bearer | Live feed WebSocket (`created`, `extended`, `deleted`) |
| `WS` | `/ws/signal?token=...` | Bearer | WebRTC signaling relay (`OFFER`, `ANSWER`, `CANDIDATE`) |

---

## Running the System

### 1. Local Development
```bash
# Start PostgreSQL
docker compose up -d db

# Run backend (Spring Boot 4 / Java 21)
cd backend && ./mvnw spring-boot:run
# Server runs at http://localhost:8081

# Build and install Android client
cd ../android && ./gradlew installDebug
```

### 2. Containerized Production Setup
```bash
# Start full stack (PostgreSQL + Spring Boot Backend + Caddy SSL Proxy)
docker compose up -d
```

### Verification
```bash
# Query registered text processing action on Android
adb shell pm query-activities -a android.intent.action.PROCESS_TEXT -t text/plain | grep -i teilen
```
