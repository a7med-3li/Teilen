/* ==========================================================================
   Teilen Web Client: Vanilla ES6+ SPA
   Zero-knowledge, dual-path (Direct LAN WebRTC + Ephemeral Cloud Relay)
   Multi-Session Rooms, Multi-GB LAN Streaming, Token Hardening & DPoP
   ========================================================================== */
(() => {
    'use strict';

    // Immediate guaranteed dismissal for launch veil (never traps user on black screen)
    setTimeout(() => {
        const veil = document.getElementById('launch-veil');
        if (veil) {
            veil.classList.add('dismissed');
            veil.hidden = true;
            try { veil.remove(); } catch (e) {}
        }
    }, 1400);

    const API = '/api/items';
    const AUTH = '/api/auth';
    const SESSIONS_API = '/api/sessions';
    const SVG_NS = 'http://www.w3.org/2000/svg';
    const MAX_RELAY_BYTES = 30 * 1024 * 1024; // 30 MB ceiling for Cloud Relay

    // ---------- Bilingual Localization (English & Arabic) ----------
    const I18N = {
        en: {
            brand: 'Teilen',
            subtitle_encrypted: 'End-to-End Encrypted',
            status_encrypted_short: 'Encrypted',
            status_live: 'Live',
            status_connecting: 'Connecting…',
            status_reconnecting: 'Reconnecting…',
            status_offline: 'Offline',
            btn_pair: 'Pair Device',
            personal_vault: 'Personal Vault',
            new_room: 'New Room',
            join_room: 'Join Room',
            join_room_by_code: 'Join Room by Code',
            share_room: 'Share Room',
            delete_room: 'Delete Room',
            leave_room: 'Leave Room',
            confirm_delete_room: 'Delete this room and all its shared content permanently?',
            confirm_leave_room: 'Leave this room? You will need an invite code to rejoin.',
            welcome_title: 'Welcome to Teilen',
            welcome_desc: 'End-to-end encrypted cross-device sync & Direct LAN',
            instant_vault_btn: '⚡ Start Instant Anonymous Vault',
            auth_divider: 'or pair with another device',
            pair_lead: 'Enter this 8-character pairing code on your phone or second device:',
            pair_code_hint: 'Waiting for other device…',
            pair_retry: 'Get a new code',
            auth_advanced_toggle: 'Advanced: Claim Custom Username / Switch Vault',
            auth_tab_login: 'Sign in',
            auth_tab_register: 'Create account',
            label_username: 'Username',
            label_password: 'Password',
            label_display_name: 'Display name (optional)',
            username_helper: 'Letters and numbers only, no special characters',
            password_placeholder_min: 'At least 6 characters',
            display_name_placeholder: 'e.g. My Laptop',
            btn_sign_in: 'Sign in',
            btn_create_account: 'Create account',
            devices_heading: 'Paired devices',
            btn_revoke_others: 'Sign Out All Other Devices',
            approve_label: 'Pair another device — enter 8-digit code (e.g. K7PM-3XQD)',
            btn_sign_out: 'Sign out of this browser',
            composer_placeholder: 'Type or paste unlimited text, links, or code… (Shift+Enter for newline, Enter to send)',
            expires_in: 'Expires in:',
            ttl_custom: 'Custom',
            btn_attach_file: 'Attach File',
            btn_send: 'Send',
            btn_copy: 'Copy',
            btn_copied: 'Copied!',
            btn_save: 'Save',
            btn_saved: 'Saved!',
            btn_extend_1h: '+1h',
            btn_extend_1h_menu: '+1 Hour Expiration',
            btn_custom_timer_menu: 'Custom Timer (TTL)…',
            btn_toggle_code_menu: 'Toggle Code Font',
            btn_toggle_full_menu: 'Toggle Full View',
            share_link_menu: 'Share Link',
            btn_delete: 'Delete',
            btn_cancel: 'Cancel',
            btn_done: 'Done',
            btn_apply: 'Apply',
            badge_text: 'TEXT',
            badge_link: 'LINK',
            badge_image: 'IMAGE',
            badge_file: 'FILE',
            badge_pdf: 'PDF',
            badge_audio: 'AUDIO',
            telemetry_title: 'Direct LAN P2P Transfer',
            telemetry_badge: '0 Server Bytes Used',
            empty_feed_title: 'Nothing here yet',
            empty_feed_subtitle: 'Share files, logs, or text from your devices. Unlimited paste and multi-gigabyte transfers supported.',
            item_count_single: '1 active item',
            item_count_multi: '%d active items',
            btn_delete_all: 'Delete All',
            modal_create_room_title: 'Create New Session Room',
            modal_create_room_desc: 'Rooms are end-to-end encrypted with their own independent 256-bit symmetric key.',
            label_room_name: 'Room Name',
            placeholder_room_name: 'e.g. Study Group, Project Alpha',
            btn_create_room: 'Create Room',
            modal_share_room_title: 'Share Session',
            modal_share_room_desc: 'Anyone with this 8-character code or link will join this room and receive its encryption key.',
            modal_join_room_title: 'Join Session Room',
            modal_join_room_desc: 'Enter the 8-character code or paste a teilen:// invite link:',
            label_invite_code: 'Invite Code / Link',
            placeholder_invite_code: 'e.g. K7PM-3XQD or teilen://session/join?code=...',
            btn_join_room: 'Join Room',
            pair_modal_title: 'Pair Device',
            pair_modal_subtitle: 'Enter this 8-character code on your phone or second browser:',
            pair_enter_code_label: 'OR ENTER CODE FROM PHONE',
            btn_connect_device: 'Connect Device',
            btn_paste: 'Paste',
            connected_devices_title: 'Connected Devices',
            this_device_badge: 'This Device',
            btn_disconnect: 'Disconnect',
            btn_disconnect_all: 'Disconnect All Other Devices',
            modal_ttl_title: 'Set Expiration Timer (TTL)',
            modal_ttl_desc: 'Enter custom lifespan for this item (maximum 24 hours):',
            ttl_unit_seconds: 'Seconds',
            ttl_unit_minutes: 'Minutes',
            ttl_unit_hours: 'Hours',
            drop_title: 'Drop to encrypt & send',
            drop_subtitle: 'Multi-GB Direct LAN P2P · Cloud Relay up to 30 MB',
            launch_status: 'Initializing Vault…',
            toast_paired_success: 'Device successfully connected!',
            toast_sending_headless: 'Encrypting & sharing to Teilen…',
            toast_shared_text: 'Text shared successfully',
            toast_shared_link: 'Link shared successfully',
            toast_shared_file: 'File shared successfully',
            toast_shared_files_multi: '%d files shared successfully',
            toast_share_failed_offline: 'Cannot share: No network connection'
        },
        ar: {
            brand: 'Teilen',
            subtitle_encrypted: 'مشفّر طرفاً لطرف',
            status_encrypted_short: 'مشفّر',
            status_live: 'متصل',
            status_connecting: 'جارٍ الاتصال…',
            status_reconnecting: 'جارٍ إعادة الاتصال…',
            status_offline: 'غير متصل',
            btn_pair: 'ربط جهاز',
            personal_vault: 'الخزنة الشخصية',
            new_room: 'غرفة جديدة',
            join_room: 'الانضمام لغرفة',
            join_room_by_code: 'الانضمام لغرفة برمز',
            share_room: 'مشاركة الغرفة',
            delete_room: 'حذف الغرفة',
            leave_room: 'مغادرة الغرفة',
            confirm_delete_room: 'هل تريد حذف هذه الغرفة وجميع محتوياتها نهائياً؟',
            confirm_leave_room: 'هل تريد مغادرة هذه الغرفة؟ ستحتاج إلى رمز دعوة للعودة.',
            welcome_title: 'مرحباً بك في Teilen',
            welcome_desc: 'مزامنة مشفّرة طرفاً لطرف بين الأجهزة وشبكة محلية مباشرة (Direct LAN)',
            instant_vault_btn: '⚡ بدء خزنة فورية مجهولة',
            auth_divider: 'أو قم بالربط مع جهاز آخر',
            pair_lead: 'أدخل هذا الرمز المكوّن من 8 خانات على هاتفك أو جهازك الآخر:',
            pair_code_hint: 'بانتظار الجهاز الآخر…',
            pair_retry: 'الحصول على رمز جديد',
            auth_advanced_toggle: 'متقدم: تسجيل اسم مستخدم مخصص / تبديل الخزنة',
            auth_tab_login: 'تسجيل الدخول',
            auth_tab_register: 'إنشاء حساب',
            label_username: 'اسم المستخدم',
            label_password: 'كلمة المرور',
            label_display_name: 'الاسم المعروض (اختياري)',
            username_helper: 'حروف وأرقام إنجليزية فقط، بدون رموز خاصة',
            password_placeholder_min: '6 أحرف على الأقل',
            display_name_placeholder: 'مثلاً: حاسوبي المحمول',
            btn_sign_in: 'تسجيل الدخول',
            btn_create_account: 'إنشاء حساب',
            devices_heading: 'الأجهزة المرتبطة',
            btn_revoke_others: 'تسجيل الخروج من كافة الأجهزة الأخرى',
            approve_label: 'ربط جهاز آخر — أدخل الرمز المكوّن من 8 خانات (مثلاً K7PM-3XQD)',
            btn_sign_out: 'تسجيل الخروج من هذا المتصفح',
            composer_placeholder: 'اكتب أو الصق نصوصاً غير محدودة، روابط، أو كود… (Shift+Enter لسطر جديد، Enter للإرسال)',
            expires_in: 'الصلاحية:',
            ttl_custom: 'مخصص',
            btn_attach_file: 'إرفاق ملف',
            btn_send: 'إرسال',
            btn_copy: 'نسخ',
            btn_copied: 'تم النسخ!',
            btn_save: 'حفظ',
            btn_saved: 'تم الحفظ!',
            btn_extend_1h: '+ساعة',
            btn_extend_1h_menu: '+ساعة صلاحية إضافية',
            btn_custom_timer_menu: 'مؤقت مخصص (TTL)…',
            btn_toggle_code_menu: 'تبديل خط الكود',
            btn_toggle_full_menu: 'تبديل العرض الكامل',
            share_link_menu: 'مشاركة الرابط',
            btn_delete: 'حذف',
            btn_cancel: 'إلغاء',
            btn_done: 'تم',
            btn_apply: 'تطبيق',
            badge_text: 'نص',
            badge_link: 'رابط',
            badge_image: 'صورة',
            badge_file: 'ملف',
            badge_pdf: 'مستند PDF',
            badge_audio: 'صوت',
            telemetry_title: 'نقل مباشر عبر الشبكة المحلية (Direct LAN P2P)',
            telemetry_badge: 'استهلاك 0 بايت من السيرفر',
            empty_feed_title: 'لا توجد عناصر بعد',
            empty_feed_subtitle: 'شارك الملفات أو السجلات أو النصوص من أجهزتك فورياً. يدعم اللصق غير المحدود ونقل عدة غيغابايت.',
            item_count_single: 'عنصر نشط واحد',
            item_count_multi: '%d عناصر نشطة',
            btn_delete_all: 'حذف الكل',
            modal_create_room_title: 'إنشاء غرفة محادثة جديدة',
            modal_create_room_desc: 'الغرف مشفّرة طرفاً لطرف بمفتاح متناظر مستقل 256 بت.',
            label_room_name: 'اسم الغرفة',
            placeholder_room_name: 'مثلاً: فريق العمل، مشروع ألفا',
            btn_create_room: 'إنشاء الغرفة',
            modal_share_room_title: 'مشاركة الغرفة',
            modal_share_room_desc: 'أي شخص يملك هذا الرمز المكوّن من 8 خانات أو الرابط سينضم إلى هذه الغرفة ويتلقى مفتاح التشفير.',
            modal_join_room_title: 'الانضمام لغرفة',
            modal_join_room_desc: 'أدخل الرمز المكوّن من 8 خانات أو الصق رابط teilen:// للدعوة:',
            label_invite_code: 'رمز / رابط الدعوة',
            placeholder_invite_code: 'مثلاً K7PM-3XQD أو رابط teilen://session/join?code=...',
            btn_join_room: 'الانضمام للغرفة',
            pair_modal_title: 'ربط جهاز',
            pair_modal_subtitle: 'أدخل هذا الرمز المكوّن من 8 خانات على هاتفك أو متصفحك الآخر:',
            pair_enter_code_label: 'أو أدخل الرمز من الهاتف',
            btn_connect_device: 'توصيل الجهاز',
            btn_paste: 'لصق',
            connected_devices_title: 'الأجهزة المتصلة',
            this_device_badge: 'هذا الجهاز',
            btn_disconnect: 'قطع الاتصال',
            btn_disconnect_all: 'قطع اتصال كافة الأجهزة الأخرى',
            modal_ttl_title: 'تحديد مؤقت الصلاحية (TTL)',
            modal_ttl_desc: 'أدخل مدة صلاحية مخصصة لهذا العنصر (بحد أقصى 24 ساعة):',
            ttl_unit_seconds: 'ثوانٍ',
            ttl_unit_minutes: 'دقائق',
            ttl_unit_hours: 'ساعات',
            drop_title: 'أفلت للتشفير والإرسال',
            drop_subtitle: 'نقل محلي مباشر Direct LAN لعدة غيغابايت · ترحيل سحابي حتى 30 ميغابايت',
            launch_status: 'جارٍ تهيئة الخزنة…',
            toast_paired_success: 'تم توصيل الجهاز بنجاح!',
            toast_sending_headless: 'جارٍ التشفير والمشاركة إلى Teilen…',
            toast_shared_text: 'تمت مشاركة النص بنجاح',
            toast_shared_link: 'تمت مشاركة الرابط بنجاح',
            toast_shared_file: 'تمت مشاركة الملف بنجاح',
            toast_shared_files_multi: 'تمت مشاركة %d ملفات بنجاح',
            toast_share_failed_offline: 'تعذر المشاركة: لا يوجد اتصال بالشبكة'
        }
    };

    function detectLocale() {
        try {
            const saved = localStorage.getItem('teilen_lang');
            if (saved === 'ar' || saved === 'en') return saved;
            const browser = (navigator.language || navigator.userLanguage || 'en').toLowerCase();
            return browser.startsWith('ar') ? 'ar' : 'en';
        } catch (e) {
            return 'en';
        }
    }

    let currentLang = detectLocale();

    function t(key, ...args) {
        const dict = I18N[currentLang] || I18N['en'];
        let val = dict[key] !== undefined ? dict[key] : (I18N['en'][key] !== undefined ? I18N['en'][key] : key);
        if (typeof val === 'string' && args.length > 0) {
            args.forEach(arg => {
                val = val.replace('%d', arg).replace('%s', arg);
            });
        }
        return val;
    }

    function applyLocale(lang) {
        currentLang = (lang === 'ar') ? 'ar' : 'en';
        try {
            localStorage.setItem('teilen_lang', currentLang);
        } catch (e) {}

        document.documentElement.lang = currentLang;
        document.documentElement.dir = (currentLang === 'ar') ? 'rtl' : 'ltr';

        const langToggleBtn = el('btn-lang-toggle');
        if (langToggleBtn) {
            langToggleBtn.textContent = (currentLang === 'ar') ? 'EN' : 'عربي';
            langToggleBtn.setAttribute('title', (currentLang === 'ar') ? 'Switch to English' : 'التبديل إلى العربية');
        }

        document.querySelectorAll('[data-i18n]').forEach(elem => {
            const k = elem.getAttribute('data-i18n');
            if (k) elem.textContent = t(k);
        });

        document.querySelectorAll('[data-i18n-placeholder]').forEach(elem => {
            const k = elem.getAttribute('data-i18n-placeholder');
            if (k) elem.setAttribute('placeholder', t(k));
        });

        document.querySelectorAll('[data-i18n-title]').forEach(elem => {
            const k = elem.getAttribute('data-i18n-title');
            if (k) elem.setAttribute('title', t(k));
        });

        if (typeof renderSessionPills === 'function') renderSessionPills();
        if (typeof refresh === 'function') refresh();
        if (typeof updateStatusDisplay === 'function') updateStatusDisplay();

        document.querySelectorAll('#feed .card').forEach(cardEl => {
            const id = cardEl.dataset.id;
            const entry = cards.get(id);
            if (entry && entry.item) {
                const badge = cardEl.querySelector('.badge');
                if (badge) badge.textContent = badgeLabel(entry.item);
            }
        });
    }

    // ---------- Clean-Slate Identity & Persistent Storage (Part 1) ----------
    function getClientFingerprint() {
        let fp = null;
        try {
            fp = localStorage.getItem('teilen_client_fingerprint');
            if (!fp) {
                fp = (typeof crypto !== 'undefined' && crypto.randomUUID)
                    ? crypto.randomUUID()
                    : 'fp_' + Math.random().toString(36).substring(2) + Date.now().toString(36);
                localStorage.setItem('teilen_client_fingerprint', fp);
            }
        } catch (e) {
            fp = 'fp_fallback_' + Math.random().toString(36).substring(2);
        }
        return fp;
    }

    const token = {
        get() {
            try {
                return localStorage.getItem('teilen_token') || null;
            } catch (e) {
                return null;
            }
        },
        set(value) {
            try {
                if (value) {
                    localStorage.setItem('teilen_token', value);
                } else {
                    localStorage.removeItem('teilen_token');
                }
            } catch (e) {}
        }
    };

    function getStoredUserId() {
        try { return localStorage.getItem('teilen_user_id') || null; } catch (e) { return null; }
    }
    function setStoredUserId(id) {
        try {
            if (id) localStorage.setItem('teilen_user_id', id);
            else localStorage.removeItem('teilen_user_id');
        } catch (e) {}
    }

    function getStoredDeviceId() {
        try { return localStorage.getItem('teilen_device_id') || null; } catch (e) { return null; }
    }
    function setStoredDeviceId(id) {
        try {
            if (id) localStorage.setItem('teilen_device_id', id);
            else localStorage.removeItem('teilen_device_id');
        } catch (e) {}
    }

    // ---------- Active Session State (Task 4) ----------
    let currentSessionId = null;
    let availableSessions = [];
    let activeCustomTtlItemId = null; // When editing card TTL

    // ---------- Elements ----------
    const el = (id) => document.getElementById(id);
    const feed = el('feed');
    const empty = el('empty');
    const statusEl = el('status');
    const countEl = el('count');
    const clearBtn = el('clear');
    const compose = el('compose');
    const composeInput = el('compose-input');
    const composeSubmit = el('compose-submit');

    const sessionSelectorPill = el('session-selector-pill');
    const activeSessionName = el('active-session-name');
    const sessionDropdown = el('session-dropdown');
    const sessionDropdownList = el('session-dropdown-list');
    const sessionDropdownJoinBtn = el('session-dropdown-join-btn');
    const sessionPills = el('session-pills');
    const sessionShareBtn = el('session-share-btn');
    const sessionDeleteBtn = el('session-delete-btn');
    const sessionCreateBtn = el('session-create-btn');
    const sessionJoinBtn = el('session-join-btn');

    const modalCreateSession = el('modal-create-session');
    const formCreateSession = el('form-create-session');
    const createSessionName = el('create-session-name');

    const modalShareSession = el('modal-share-session');
    const shareSessionTitle = el('share-session-title');
    const shareSessionCode = el('share-session-code');
    const shareSessionCopy = el('share-session-copy');
    const shareSessionDeleteBtn = el('share-session-delete-btn');

    const modalJoinSession = el('modal-join-session');
    const formJoinSession = el('form-join-session');
    const joinSessionInput = el('join-session-input');
    const joinSessionPreview = el('join-session-preview');

    const modalCustomTtl = el('modal-custom-ttl');
    const formCustomTtl = el('form-custom-ttl');
    const customTtlNumber = el('custom-ttl-number');
    const customTtlUnit = el('custom-ttl-unit');
    const customTtlTitle = el('custom-ttl-title');
    const ttlCustomPill = el('ttl-custom-pill');

    const telemetryCard = el('telemetry-card');
    const telemetryName = el('telemetry-name');
    const telemetryBar = el('telemetry-bar');
    const telemetryStats = el('telemetry-stats');
    const telemetrySpeed = el('telemetry-speed');
    const telemetryCancel = el('telemetry-cancel');

    const pairSection = el('pair');
    const pairCode = el('pair-code');
    const pairState = el('pair-state');
    const pairRetry = el('pair-retry');

    const btnInstantVault = el('btn-instant-vault');
    const btnToggleAuthAdvanced = el('btn-toggle-auth-advanced');
    const authAdvancedChevron = el('auth-advanced-chevron');
    const authAdvancedContainer = el('auth-advanced-container');
    const authTabLogin = el('auth-tab-login');
    const authTabRegister = el('auth-tab-register');
    const authTabPair = el('auth-tab-pair');
    const loginContainer = el('login-container');
    const registerContainer = el('register-container');
    const pairContainer = el('pair-container');
    const loginForm = el('login-form');
    const loginUsername = el('login-username');
    const loginUsernameHelper = el('login-username-helper');
    const loginPassword = el('login-password');
    const loginStatus = el('login-status');
    const registerForm = el('register-form');
    const registerUsername = el('register-username');
    const registerUsernameHelper = el('register-username-helper');
    const registerPassword = el('register-password');
    const registerDisplayName = el('register-display-name');
    const registerStatus = el('register-status');
    const pairCodeCopy = el('pair-code-copy');

    const deviceSection = el('devices');
    const accountBtn = el('account');
    const accountPhone = el('account-phone');
    const deviceList = el('device-list');
    const approveForm = el('approve-form');
    const approveCode = el('approve-code');
    const deviceStatus = el('device-status');
    const signOutBtn = el('sign-out');
    const revokeOthersBtn = el('revoke-others-btn');

    const app = el('app');
    const foot = el('foot');
    const dropVeil = el('drop-veil');

    // Header Pair & Modal Pair Elements
    const btnLangToggle = el('btn-lang-toggle');
    const btnHeaderPair = el('btn-header-pair');
    const modalPairPhone = el('modal-pair-phone');
    const modalPairCode = el('modal-pair-code');
    const modalPairCodeCopy = el('modal-pair-code-copy');
    const modalPairStatus = el('modal-pair-status');
    const modalApproveForm = el('modal-approve-form');
    const modalApproveCode = el('modal-approve-code');
    const modalApproveStatus = el('modal-approve-status');
    const modalDevicesTitle = el('modal-devices-title');
    const modalDevicesList = el('modal-devices-list');
    const modalDisconnectOthersBtn = el('modal-disconnect-others-btn');

    // Hero Pairing Card Elements
    const heroPairingCard = el('hero-pairing-card');
    const heroPairCode = el('hero-pair-code');
    const heroCodeCopy = el('hero-code-copy');
    const heroPairStatus = el('hero-pair-status');
    const heroApproveForm = el('hero-approve-form');
    const heroApproveCode = el('hero-approve-code');
    const heroApproveStatus = el('hero-approve-status');
    const heroPairingDismiss = el('hero-pairing-dismiss');
    let heroPairingDismissed = false;

    const uploadInput = el('upload-input');
    const uploadStatus = el('upload-status');

    let selectedTtlSeconds = 3600; // Default 1 hour
    let activeLanTransfer = null;

    /** id -> {item, element, ttl, ring} */
    const cards = new Map();
    let socket = null;
    let retryDelay = 1000;
    let pollTimer = null;
    let pairTimer = null;
    let socketConnecting = false;

    let pairKey = null;
    let pairUserCode = null;

    // ---------- Toast notifications ----------
    function toast(message, type = 'info', duration = 3500) {
        const container = el('toast-container');
        if (!container) return;
        const t = document.createElement('div');
        t.className = 'toast ' + type;
        t.textContent = message;
        container.appendChild(t);
        requestAnimationFrame(() => t.classList.add('show'));
        setTimeout(() => {
            t.classList.remove('show');
            t.addEventListener('transitionend', () => t.remove(), {once: true});
            setTimeout(() => t.remove(), 400);
        }, duration);
    }

    // ---------- Transport with DPoP Proof-of-Possession ----------
    async function api(path, options) {
        const settings = Object.assign({}, options);
        const headers = Object.assign({}, settings.headers);
        const held = token.get();
        if (held) {
            headers['Authorization'] = 'Bearer ' + held;
        }

        // Cryptographic Proof-of-Possession (DPoP): Sign METHOD|PATH|TIMESTAMP with device key
        try {
            const method = (settings.method || 'GET').toUpperCase();
            const sigInfo = await TeilenCrypto.signRequest(method, path);
            if (sigInfo) {
                headers['X-Teilen-Signature'] = sigInfo.signature;
                headers['X-Teilen-Timestamp'] = sigInfo.timestamp;
                if (sigInfo.publicKey) {
                    headers['X-Teilen-Public-Key'] = sigInfo.publicKey;
                }
            }
        } catch (e) {
            // Key not initialized yet during initial handshake
        }

        settings.headers = headers;
        settings.credentials = 'same-origin';

        let response = await fetch(path, settings);
        if (response.status === 401 && !path.includes('/api/auth/')) {
            const refreshed = await trySilentRefresh();
            if (refreshed) {
                headers['Authorization'] = 'Bearer ' + token.get();
                settings.headers = headers;
                response = await fetch(path, settings);
            } else {
                token.set(null);
                showPairing();
            }
        }
        return response;
    }

    async function trySilentRefresh() {
        try {
            const response = await fetch('/api/auth/refresh', {
                method: 'POST',
                credentials: 'same-origin'
            });
            if (response.ok) {
                const session = await response.json();
                token.set(session.token);
                return true;
            }
        } catch (e) {}
        return false;
    }

    async function why(response) {
        const body = await response.json().catch(() => ({}));
        return body.message || 'HTTP ' + response.status;
    }

    async function postJson(path, body) {
        return api(path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
    }

    // ---------- Multi-Session Rooms (Spaces) (Task 4) ----------

    async function loadSessions() {
        try {
            const response = await api(SESSIONS_API);
            if (response.ok) {
                const sessions = await response.json();
                if (Array.isArray(sessions) && sessions.length > 0) {
                    availableSessions = sessions;
                }
            }
        } catch (e) {
            console.warn('Failed to load sessions', e);
        }

        if (!availableSessions || availableSessions.length === 0) {
            availableSessions = [{
                id: 'personal',
                name: 'Personal Vault',
                isPersonal: true,
                role: 'OWNER',
                memberCount: 1
            }];
        }

        if (!currentSessionId || !availableSessions.some(s => s.id === currentSessionId)) {
            currentSessionId = availableSessions[0].id;
        }

        renderSessionPills();
        TeilenLan.setSession(currentSessionId);
    }

    function renderSessionPills() {
        if (!availableSessions || availableSessions.length === 0) {
            availableSessions = [{
                id: 'personal',
                name: t('personal_vault'),
                isPersonal: true,
                role: 'OWNER',
                memberCount: 1
            }];
        }

        const activeSession = availableSessions.find(s => s.id === currentSessionId) || availableSessions[0];
        if (activeSessionName) {
            activeSessionName.textContent = activeSession ? (activeSession.isPersonal ? t('personal_vault') : activeSession.name) : t('personal_vault');
        }
        const isCustomRoom = activeSession && !activeSession.isPersonal;
        if (sessionShareBtn) {
            sessionShareBtn.hidden = !isCustomRoom;
        }
        if (sessionDeleteBtn) {
            sessionDeleteBtn.hidden = !isCustomRoom;
            if (isCustomRoom) {
                sessionDeleteBtn.textContent = activeSession.role === 'OWNER' ? ('🗑 ' + t('delete_room')) : ('🚪 ' + t('leave_room'));
            }
        }
        if (shareSessionDeleteBtn) {
            shareSessionDeleteBtn.hidden = !isCustomRoom;
            if (isCustomRoom) {
                shareSessionDeleteBtn.textContent = activeSession.role === 'OWNER' ? ('🗑 ' + t('delete_room')) : ('🚪 ' + t('leave_room'));
            }
        }

        if (sessionDropdownList) {
            sessionDropdownList.replaceChildren(...availableSessions.map(session => {
                const btn = document.createElement('button');
                btn.type = 'button';
                const isActive = session.id === currentSessionId || (session.isPersonal && (!currentSessionId || currentSessionId === 'personal'));
                btn.className = 'session-dropdown-item' + (isActive ? ' active' : '');
                const icon = session.isPersonal ? '🔒' : '👥';
                const sName = session.isPersonal ? t('personal_vault') : session.name;
                btn.innerHTML = `<span>${icon}</span> <span class="session-dropdown-name">${sName}</span>` +
                    (!session.isPersonal && session.memberCount > 1 ? ` <span class="session-dropdown-count">${session.memberCount}</span>` : '');
                btn.addEventListener('click', () => {
                    if (sessionDropdown) sessionDropdown.hidden = true;
                    selectSession(session.id);
                });
                return btn;
            }));
        }

        if (sessionPills) {
            sessionPills.replaceChildren(...availableSessions.map(session => {
                const pill = document.createElement('button');
                pill.type = 'button';
                const isActive = session.id === currentSessionId || (session.isPersonal && (!currentSessionId || currentSessionId === 'personal'));
                pill.className = 'session-pill' + (isActive ? ' active' : '');
                pill.dataset.id = session.id || 'personal';

                const icon = session.isPersonal ? '🔒' : '👥';
                const sName = session.isPersonal ? t('personal_vault') : session.name;
                pill.innerHTML = `<span>${icon}</span> <span>${sName}</span>` +
                    (!session.isPersonal && session.memberCount > 1 ? ` <span class="session-pill-badge">${session.memberCount}</span>` : '');

                pill.addEventListener('click', () => selectSession(session.id));
                return pill;
            }));
        }
    }

    async function selectSession(sessionId) {
        if (currentSessionId === sessionId) return;
        currentSessionId = sessionId;
        renderSessionPills();
        TeilenLan.setSession(currentSessionId);

        // Scope feed to selected session
        cards.clear();
        feed.replaceChildren();
        await load();
    }

    // Modal helpers
    function openModal(modal) {
        if (!modal) return;
        modal.hidden = false;
    }
    function closeModal(modal) {
        if (!modal) return;
        modal.hidden = true;
        if (modal === modalPairPhone) {
            clearInterval(pairTimer);
        }
    }

    if (sessionSelectorPill) {
        sessionSelectorPill.addEventListener('click', (e) => {
            e.stopPropagation();
            if (sessionDropdown) sessionDropdown.hidden = !sessionDropdown.hidden;
        });
    }

    if (sessionDropdownJoinBtn) {
        sessionDropdownJoinBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (sessionDropdown) sessionDropdown.hidden = true;
            if (joinSessionInput) joinSessionInput.value = '';
            if (joinSessionPreview) joinSessionPreview.hidden = true;
            openModal(modalJoinSession);
            if (joinSessionInput) joinSessionInput.focus();
        });
    }
    document.querySelectorAll('.modal-close').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const veil = e.target.closest('.modal-veil');
            if (veil) closeModal(veil);
        });
    });
    document.querySelectorAll('.modal-veil').forEach(veil => {
        veil.addEventListener('click', (e) => {
            if (e.target === veil) closeModal(veil);
        });
    });

    // Create Room
    sessionCreateBtn?.addEventListener('click', () => {
        if (createSessionName) createSessionName.value = '';
        openModal(modalCreateSession);
        if (createSessionName) createSessionName.focus();
    });

    formCreateSession?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = createSessionName?.value?.trim() || '';
        if (!name) return;

        try {
            // Generate fresh random 256-bit symmetric session key
            const sessionKey = await TeilenCrypto.generateSessionKey();
            const response = await postJson(SESSIONS_API, {
                name,
                encryptedSessionKey: null // Master of creator wraps locally
            });

            if (!response.ok) {
                toast('Could not create room: ' + (await why(response)), 'error');
                return;
            }

            const created = await response.json();
            await TeilenCrypto.setSessionKey(created.id, sessionKey);
            closeModal(modalCreateSession);
            toast('Room created: ' + name, 'success');
            await loadSessions();
            await selectSession(created.id);
        } catch (err) {
            toast('Error creating room: ' + err.message, 'error');
        }
    });

    // Share Room (Invite Code / QR with #key fragment)
    sessionShareBtn?.addEventListener('click', async () => {
        const current = availableSessions.find(s => s.id === currentSessionId);
        if (!current) return;
        if (current.isPersonal) {
            toast('Personal Vault is private to your devices. Create a shared room to invite friends or teammates.', 'info');
            return;
        }

        try {
            const response = await postJson(`${SESSIONS_API}/${current.id}/invite`, {});
            if (!response.ok) {
                toast('Could not generate invite: ' + (await why(response)), 'error');
                return;
            }
            const invite = await response.json();
            const sessionKey = await TeilenCrypto.getSessionKey(current.id);
            const keyFragment = sessionKey ? '#key=' + encodeURIComponent(TeilenCrypto.b64(sessionKey)) : '';
            const shareableUrl = `${location.origin}/#join?code=${invite.inviteCode}${keyFragment}`;

            if (shareSessionTitle) shareSessionTitle.textContent = `Share Room: ${current.name}`;
            if (shareSessionCode) shareSessionCode.textContent = invite.inviteCode;

            if (shareSessionCopy) {
                shareSessionCopy.onclick = async () => {
                    await navigator.clipboard.writeText(shareableUrl);
                    toast('Copied invite link with secure key to clipboard', 'success');
                };
            }

            openModal(modalShareSession);
        } catch (err) {
            toast('Failed to share session', 'error');
        }
    });

    // Delete / Leave Room Handler
    async function handleDeleteOrLeaveActiveRoom() {
        const activeSession = availableSessions.find(s => s.id === currentSessionId);
        if (!activeSession || activeSession.isPersonal) return;
        const isOwner = activeSession.role === 'OWNER';
        const actionWord = isOwner ? 'Delete' : 'Leave';
        const confirmMsg = isOwner ? t('confirm_delete_room') : t('confirm_leave_room');

        if (!confirm(confirmMsg)) return;

        try {
            const response = await api(`${SESSIONS_API}/${activeSession.id}`, { method: 'DELETE' });
            if (response.ok || response.status === 204) {
                toast(`Room '${activeSession.name}' ${isOwner ? 'deleted' : 'left'}`, 'success');
                if (sessionDropdown) sessionDropdown.hidden = true;
                closeModal(modalShareSession);
                currentSessionId = 'personal';
                await loadSessions();
                await selectSession('personal');
            } else {
                toast(`Failed to ${actionWord.toLowerCase()} room: ` + (await why(response)), 'error');
            }
        } catch (err) {
            toast(`Failed to ${actionWord.toLowerCase()} room: ` + err.message, 'error');
        }
    }

    sessionDeleteBtn?.addEventListener('click', handleDeleteOrLeaveActiveRoom);
    shareSessionDeleteBtn?.addEventListener('click', handleDeleteOrLeaveActiveRoom);

    // Join Room
    sessionJoinBtn?.addEventListener('click', () => {
        if (joinSessionInput) {
            joinSessionInput.value = '';
        }
        if (joinSessionPreview) {
            joinSessionPreview.hidden = true;
        }
        openModal(modalJoinSession);
        if (joinSessionInput) {
            joinSessionInput.focus();
        }
    });

    formJoinSession?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const raw = joinSessionInput?.value?.trim() || '';
        if (!raw) return;

        let code = raw;
        let keyB64 = null;

        // Parse code and #key fragment if a URL was pasted
        if (raw.includes('code=')) {
            const urlObj = new URL(raw, location.origin);
            code = urlObj.searchParams.get('code') || raw;
            if (urlObj.hash.includes('key=')) {
                keyB64 = decodeURIComponent(urlObj.hash.split('key=')[1]);
            }
        } else if (raw.includes('#key=')) {
            const parts = raw.split('#key=');
            code = parts[0];
            keyB64 = decodeURIComponent(parts[1]);
        }

        code = code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();

        try {
            const response = await postJson(`${SESSIONS_API}/join`, {
                inviteCode: code,
                encryptedSessionKey: null
            });

            if (!response.ok) {
                toast('Could not join room: ' + (await why(response)), 'error');
                return;
            }

            const joined = await response.json();
            if (keyB64) {
                await TeilenCrypto.setSessionKey(joined.id, TeilenCrypto.unb64(keyB64));
            }

            closeModal(modalJoinSession);
            toast('Joined room: ' + joined.name, 'success');
            await loadSessions();
            await selectSession(joined.id);
        } catch (err) {
            toast('Error joining room: ' + err.message, 'error');
        }
    });

    // Auto-join from URL hash if user opened an invite link
    async function checkUrlForInvite() {
        const hash = location.hash;
        if (hash.includes('join?code=') || hash.includes('code=')) {
            const parts = hash.split('code=')[1];
            if (parts) {
                const codeAndKey = parts.split('#key=');
                const code = (codeAndKey[0] || '').split('&')[0];
                const keyB64 = codeAndKey[1] ? decodeURIComponent(codeAndKey[1]) : null;
                if (code) {
                    try {
                        const response = await postJson(`${SESSIONS_API}/join`, {
                            inviteCode: code,
                            encryptedSessionKey: null
                        });
                        if (response.ok) {
                            const joined = await response.json();
                            if (keyB64) {
                                await TeilenCrypto.setSessionKey(joined.id, TeilenCrypto.unb64(keyB64));
                            }
                            toast('Auto-joined room: ' + joined.name, 'success');
                            await loadSessions();
                            await selectSession(joined.id);
                        }
                    } catch (e) {}
                }
            }
            history.replaceState(null, '', location.pathname);
        }
    }

    // ---------- Pre-Send TTL Selection (Task 1) ----------

    document.querySelectorAll('.ttl-pill').forEach(pill => {
        if (pill.id === 'ttl-custom-pill') return;
        pill.addEventListener('click', () => {
            document.querySelectorAll('.ttl-pill').forEach(p => p.classList.remove('active'));
            pill.classList.add('active');
            selectedTtlSeconds = parseInt(pill.dataset.ttl, 10);
            ttlCustomPill.textContent = 'Custom';
        });
    });

    ttlCustomPill.addEventListener('click', () => {
        activeCustomTtlItemId = null; // Setting composer TTL
        customTtlTitle.textContent = 'Set Expiration Timer (TTL)';
        openModal(modalCustomTtl);
    });

    formCustomTtl.addEventListener('submit', async (e) => {
        e.preventDefault();
        const count = parseInt(customTtlNumber.value, 10) || 1;
        const multiplier = parseInt(customTtlUnit.value, 10) || 3600;
        let totalSec = count * multiplier;
        // Clamp between 10s and 86400s (backend valid TTL limits)
        totalSec = Math.max(10, Math.min(86400, totalSec));

        closeModal(modalCustomTtl);

        if (activeCustomTtlItemId) {
            // In-Feed Card TTL update
            await updateCardTtl(activeCustomTtlItemId, totalSec);
            activeCustomTtlItemId = null;
        } else {
            // Composer TTL update
            selectedTtlSeconds = totalSec;
            document.querySelectorAll('.ttl-pill').forEach(p => p.classList.remove('active'));
            ttlCustomPill.classList.add('active');
            ttlCustomPill.textContent = `Custom (${formatDurationShort(totalSec)})`;
            toast(`TTL set to ${formatDurationShort(totalSec)}`, 'info');
        }
    });

    function formatDurationShort(sec) {
        if (sec < 3600) return Math.round(sec / 60) + 'm';
        if (sec < 86400) return (sec / 3600).toFixed(1).replace('.0', '') + 'h';
        return (sec / 86400).toFixed(1).replace('.0', '') + 'd';
    }

    // ---------- Feed Cards with Sticky Top Header Bar (Tasks 1, 2, 3) ----------

    const BLOB_TYPES = new Set(['FILE', 'IMAGE', 'AUDIO', 'PDF']);

    function formatSize(bytes) {
        if (!bytes && bytes !== 0) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }

    async function copyImageToClipboard(item, href, btn) {
        try {
            const resp = await api(href);
            if (!resp.ok) {
                toast('Failed to load image', 'error');
                return;
            }
            const blob = await resp.blob();

            let pngBlob = blob;
            if (blob.type !== 'image/png') {
                pngBlob = await new Promise((resolve, reject) => {
                    const img = new Image();
                    img.crossOrigin = 'anonymous';
                    img.onload = () => {
                        const canvas = document.createElement('canvas');
                        canvas.width = img.naturalWidth || img.width;
                        canvas.height = img.naturalHeight || img.height;
                        const ctx = canvas.getContext('2d');
                        ctx.drawImage(img, 0, 0);
                        canvas.toBlob((b) => {
                            if (b) resolve(b);
                            else reject(new Error('Canvas conversion failed'));
                        }, 'image/png');
                    };
                    img.onerror = () => reject(new Error('Image decode error'));
                    img.src = URL.createObjectURL(blob);
                });
            }

            await navigator.clipboard.write([
                new ClipboardItem({ 'image/png': pngBlob })
            ]);

            if (btn) {
                btn.classList.add('copied');
                const orig = btn.innerHTML;
                btn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"></polyline></svg> <span>Copied</span>`;
                setTimeout(() => {
                    btn.classList.remove('copied');
                    btn.innerHTML = orig;
                }, 1500);
            }
            toast('Image copied to clipboard', 'success');
        } catch (err) {
            console.error('Image clipboard copy error:', err);
            try {
                await navigator.clipboard.writeText(href);
                toast('Image link copied to clipboard', 'info');
            } catch (e) {
                toast('Could not copy image: ' + err.message, 'error');
            }
        }
    }

    function upsert(item, isNew) {
        if (cards.has(item.id)) return;

        const li = document.createElement('li');
        li.className = 'card' + (isNew ? ' new' : '');
        li.dataset.id = item.id;

        // Sticky Top Action Header (Zero buttons at the bottom!)
        const head = document.createElement('div');
        head.className = 'card-head';

        // Left Header: Icon + Title Snippet + Route + Time
        const headLeft = document.createElement('div');
        headLeft.className = 'card-head-left';

        const badge = document.createElement('span');
        badge.className = 'badge ' + item.type.toLowerCase();
        badge.textContent = badgeLabel(item);

        const titleSnippet = document.createElement('span');
        titleSnippet.className = 'card-title-snippet';
        titleSnippet.textContent = item.content ? item.content.slice(0, 30) : '';

        const isLan = !!(item.isLan || String(item.id).startsWith('lan-'));
        const routePill = document.createElement('span');
        routePill.className = 'route-pill ' + (isLan ? 'lan' : 'relay');
        routePill.innerHTML = isLan
            ? `<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="#10B981" stroke-width="2.5"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg> Direct LAN`
            : `<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"></path></svg> Relay`;

        const at = document.createElement('span');
        at.className = 'card-time';
        at.textContent = new Date(item.createdAt).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});

        headLeft.append(badge, titleSnippet, routePill, at);

        // Right Header: Compact Action Toolbar + ⋮ Overflow Menu
        const headRight = document.createElement('div');
        headRight.className = 'card-head-right';

        // 1. Circular TTL Countdown Ring
        const clock = document.createElement('span');
        clock.className = 'ttl';
        const ring = document.createElementNS(SVG_NS, 'svg');
        ring.setAttribute('class', 'ring');
        ring.setAttribute('viewBox', '0 0 36 36');
        ring.setAttribute('width', '24');
        ring.setAttribute('height', '24');
        const track = document.createElementNS(SVG_NS, 'circle');
        track.setAttribute('class', 'ring-track');
        track.setAttribute('cx', '18');
        track.setAttribute('cy', '18');
        track.setAttribute('r', '15.5');
        const fill = document.createElementNS(SVG_NS, 'circle');
        fill.setAttribute('class', 'ring-fill');
        fill.setAttribute('cx', '18');
        fill.setAttribute('cy', '18');
        fill.setAttribute('r', '15.5');
        ring.append(track, fill);

        const ttlText = document.createElement('span');
        ttlText.className = 'ttl-text';
        clock.append(ring, ttlText);
        headRight.append(clock);

        const href = item.blobUrl || (API + '/' + item.id + '/blob');
        let textContainer = null;

        // Primary Action Buttons
        if (item.type === 'IMAGE') {
            // IMAGE: [ 📋 Copy ] and [ ⬇ Save ]
            const copyImgBtn = document.createElement('button');
            copyImgBtn.type = 'button';
            copyImgBtn.className = 'btn-head-action';
            copyImgBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg> <span>${t('btn_copy')}</span>`;
            copyImgBtn.title = t('btn_copy');
            copyImgBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                copyImageToClipboard(item, href, copyImgBtn);
            });

            const dlBtn = document.createElement('button');
            dlBtn.type = 'button';
            dlBtn.className = 'btn-head-action';
            dlBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg> <span>${t('btn_save')}</span>`;
            dlBtn.title = t('btn_save');
            dlBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                triggerBlobDownload(item, href);
            });

            headRight.append(copyImgBtn, dlBtn);
        } else if (BLOB_TYPES.has(item.type)) {
            // FILE / PDF / AUDIO: [ ⬇ Save ]
            const dlBtn = document.createElement('button');
            dlBtn.type = 'button';
            dlBtn.className = 'btn-head-action';
            dlBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg> <span>${t('btn_save')}</span>`;
            dlBtn.title = t('btn_save');
            dlBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                triggerBlobDownload(item, href);
            });
            headRight.append(dlBtn);
        } else {
            // TEXT / LINK: [ 📋 Copy ]
            const copyBtn = document.createElement('button');
            copyBtn.type = 'button';
            copyBtn.className = 'btn-head-action';
            copyBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg> <span>${t('btn_copy')}</span>`;
            copyBtn.title = t('btn_copy');
            copyBtn.addEventListener('click', async (e) => {
                e.stopPropagation();
                if (navigator.vibrate) navigator.vibrate(15);
                await navigator.clipboard.writeText(item.content);
                copyBtn.classList.add('copied');
                const orig = copyBtn.innerHTML;
                copyBtn.innerHTML = `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"></polyline></svg> <span>✓ ${t('btn_copied')}</span>`;
                toast(t('btn_copied'), 'success');
                setTimeout(() => {
                    copyBtn.classList.remove('copied');
                    copyBtn.innerHTML = orig;
                }, 1500);
            });
            headRight.append(copyBtn);
        }

        // Always-visible [ +1h ] Extend Button
        if (!isLan) {
            const extBtn = document.createElement('button');
            extBtn.type = 'button';
            extBtn.className = 'btn-head-action subtle';
            extBtn.innerHTML = `<span>${t('btn_extend_1h')}</span>`;
            extBtn.title = t('btn_extend_1h_menu');
            extBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                extendItem(item.id);
            });
            headRight.append(extBtn);
        }

        // Always-visible [ ✕ ] Delete Button
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'btn-head-action danger';
        delBtn.innerHTML = `<span>✕</span>`;
        delBtn.title = t('btn_delete');
        delBtn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (isLan) {
                remove(item.id);
                toast('Item deleted', 'info');
            } else {
                const res = await api(API + '/' + item.id, {method: 'DELETE'});
                if (res.ok) {
                    remove(item.id);
                    toast('Item deleted', 'info');
                } else {
                    toast('Could not delete item', 'error');
                }
            }
        });
        headRight.append(delBtn);

        // Quick Actions Overflow Menu (⋮)
        const menuContainer = document.createElement('div');
        menuContainer.className = 'card-menu-container';

        const moreBtn = document.createElement('button');
        moreBtn.type = 'button';
        moreBtn.className = 'btn-card-more';
        moreBtn.title = 'More options';
        moreBtn.innerHTML = `<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><circle cx="12" cy="5" r="2"></circle><circle cx="12" cy="12" r="2"></circle><circle cx="12" cy="19" r="2"></circle></svg>`;

        const dropdown = document.createElement('div');
        dropdown.className = 'card-menu-dropdown';
        dropdown.hidden = true;

        moreBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const wasHidden = dropdown.hidden;
            document.querySelectorAll('.card-menu-dropdown').forEach(d => d.hidden = true);
            dropdown.hidden = !wasHidden;
        });

        // 1. Extend +1h & Custom TTL
        if (!isLan) {
            const optExtend = document.createElement('button');
            optExtend.type = 'button';
            optExtend.className = 'card-menu-item';
            optExtend.innerHTML = `<span>⏳</span> <span>${t('btn_extend_1h_menu')}</span>`;
            optExtend.addEventListener('click', (e) => {
                e.stopPropagation();
                dropdown.hidden = true;
                extendItem(item.id);
            });
            dropdown.append(optExtend);

            const optCustomTtl = document.createElement('button');
            optCustomTtl.type = 'button';
            optCustomTtl.className = 'card-menu-item';
            optCustomTtl.innerHTML = `<span>⏱</span> <span>${t('btn_custom_timer_menu')}</span>`;
            optCustomTtl.addEventListener('click', (e) => {
                e.stopPropagation();
                dropdown.hidden = true;
                activeCustomTtlItemId = item.id;
                customTtlTitle.textContent = t('modal_ttl_title');
                openModal(modalCustomTtl);
            });
            dropdown.append(optCustomTtl);
        }

        // 2. Code toggle & Full View (for TEXT)
        if (item.type === 'TEXT') {
            const optCode = document.createElement('button');
            optCode.type = 'button';
            optCode.className = 'card-menu-item';
            optCode.innerHTML = `<span>&lt;&gt;</span> <span>${t('btn_toggle_code_menu')}</span>`;
            optCode.addEventListener('click', (e) => {
                e.stopPropagation();
                dropdown.hidden = true;
                if (textContainer) textContainer.classList.toggle('code-mode');
            });

            const optFull = document.createElement('button');
            optFull.type = 'button';
            optFull.className = 'card-menu-item';
            optFull.innerHTML = `<span>⤢</span> <span>${t('btn_toggle_full_menu')}</span>`;
            optFull.addEventListener('click', (e) => {
                e.stopPropagation();
                dropdown.hidden = true;
                if (textContainer) textContainer.classList.toggle('expanded');
            });

            dropdown.append(optCode, optFull);
        }

        // 3. Share link
        const optShare = document.createElement('button');
        optShare.type = 'button';
        optShare.className = 'card-menu-item';
        optShare.innerHTML = `<span>🔗</span> <span>${t('share_link_menu')}</span>`;
        optShare.addEventListener('click', async (e) => {
            e.stopPropagation();
            dropdown.hidden = true;
            const shareUrl = BLOB_TYPES.has(item.type) ? href : item.content;
            if (navigator.share) {
                try {
                    await navigator.share({
                        title: 'Teilen Share',
                        text: item.content,
                        url: BLOB_TYPES.has(item.type) ? href : undefined
                    });
                    return;
                } catch (err) {}
            }
            await navigator.clipboard.writeText(shareUrl);
            toast('Share link copied to clipboard', 'success');
        });
        dropdown.append(optShare);

        // 4. Delete
        const optDelete = document.createElement('button');
        optDelete.type = 'button';
        optDelete.className = 'card-menu-item danger';
        optDelete.innerHTML = `<span>🗑</span> <span>${t('btn_delete')}</span>`;
        optDelete.addEventListener('click', async (e) => {
            e.stopPropagation();
            dropdown.hidden = true;
            if (isLan) {
                remove(item.id);
                toast('Item deleted', 'info');
                return;
            }
            try {
                const response = await api(API + '/' + item.id, {method: 'DELETE'});
                if (response.ok || response.status === 204) {
                    remove(item.id);
                    toast('Item deleted', 'info');
                } else {
                    toast('Could not delete: ' + (await why(response)), 'error');
                }
            } catch (err) {
                toast('Network error deleting item', 'error');
            }
        });
        dropdown.append(optDelete);

        menuContainer.append(moreBtn, dropdown);
        headRight.append(menuContainer);
        head.append(headLeft, headRight);

        // Card Body (Unlimited Scrollable Container - ZERO buttons at bottom!)
        const body = document.createElement('div');
        body.className = 'card-body';

        if (item.type === 'LINK') {
            const a = document.createElement('a');
            a.href = item.content;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = item.content;
            a.className = 'link-content';
            body.append(a);
        } else if (BLOB_TYPES.has(item.type)) {
            body.append(renderBlobBody(item, href));
        } else {
            // Unlimited Scrollable Text (Task 2)
            textContainer = document.createElement('div');
            textContainer.className = 'card-text-scroll';
            textContainer.textContent = item.content;

            const bottomFade = document.createElement('div');
            bottomFade.className = 'card-bottom-fade';

            body.append(textContainer, bottomFade);
        }

        li.append(head, body);
        feed.prepend(li);
        cards.set(item.id, {item, element: li, ttl: ttlText, ring: fill});
        setTimeout(() => li.classList.remove('new'), 1500);
        refresh();
    }

    function renderBlobBody(item, href) {
        const wrap = document.createElement('div');
        wrap.className = 'file';

        const meta = document.createElement('div');
        meta.className = 'file-meta';

        const name = document.createElement('a');
        name.href = href;
        name.target = '_blank';
        name.rel = 'noopener noreferrer';
        name.className = 'file-name';
        name.textContent = item.content;
        meta.append(name);

        const size = formatSize(item.sizeBytes);
        if (size) {
            const s = document.createElement('span');
            s.className = 'file-size';
            s.textContent = size;
            meta.append(s);
        }
        wrap.append(meta);

        if (item.type === 'IMAGE') {
            const preview = document.createElement('img');
            preview.className = 'file-preview';
            preview.src = href;
            preview.alt = item.content;
            preview.loading = 'lazy';
            preview.addEventListener('error', () => preview.remove());
            wrap.append(preview);
        } else if (item.type === 'AUDIO') {
            const audio = document.createElement('audio');
            audio.className = 'file-audio';
            audio.controls = true;
            audio.src = href;
            wrap.append(audio);
        }
        return wrap;
    }

    function badgeLabel(item) {
        if (item.type === 'LINK') return t('badge_link');
        if (item.type === 'IMAGE') return t('badge_image');
        if (item.type === 'PDF') return t('badge_pdf');
        if (item.type === 'AUDIO') return t('badge_audio');
        if (item.type === 'FILE') return t('badge_file');
        return t('badge_text');
    }

    async function triggerBlobDownload(item, href) {
        try {
            toast('Downloading ' + (item.content || 'file') + '…', 'info');
            const resp = await fetch(href);
            if (!resp.ok) throw new Error('Download failed');
            const blob = await resp.blob();
            const blobUrl = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = item.content || 'file';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(blobUrl), 10000);
            toast('Downloaded ' + (item.content || 'file'), 'success');
        } catch (err) {
            const a = document.createElement('a');
            a.href = href;
            a.download = item.content || 'file';
            a.target = '_blank';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        }
    }

    async function extendItem(id) {
        try {
            const response = await api('/api/v1/items/' + id + '/extend', {method: 'POST'});
            if (!response.ok) {
                toast('Could not extend: ' + (await why(response)), 'error');
                return;
            }
            applyExtended(await response.json());
            toast('Extended +1 hour', 'success');
        } catch (e) {
            toast('Failed to extend lifespan', 'error');
        }
    }

    async function updateCardTtl(id, ttlSeconds) {
        try {
            const response = await api('/api/v1/items/' + id + '/ttl', {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ttlSeconds})
            });
            if (!response.ok) {
                toast('Could not update timer: ' + (await why(response)), 'error');
                return;
            }
            applyExtended(await response.json());
            toast(`Timer updated (${formatDurationShort(ttlSeconds)})`, 'success');
        } catch (e) {
            toast('Failed to update timer', 'error');
        }
    }

    function applyExtended(item) {
        const entry = cards.get(item.id);
        if (!entry) return;
        entry.item = item;
        entry.ttl.textContent = formatLeft(remaining(item));
        refresh();
    }

    function remove(id) {
        const entry = cards.get(id);
        if (!entry) return;
        cards.delete(id);
        entry.element.classList.add('leaving');
        setTimeout(() => {
            entry.element.remove();
            refresh();
        }, 300);
    }

    function refresh() {
        const count = cards.size;
        countEl.textContent = count === 1 ? t('item_count_single') : t('item_count_multi', count);
        empty.hidden = count > 0;
        clearBtn.hidden = count === 0;
    }

    function remaining(item) {
        return Math.max(0, Math.round((new Date(item.expiresAt).getTime() - Date.now()) / 1000));
    }

    function formatLeft(seconds) {
        if (seconds <= 0) return 'expired';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm';
        return m > 0 ? m + 'm ' + String(s).padStart(2, '0') + 's' : s + 's';
    }

    function totalSeconds(item) {
        return Math.max(1, Math.round((new Date(item.expiresAt).getTime() - new Date(item.createdAt).getTime()) / 1000));
    }

    // Tick countdown rings
    setInterval(() => {
        for (const [id, entry] of cards) {
            const left = remaining(entry.item);
            entry.ttl.textContent = formatLeft(left);
            if (entry.ring) {
                const total = totalSeconds(entry.item);
                const fraction = total > 0 ? Math.max(0, Math.min(1, left / total)) : 0;
                const circumference = 2 * Math.PI * 15.5; // ~97.389
                entry.ring.style.strokeDasharray = circumference.toFixed(1);
                entry.ring.style.strokeDashoffset = (circumference * (1 - fraction)).toFixed(1);
                entry.ring.classList.toggle('warn', fraction <= 0.20 && fraction > 0.05);
                entry.ring.classList.toggle('danger', fraction <= 0.05);
            }
            if (left <= 0) {
                remove(id);
            }
        }
    }, 1000);

    // ---------- Composer & Sending (Tasks 1, 2, 5) ----------

    function isUrl(text) {
        return /^https?:\/\/\S+$/i.test(text.trim());
    }

    // Unlimited Text Composer Submit
    compose.addEventListener('submit', async (e) => {
        e.preventDefault();
        const content = composeInput.value.trim();
        if (!content) return;

        composeInput.value = '';
        toast('Encrypting & sending…', 'info');

        const type = isUrl(content) ? 'LINK' : 'TEXT';
        const response = await postJson(API, {
            content,
            type,
            ttlSeconds: selectedTtlSeconds,
            sessionId: currentSessionId
        });

        if (response.ok) {
            toast('Sent', 'success');
        } else {
            const message = await why(response);
            toast('Could not send: ' + message, 'error');
            composeInput.value = content;
        }
    });

    // Enter submits, Shift+Enter adds newline
    composeInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            compose.dispatchEvent(new Event('submit', {cancelable: true}));
        }
    });

    // File attachments with Multi-GB Tiered Policy (Task 5)
    uploadInput.addEventListener('change', async () => {
        const files = [...uploadInput.files];
        uploadInput.value = '';
        if (files.length === 0) return;

        for (const file of files) {
            if (file.size > MAX_RELAY_BYTES) {
                // Multi-GB Direct LAN P2P
                await sendMultiGbLan(file);
            } else {
                // Standard Cloud Relay upload
                await uploadCloudFile(file);
            }
        }
    });

    async function uploadCloudFile(file) {
        toast(`Uploading ${file.name}…`, 'info');
        const body = new FormData();
        body.append('file', file);
        body.append('ttlSeconds', selectedTtlSeconds);
        if (currentSessionId) {
            body.append('sessionId', currentSessionId);
        }

        const response = await api(API, {method: 'POST', body});
        if (response.ok) {
            toast(`Sent ${file.name}`, 'success');
        } else {
            toast(`Failed to send ${file.name}: ` + (await why(response)), 'error');
        }
    }

    async function sendMultiGbLan(file) {
        toast(`⚡ Initiating Direct LAN P2P for ${file.name} (${formatSize(file.size)})`, 'info');
        showTelemetry({
            direction: 'tx',
            fileName: file.name,
            bytesSent: 0,
            totalBytes: file.size,
            percent: 0,
            speedMBps: '0.0'
        });

        activeLanTransfer = TeilenLan.sendFile(
            file,
            (progress) => {
                showTelemetry({
                    direction: 'tx',
                    fileName: file.name,
                    bytesSent: progress.bytesSent,
                    totalBytes: progress.totalBytes,
                    percent: progress.percent,
                    speedMBps: progress.speedMBps
                });
            },
            () => {
                toast(`⚡ Direct LAN transfer complete: ${file.name}`, 'success');
                hideTelemetry();
            },
            (err) => {
                hideTelemetry();
                toast('Direct LAN Transfer Notice: ' + err, 'warn');
                toast('Files over 30 MB transfer directly device-to-device for free without server storage. Make sure the receiving device is open on the same Wi-Fi/Hotspot.', 'info', 7000);
            }
        );
    }

    // Telemetry UI
    function showTelemetry(info) {
        if (!info) {
            hideTelemetry();
            return;
        }
        telemetryCard.hidden = false;
        telemetryName.textContent = info.fileName;
        telemetryBar.style.width = info.percent + '%';
        const sent = formatSize(info.bytesSent || info.bytesReceived || 0);
        const total = formatSize(info.totalBytes || 0);
        telemetryStats.textContent = `${sent} / ${total} · ${info.percent}%`;
        telemetrySpeed.textContent = `${info.speedMBps} MB/s`;
    }

    function hideTelemetry() {
        telemetryCard.hidden = true;
        activeLanTransfer = null;
    }

    telemetryCancel.addEventListener('click', () => {
        if (activeLanTransfer) {
            activeLanTransfer.cancel();
            hideTelemetry();
            toast('LAN transfer cancelled', 'info');
        }
    });

    // Global Paste (Ctrl+V)
    window.addEventListener('paste', (event) => {
        if (!token.get()) return;
        const target = event.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return;

        const items = event.clipboardData && event.clipboardData.items;
        if (!items) return;

        const files = [];
        for (const item of items) {
            if (item.kind === 'file') {
                const file = item.getAsFile();
                if (file) files.push(file);
            } else if (item.kind === 'string' && item.type === 'text/plain') {
                item.getAsString(async (value) => {
                    const text = (value || '').trim();
                    if (text) {
                        toast('Pasted text — encrypting & sending…', 'info');
                        await postJson(API, {
                            content: text,
                            type: isUrl(text) ? 'LINK' : 'TEXT',
                            ttlSeconds: selectedTtlSeconds,
                            sessionId: currentSessionId
                        });
                    }
                });
            }
        }
        if (files.length) {
            event.preventDefault();
            files.forEach(f => f.size > MAX_RELAY_BYTES ? sendMultiGbLan(f) : uploadCloudFile(f));
        }
    });

    // Drag-and-drop
    let dragDepth = 0;
    window.addEventListener('dragenter', () => {
        if (!token.get()) return;
        dragDepth++;
        dropVeil.hidden = false;
    });
    window.addEventListener('dragover', (e) => {
        if (token.get()) e.preventDefault();
    });
    window.addEventListener('dragleave', () => {
        dragDepth = Math.max(0, dragDepth - 1);
        if (dragDepth === 0) dropVeil.hidden = true;
    });
    window.addEventListener('drop', (e) => {
        dragDepth = 0;
        dropVeil.hidden = true;
        if (!token.get() || !e.dataTransfer) return;
        e.preventDefault();
        const files = [...e.dataTransfer.files];
        if (files.length) {
            files.forEach(f => f.size > MAX_RELAY_BYTES ? sendMultiGbLan(f) : uploadCloudFile(f));
        }
    });

    // Purge All
    clearBtn.addEventListener('click', async () => {
        if (cards.size === 0) return;
        const toDelete = [...cards.keys()];
        toDelete.forEach(id => remove(id));
        toast('Purging ephemeral feed…', 'info');
        await Promise.all(toDelete.map(id => {
            if (id.startsWith('lan-')) return Promise.resolve();
            return api(API + '/' + id, {method: 'DELETE'}).catch(() => {});
        }));
    });

    // ---------- WebSocket Feed & Polling ----------

    let currentWsToken = null;
    let currentWsSessionId = null;
    let wsReconnectTimer = null;

    async function load() {
        try {
            const path = currentSessionId ? `${API}?sessionId=${encodeURIComponent(currentSessionId)}` : API;
            const response = await api(path);
            if (response.status === 401) {
                console.warn("Token expired or invalid (401). Clearing and re-provisioning...");
                localStorage.removeItem("teilen_token");
                await silentProvisionVault();
                return;
            }
            const items = await response.json();
            items.reverse().forEach(item => upsert(item, false));
        } catch (e) {
            console.warn('Load failed', e);
        }
    }

    function connect() {
        const curToken = token.get() || '';
        if (!curToken) return;

        if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
            if (currentWsToken === curToken && currentWsSessionId === currentSessionId) {
                return;
            }
        }

        stopSocket();
        currentWsToken = curToken;
        currentWsSessionId = currentSessionId;

        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        let wsUrl = `${scheme}://${location.host}/ws?token=${encodeURIComponent(curToken)}`;
        if (currentSessionId) wsUrl += `&sessionId=${encodeURIComponent(currentSessionId)}`;

        const live = new WebSocket(wsUrl);
        socket = live;

        live.addEventListener('open', () => {
            if (socket !== live) return;
            retryDelay = 2000;
            setStatus('status_live', 'live');
            stopPolling();
            load();
        });

        live.addEventListener('message', (message) => {
            let event;
            try {
                event = JSON.parse(message.data);
            } catch (e) {
                return;
            }
            if (event.event === 'created' && event.item) {
                if (!event.item.sessionId || event.item.sessionId === currentSessionId) {
                    upsert(event.item, true);
                }
            } else if (event.event === 'extended' && event.item) {
                applyExtended(event.item);
            } else if (event.event === 'deleted' && event.id) {
                remove(event.id);
            } else if (event.event === 'sync_all' || event.event === 'vault_merged') {
                loadSessions().then(() => load());
                loadDevices();
            } else if (event.event === 'kicked') {
                token.set(null);
                setStoredUserId(null);
                setStoredDeviceId(null);
                stopSocket();
                showPairing();
                toast('This device was disconnected', 'info');
            }
        });

        live.addEventListener('close', () => {
            if (socket !== live) return;
            socket = null;
            setStatus('status_reconnecting', 'offline');
            startPolling();
            clearTimeout(wsReconnectTimer);
            wsReconnectTimer = setTimeout(() => {
                if (token.get()) {
                    connect();
                }
            }, 2000);
        });

        live.addEventListener('error', () => {
            try {
                live.close();
            } catch (_) {}
        });
    }

    function stopSocket() {
        clearTimeout(wsReconnectTimer);
        currentWsToken = null;
        currentWsSessionId = null;
        const old = socket;
        socket = null;
        if (old) {
            try {
                old.close();
            } catch (_) {}
        }
    }

    function startPolling() {
        if (pollTimer) return;
        pollTimer = setInterval(async () => {
            const ids = new Set();
            try {
                const path = currentSessionId ? `${API}?sessionId=${encodeURIComponent(currentSessionId)}` : API;
                const response = await api(path);
                const items = await response.json();
                items.forEach(item => {
                    ids.add(item.id);
                    upsert(item, false);
                });
            } catch (e) {}
            for (const id of [...cards.keys()]) {
                if (!ids.has(id)) remove(id);
            }
        }, 3000);
    }

    function stopPolling() {
        clearInterval(pollTimer);
        pollTimer = null;
    }

    let currentStatusKey = 'status_connecting';
    let currentStatusKind = 'offline';

    function setStatus(textOrKey, kind) {
        currentStatusKey = textOrKey;
        currentStatusKind = kind;
        const statusText = el('status-text');
        if (statusText) statusText.textContent = t(textOrKey);
        if (statusEl) {
            statusEl.className = 'status ' + kind;
        }
    }

    function updateStatusDisplay() {
        const statusText = el('status-text');
        if (statusText) statusText.textContent = t(currentStatusKey);
    }

    // ---------- Devices & Account Section ----------

    async function loadDevices() {
        try {
            const devRes = await api('/api/devices');
            if (devRes.ok) {
                const devs = await devRes.json();
                renderDevices(devs);
            }
        } catch (e) {
            console.warn('Could not load devices:', e);
        }
    }

    function renderDevices(devices) {
        if (!Array.isArray(devices)) devices = [];

        // Hero Pairing Card: show when user has <= 1 connected device, hide when >= 2 connected devices
        if (heroPairingCard) {
            const hasMultiple = devices.length > 1;
            if (hasMultiple || heroPairingDismissed) {
                heroPairingCard.hidden = true;
            } else {
                heroPairingCard.hidden = false;
            }
        }

        const devCount = devices.length;
        if (modalDevicesTitle) {
            modalDevicesTitle.textContent = `${t('connected_devices_title')} (${devCount})`;
        }
        if (modalDisconnectOthersBtn) {
            modalDisconnectOthersBtn.hidden = devCount <= 1;
            modalDisconnectOthersBtn.textContent = t('btn_disconnect_all');
        }

        if (modalDevicesList) {
            modalDevicesList.replaceChildren(...devices.map(device => {
                const li = document.createElement('li');
                li.className = 'modal-device-row';

                const info = document.createElement('div');
                info.className = 'modal-device-info';

                const name = document.createElement('span');
                name.className = 'modal-device-name';
                name.textContent = device.name || (device.deviceType === 'PHONE' ? 'Phone' : 'Browser');
                info.appendChild(name);

                if (device.current) {
                    const badge = document.createElement('span');
                    badge.className = 'modal-device-badge';
                    badge.textContent = t('this_device_badge');
                    info.appendChild(badge);
                }

                li.appendChild(info);

                if (!device.current) {
                    const disconnectBtn = document.createElement('button');
                    disconnectBtn.type = 'button';
                    disconnectBtn.className = 'btn-device-disconnect';
                    disconnectBtn.textContent = t('btn_disconnect');
                    disconnectBtn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        disconnectBtn.disabled = true;
                        try {
                            const res = await api('/api/devices/' + device.id, {method: 'DELETE'});
                            if (res.ok) {
                                toast('Device disconnected', 'info');
                                loadDevices();
                            } else {
                                disconnectBtn.disabled = false;
                                toast('Could not disconnect device', 'error');
                            }
                        } catch (err) {
                            disconnectBtn.disabled = false;
                        }
                    });
                    li.appendChild(disconnectBtn);
                }
                return li;
            }));
        }

        if (deviceList) {
            deviceList.replaceChildren(...devices.map(device => {
                const row = document.createElement('li');
                row.className = 'device';

                const label = document.createElement('span');
                label.className = 'device-name';
                label.textContent = device.name + (device.current ? ' (this browser)' : '');

                const meta = document.createElement('span');
                meta.className = 'device-meta';
                const details = [];
                if (device.platform) details.push(device.platform.slice(0, 30));
                if (device.ipAddress) details.push(device.ipAddress);
                details.push(new Date(device.lastSeenAt).toLocaleDateString());
                meta.textContent = details.join(' · ');

                row.append(label, meta);

                if (!device.current) {
                    const dropBtn = document.createElement('button');
                    dropBtn.className = 'btn-device-drop';
                    dropBtn.textContent = 'Revoke';
                    dropBtn.type = 'button';
                    dropBtn.addEventListener('click', async () => {
                        dropBtn.disabled = true;
                        const res = await api('/api/devices/' + device.id, {method: 'DELETE'});
                        if (res.ok) {
                            toast('Device revoked', 'info');
                            loadDevices();
                        } else {
                            dropBtn.disabled = false;
                            toast('Could not revoke device', 'error');
                        }
                    });
                    row.append(dropBtn);
                }
                return row;
            }));
        }
    }

    if (modalDisconnectOthersBtn) {
        modalDisconnectOthersBtn.addEventListener('click', async () => {
            if (!confirm('Disconnect all other devices?')) return;
            try {
                const res = await api('/api/devices/others', {method: 'DELETE'});
                if (res.ok) {
                    toast('All other devices disconnected', 'success');
                    loadDevices();
                } else {
                    toast('Failed to disconnect other devices', 'error');
                }
            } catch (e) {
                toast('Failed to disconnect other devices', 'error');
            }
        });
    }

    // Revoke all other devices (Task 6.3)
    if (revokeOthersBtn) {
        revokeOthersBtn.addEventListener('click', async () => {
            if (!confirm('Sign out and revoke all other devices?')) return;
            const res = await api('/api/devices/others', {method: 'DELETE'});
            if (res.ok) {
                toast('All other devices revoked', 'success');
                const devRes = await api('/api/devices');
                if (devRes.ok) renderDevices(await devRes.json());
            } else {
                toast('Failed to revoke other devices', 'error');
            }
        });
    }

    // Sign out
    signOutBtn.addEventListener('click', async () => {
        await api('/api/auth/logout', {method: 'POST'});
        token.set(null);
        showPairing();
        toast('Signed out', 'info');
    });

    // Account menu toggle
    accountBtn.addEventListener('click', async () => {
        const wasOpen = !deviceSection.hidden;
        if (wasOpen) {
            deviceSection.hidden = true;
            accountBtn.setAttribute('aria-expanded', 'false');
            return;
        }
        deviceSection.hidden = false;
        accountBtn.setAttribute('aria-expanded', 'true');
        const res = await api('/api/devices');
        if (res.ok) renderDevices(await res.json());
    });

    // ---------- Auth, Login & Pairing ----------

    function showPairing() {
        stopSocket();
        stopPolling();
        TeilenLan.stop();
        cards.clear();
        feed.replaceChildren();
        pairSection.hidden = false;
        app.hidden = true;
        foot.hidden = true;
        deviceSection.hidden = true;
        accountBtn.hidden = true;
        setStatus('status_offline', 'offline');
        startPairing();
    }

    async function showFeed() {
        pairSection.hidden = true;
        app.hidden = false;
        foot.hidden = false;
        accountBtn.hidden = false;

        await loadSessions();
        await load();
        connect();

        try {
            const devRes = await api('/api/devices');
            if (devRes.ok) {
                renderDevices(await devRes.json());
            }
        } catch (e) {
            console.warn('Could not load devices:', e);
        }

        // Start LAN WebRTC engine with session scoping and telemetry handler
        TeilenLan.start(token.get(), (lanItem) => {
            upsert(lanItem, true);
            toast('Received Direct LAN file: ' + lanItem.content, 'success');
        }, showTelemetry, currentSessionId);

        await checkUrlForInvite();
    }

    // Instant Zero-Auth Vault Provisioning
    async function silentProvisionVault() {
        try {
            toast('Initializing instant vault…', 'info');
            await TeilenCrypto.ensureMasterKey();
            const identity = await TeilenCrypto.ensureIdentity();
            const randChars = Math.random().toString(36).substring(2, 8).toLowerCase();
            const username = 'vault' + randChars;
            const password = Array.from(crypto.getRandomValues(new Uint8Array(16)))
                .map(b => b.toString(16).padStart(2, '0')).join('');
            const deviceName = navigator.userAgent.includes('Chrome') ? 'Chrome' :
                navigator.userAgent.includes('Firefox') ? 'Firefox' :
                navigator.userAgent.includes('Safari') ? 'Safari' : 'Browser';

            const res = await fetch('/api/auth/account', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({
                    username,
                    password,
                    displayName: 'Personal Vault',
                    deviceName,
                    deviceType: 'WEB',
                    devicePublicKey: TeilenCrypto.b64(identity.publicKey),
                    clientFingerprint: getClientFingerprint()
                })
            });

            if (res.ok) {
                const session = await res.json();
                token.set(session.token);
                if (session.userId) setStoredUserId(session.userId);
                if (session.deviceId) setStoredDeviceId(session.deviceId);
                toast('Instant vault initialized (' + username + ')', 'success');
                await showFeed();
                return true;
            } else {
                toast('Provisioning failed: ' + (await why(res)), 'error');
                return false;
            }
        } catch (err) {
            console.error('Silent provisioning error:', err);
            toast('Failed to create instant vault: ' + err.message, 'error');
            return false;
        }
    }

    if (btnInstantVault) {
        btnInstantVault.addEventListener('click', () => {
            silentProvisionVault();
        });
    }

    if (btnToggleAuthAdvanced) {
        btnToggleAuthAdvanced.addEventListener('click', () => {
            const hidden = authAdvancedContainer.hidden;
            authAdvancedContainer.hidden = !hidden;
            if (authAdvancedChevron) authAdvancedChevron.textContent = hidden ? '▴' : '▾';
        });
    }

    // Tab switching for Auth
    if (authTabLogin) {
        authTabLogin.addEventListener('click', () => {
            authTabLogin.classList.add('active');
            if (authTabRegister) authTabRegister.classList.remove('active');
            if (authTabPair) authTabPair.classList.remove('active');
            loginContainer.hidden = false;
            registerContainer.hidden = true;
            if (pairContainer) pairContainer.hidden = false; // Pairing stays visible in main body
        });
    }

    if (authTabRegister) {
        authTabRegister.addEventListener('click', () => {
            authTabRegister.classList.add('active');
            if (authTabLogin) authTabLogin.classList.remove('active');
            if (authTabPair) authTabPair.classList.remove('active');
            registerContainer.hidden = false;
            loginContainer.hidden = true;
            if (pairContainer) pairContainer.hidden = false; // Pairing stays visible in main body
        });
    }

    if (authTabPair) {
        authTabPair.addEventListener('click', () => {
            authTabPair.classList.add('active');
            if (authTabLogin) authTabLogin.classList.remove('active');
            if (authTabRegister) authTabRegister.classList.remove('active');
            pairContainer.hidden = false;
            loginContainer.hidden = true;
            registerContainer.hidden = true;
            startPairing();
        });
    }

    loginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = loginUsername.value.trim();
        const password = loginPassword.value;
        const identity = await TeilenCrypto.ensureIdentity();

        loginStatus.hidden = false;
        loginStatus.textContent = 'Signing in…';

        const res = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({
                username,
                password,
                deviceName: navigator.userAgent.includes('Chrome') ? 'Chrome' : 'Browser',
                deviceType: 'WEB',
                devicePublicKey: TeilenCrypto.b64(identity.publicKey)
            })
        });

        if (res.ok) {
            const session = await res.json();
            token.set(session.token);
            toast('Signed in as ' + username, 'success');
            await showFeed();
        } else {
            loginStatus.textContent = 'Sign in failed: ' + (await why(res));
        }
    });

    registerForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = registerUsername.value.trim();
        const password = registerPassword.value;
        const displayName = registerDisplayName.value.trim() || undefined;
        const identity = await TeilenCrypto.ensureIdentity();

        registerStatus.hidden = false;
        registerStatus.textContent = 'Creating account…';

        const res = await fetch('/api/auth/account', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({
                username,
                password,
                displayName,
                deviceName: navigator.userAgent.includes('Chrome') ? 'Chrome' : 'Browser',
                deviceType: 'WEB',
                devicePublicKey: TeilenCrypto.b64(identity.publicKey)
            })
        });

        if (res.ok) {
            const session = await res.json();
            token.set(session.token);
            toast('Account created!', 'success');
            await showFeed();
        } else {
            registerStatus.textContent = 'Registration failed: ' + (await why(res));
        }
    });

    async function startPairing() {
        clearInterval(pairTimer);
        try {
            const master = await TeilenCrypto.getMasterKey();
            const masterB64 = master ? TeilenCrypto.b64(master) : null;
            const res = await postJson('/api/auth/device/code', {
                deviceName: navigator.userAgent.includes('Chrome') ? 'Chrome' :
                            navigator.userAgent.includes('Firefox') ? 'Firefox' :
                            navigator.userAgent.includes('Safari') ? 'Safari' : 'Browser',
                deviceType: 'WEB',
                platform: navigator.platform || 'Web',
                clientFingerprint: getClientFingerprint(),
                masterKeyPackage: masterB64
            });

            if (!res.ok) {
                console.error('Pairing code fetch failed with HTTP', res.status);
                return;
            }

            const data = await res.json();
            const codeVal = data.userCode;
            pairUserCode = codeVal;

            if (pairCode && codeVal) pairCode.textContent = codeVal;
            if (pairState) pairState.textContent = t('pair_code_hint');

            if (heroPairCode && codeVal) heroPairCode.textContent = codeVal;
            if (heroPairStatus) heroPairStatus.textContent = t('pair_modal_subtitle');

            if (modalPairCode && codeVal) modalPairCode.textContent = codeVal;
            if (modalPairStatus) modalPairStatus.textContent = t('pair_modal_subtitle');

            if (data.deviceCode) {
                pairTimer = setInterval(async () => {
                    try {
                        const tokenRes = await postJson('/api/auth/device/token', {
                            deviceCode: data.deviceCode
                        });
                        if (tokenRes.status === 200) {
                            clearInterval(pairTimer);
                            const claimData = await tokenRes.json();
                            if (claimData.token) {
                                token.set(claimData.token);
                            }
                            if (claimData.user && claimData.user.id) {
                                setStoredUserId(claimData.user.id);
                            }
                            if (claimData.deviceId) {
                                setStoredDeviceId(claimData.deviceId);
                            }
                            if (claimData.keyPackage) {
                                const incomingKey = TeilenCrypto.unb64(claimData.keyPackage);
                                const currentMaster = await TeilenCrypto.getMasterKey();
                                if (currentMaster && TeilenCrypto.b64(currentMaster) !== claimData.keyPackage) {
                                    await TeilenCrypto.addLegacyKey(currentMaster);
                                }
                                await TeilenCrypto.setMasterKey(incomingKey);
                            }
                            toast(t('toast_paired_success'), 'success');
                            closeModal(modalPairPhone);
                            stopSocket();
                            connect();
                            await loadSessions();
                            await load();
                            await loadDevices();
                        } else if (tokenRes.status !== 202) {
                            clearInterval(pairTimer);
                        }
                    } catch (e) {
                        // ignore polling error
                    }
                }, 2500);
            }
        } catch (e) {
            console.warn('Pairing generation error:', e);
        }
    }

    const copyPairCode = async () => {
        if (pairUserCode) {
            await navigator.clipboard.writeText(pairUserCode);
            toast(t('btn_copied'), 'success');
        }
    };
    if (pairCodeCopy) pairCodeCopy.addEventListener('click', copyPairCode);
    if (heroCodeCopy) heroCodeCopy.addEventListener('click', copyPairCode);
    if (modalPairCodeCopy) modalPairCodeCopy.addEventListener('click', copyPairCode);

    if (btnLangToggle) {
        btnLangToggle.addEventListener('click', () => {
            applyLocale(currentLang === 'ar' ? 'en' : 'ar');
        });
    }

    if (btnHeaderPair) {
        btnHeaderPair.addEventListener('click', () => {
            openModal(modalPairPhone);
            loadDevices();
            startPairing();
        });
    }

    if (heroPairingDismiss) {
        heroPairingDismiss.addEventListener('click', () => {
            heroPairingDismissed = true;
            if (heroPairingCard) heroPairingCard.hidden = true;
        });
    }

    function normalizeUserCodeInput(raw) {
        if (!raw) return '';
        let s = String(raw).trim();
        if (s.includes('code=')) {
            const idx = s.indexOf('code=');
            let after = s.substring(idx + 5);
            const amp = after.indexOf('&');
            if (amp >= 0) after = after.substring(0, amp);
            const hash = after.indexOf('#');
            if (hash >= 0) after = after.substring(0, hash);
            s = after;
        }
        s = s.toUpperCase().replace(/[^A-Z0-9]/g, '');
        return s;
    }

    async function handlePairWithCode(userCode, statusEl, inputEl) {
        if (!userCode) return;
        const normalized = normalizeUserCodeInput(userCode);
        if (normalized.length !== 8) {
            if (statusEl) {
                statusEl.hidden = false;
                statusEl.textContent = 'Enter an 8-character pairing code (e.g. K7PM-3XQD)';
            }
            return;
        }
        if (statusEl) {
            statusEl.hidden = false;
            statusEl.textContent = 'Connecting…';
        }
        try {
            const master = await TeilenCrypto.getMasterKey();
            const masterB64 = master ? TeilenCrypto.b64(master) : null;
            const res = await postJson('/api/auth/device/pair', {
                userCode: normalized,
                scannerMasterKey: masterB64,
                deviceName: navigator.userAgent.includes('Chrome') ? 'Chrome' :
                            navigator.userAgent.includes('Firefox') ? 'Firefox' :
                            navigator.userAgent.includes('Safari') ? 'Safari' : 'Browser',
                deviceType: 'WEB',
                platform: navigator.platform || 'Web',
                clientFingerprint: getClientFingerprint()
            });

            if (!res.ok) {
                if (statusEl) statusEl.textContent = 'Pairing failed: ' + (await why(res));
                return;
            }

            const pairData = await res.json();
            if (pairData.token) {
                token.set(pairData.token);
            }
            if (pairData.userId) {
                setStoredUserId(pairData.userId);
            }
            if (pairData.deviceId) {
                setStoredDeviceId(pairData.deviceId);
            }
            if (pairData.masterKey) {
                const incomingKey = TeilenCrypto.unb64(pairData.masterKey);
                const currentMaster = await TeilenCrypto.getMasterKey();
                if (currentMaster && TeilenCrypto.b64(currentMaster) !== pairData.masterKey) {
                    await TeilenCrypto.addLegacyKey(currentMaster);
                }
                await TeilenCrypto.setMasterKey(incomingKey);
            }

            if (inputEl) inputEl.value = '';
            if (statusEl) statusEl.textContent = 'Connected!';
            toast(t('toast_paired_success'), 'success');
            closeModal(modalPairPhone);
            connect();
            await loadSessions();
            await load();
            await loadDevices();
        } catch (err) {
            if (statusEl) statusEl.textContent = 'Error: ' + err.message;
        }
    }

    approveForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        await handlePairWithCode(approveCode.value.trim(), deviceStatus, approveCode);
    });

    if (heroApproveForm && heroApproveCode) {
        heroApproveForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            await handlePairWithCode(heroApproveCode.value.trim(), heroApproveStatus, heroApproveCode);
        });
    }

    // 8-Box Segmented Auto-Advancing Pairing Input
    const otpBoxes = Array.from(document.querySelectorAll('#modal-approve-form .otp-box'));
    const modalPairPasteBtn = el('modal-pair-paste-btn');

    function getOtpCode() {
        return otpBoxes.map(b => b.value).join('').toUpperCase();
    }

    function populateOtpCode(code) {
        if (!code) return;
        const clean = code.replace(/[^a-zA-Z0-9]/g, '').toUpperCase().slice(0, 8);
        otpBoxes.forEach((box, i) => {
            box.value = clean[i] || '';
        });
        if (clean.length === 8) {
            otpBoxes[7]?.focus();
            triggerOtpSubmit();
        } else if (clean.length > 0) {
            const nextIdx = Math.min(clean.length, 7);
            otpBoxes[nextIdx]?.focus();
        }
    }

    function triggerOtpSubmit() {
        const raw = getOtpCode();
        if (raw.length === 8) {
            const formatted = raw.slice(0, 4) + '-' + raw.slice(4);
            handlePairWithCode(formatted, modalApproveStatus, otpBoxes[7]);
        }
    }

    otpBoxes.forEach((box, index) => {
        box.addEventListener('input', (e) => {
            const val = box.value;
            if (val.length > 1) {
                populateOtpCode(val);
                return;
            }
            const clean = val.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
            box.value = clean;
            if (clean.length === 1) {
                if (index < 7) {
                    otpBoxes[index + 1].focus();
                    otpBoxes[index + 1].select();
                } else if (index === 7) {
                    triggerOtpSubmit();
                }
            }
        });

        box.addEventListener('keydown', (e) => {
            if (e.key === 'Backspace') {
                if (!box.value && index > 0) {
                    e.preventDefault();
                    otpBoxes[index - 1].value = '';
                    otpBoxes[index - 1].focus();
                }
            } else if (e.key === 'ArrowLeft' && index > 0) {
                e.preventDefault();
                otpBoxes[index - 1].focus();
                otpBoxes[index - 1].select();
            } else if (e.key === 'ArrowRight' && index < 7) {
                e.preventDefault();
                otpBoxes[index + 1].focus();
                otpBoxes[index + 1].select();
            }
        });

        box.addEventListener('paste', (e) => {
            e.preventDefault();
            const text = (e.clipboardData || window.clipboardData)?.getData('text') || '';
            populateOtpCode(text);
        });

        box.addEventListener('focus', () => {
            box.select();
        });
    });

    modalPairPasteBtn?.addEventListener('click', async () => {
        try {
            const text = await navigator.clipboard.readText();
            if (text) {
                populateOtpCode(text);
            }
        } catch (err) {
            toast('Could not access clipboard. Paste manually into the first box.', 'info');
        }
    });

    if (modalApproveForm) {
        modalApproveForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const raw = getOtpCode();
            if (raw.length === 8) {
                const formatted = raw.slice(0, 4) + '-' + raw.slice(4);
                await handlePairWithCode(formatted, modalApproveStatus, otpBoxes[0]);
            } else {
                if (modalApproveStatus) {
                    modalApproveStatus.hidden = false;
                    modalApproveStatus.textContent = 'Please enter all 8 characters';
                }
                const firstEmpty = otpBoxes.find(b => !b.value);
                firstEmpty?.focus();
            }
        });
    }

    // Global Ctrl+V / Cmd+V Paste-to-Send on Web SPA
    window.addEventListener('paste', async (e) => {
        const files = e.clipboardData?.files ? [...e.clipboardData.files] : [];
        if (files.length === 0) return; // Allow normal text paste to proceed

        e.preventDefault();
        toast(`Attaching ${files.length} item(s) from clipboard…`, 'info');
        for (const file of files) {
            let f = file;
            if (!f.name || f.name === 'image.png') {
                const ext = f.type ? (f.type.split('/')[1] || 'png') : 'png';
                const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
                f = new File([f], `pasted-${stamp}.${ext}`, { type: f.type || 'image/png' });
            }
            if (f.size > MAX_RELAY_BYTES) {
                await sendMultiGbLan(f);
            } else {
                await uploadCloudFile(f);
            }
        }
    });

    // Brand launch animation
    function initLaunchScreen() {
        const veil = el('launch-veil');
        const launchStatus = el('launch-status');
        if (!veil) return;
        setTimeout(() => {
            if (launchStatus) {
                launchStatus.style.opacity = '0';
                setTimeout(() => {
                    launchStatus.textContent = 'Syncing Ephemeral Feed…';
                    launchStatus.style.opacity = '1';
                }, 120);
            }
        }, 650);
        setTimeout(() => {
            veil.classList.add('dismissed');
            veil.hidden = true;
            setTimeout(() => {
                try { veil.remove(); } catch (e) {}
            }, 300);
        }, 1200);
    }

    // Auto-update SW
    function initServiceWorkerAutoUpdate() {
        if (!('serviceWorker' in navigator)) return;
        navigator.serviceWorker.register('/sw.js').catch(() => {});
    }

    function enablePush() {
        // Handled by push service worker if subscribed
    }

    // Initialize SPA
    applyLocale(currentLang);
    initLaunchScreen();
    initServiceWorkerAutoUpdate();
    renderSessionPills();
    refresh();

    // Outside-Click Dismissal for Dropdowns, Card Menus, and Modal Backdrops
    document.addEventListener('pointerdown', (e) => {
        // 1. Session switcher dropdown
        if (sessionDropdown && !sessionDropdown.hidden) {
            if (!sessionDropdown.contains(e.target) && !sessionSelectorPill?.contains(e.target) && !sessionCreateBtn?.contains(e.target)) {
                sessionDropdown.hidden = true;
            }
        }

        // 2. Card overflow menus
        document.querySelectorAll('.card-menu-dropdown:not([hidden])').forEach(menu => {
            const container = menu.closest('.card-menu-container');
            if (container && !container.contains(e.target)) {
                menu.hidden = true;
            }
        });

        // 3. Modals on backdrop click
        if (e.target.classList && (e.target.classList.contains('modal-veil') || e.target.classList.contains('modal-backdrop'))) {
            closeModal(e.target);
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (sessionDropdown && !sessionDropdown.hidden) {
                sessionDropdown.hidden = true;
            }
            document.querySelectorAll('.card-menu-dropdown:not([hidden])').forEach(menu => {
                menu.hidden = true;
            });
            document.querySelectorAll('.modal-veil:not([hidden]), .modal-backdrop:not([hidden])').forEach(m => {
                closeModal(m);
            });
        }
    });

    async function initApp() {
        try {
            // Unbreakable persistence rule: If token exists in storage, 100% preserve it and feed!
            if (token.get()) {
                await showFeed();
                return;
            }
            const authenticated = await trySilentRefresh();
            if (authenticated) {
                await showFeed();
                return;
            }
            // Auto silent-provision a zero-auth vault ONLY if no token exists in storage
            const provisioned = await silentProvisionVault();
            if (!provisioned) {
                showPairing();
            }
        } catch (err) {
            console.error('Startup initialization error:', err);
            token.set(null);
            showPairing();
        }
    }

    initApp();
})();
