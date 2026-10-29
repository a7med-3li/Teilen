// ==========================================================================
// Teilen Admin Telemetry & Live Ops Console - Client Logic
// ==========================================================================

(() => {
    'use strict';

    const TOKEN_KEY = 'teilen_admin_token';
    const USERNAME_KEY = 'teilen_admin_username';
    const POLL_INTERVAL_MS = 3000;

    let pollTimer = null;
    let isFetching = false;

    // Elements
    const loginView = document.getElementById('login-view');
    const dashboardView = document.getElementById('dashboard-view');
    const loginForm = document.getElementById('admin-login-form');
    const loginUsername = document.getElementById('admin-username');
    const loginPassword = document.getElementById('admin-password');
    const loginError = document.getElementById('login-error');
    const btnLogin = document.getElementById('btn-login');

    const adminUsernameDisplay = document.getElementById('admin-username-display');
    const btnRefresh = document.getElementById('btn-refresh');
    const btnLogout = document.getElementById('btn-logout');

    // KPI Elements
    const kpiOnlineUsers = document.getElementById('kpi-online-users');
    const kpiActiveTimeline = document.getElementById('kpi-active-timeline');
    const kpiFeedSockets = document.getElementById('kpi-feed-sockets');
    const kpiSignalSockets = document.getElementById('kpi-signal-sockets');

    const kpiTotalUsers = document.getElementById('kpi-total-users');
    const kpiUsersBreakdown = document.getElementById('kpi-users-breakdown');
    const kpiMultiDevice = document.getElementById('kpi-multi-device');
    const kpiTotalDevicesChip = document.getElementById('kpi-total-devices-chip');

    const kpiLiveItems = document.getElementById('kpi-live-items');
    const kpiItemsVolume = document.getElementById('kpi-items-volume');
    const kpiExpiringChip = document.getElementById('kpi-expiring-chip');
    const kpiCustomRoomsChip = document.getElementById('kpi-custom-rooms-chip');

    const kpiBlobBytes = document.getElementById('kpi-blob-bytes');
    const kpiDiskStorage = document.getElementById('kpi-disk-storage');
    const kpiJvmLabel = document.getElementById('kpi-jvm-label');
    const kpiJvmBar = document.getElementById('kpi-jvm-bar');
    const kpiUptimeLabel = document.getElementById('kpi-uptime-label');
    const kpiCpuLabel = document.getElementById('kpi-cpu-label');

    // Activity Table Elements
    const activityCountBadge = document.getElementById('activity-count-badge');
    const activityTableBody = document.getElementById('activity-table-body');

    // Chart Elements
    const contentTypeTotalLabel = document.getElementById('content-type-total-label');
    const contentTypeBars = document.getElementById('content-type-bars');
    const platformTotalLabel = document.getElementById('platform-total-label');
    const platformBars = document.getElementById('platform-bars');
    const ttlTotalLabel = document.getElementById('ttl-total-label');
    const ttlBars = document.getElementById('ttl-bars');

    // --- Authentication Helpers ---

    function getAdminToken() {
        return sessionStorage.getItem(TOKEN_KEY);
    }

    function setAdminAuth(token, username) {
        sessionStorage.setItem(TOKEN_KEY, token);
        sessionStorage.setItem(USERNAME_KEY, username || 'admin');
    }

    function clearAdminAuth() {
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(USERNAME_KEY);
    }

    function showDashboard() {
        loginView.hidden = true;
        loginView.style.display = 'none';
        dashboardView.hidden = false;
        dashboardView.style.display = 'flex';
        const username = sessionStorage.getItem(USERNAME_KEY) || 'admin';
        if (adminUsernameDisplay) adminUsernameDisplay.textContent = username;
        loadStats();
        startPolling();
    }

    function showLogin() {
        stopPolling();
        dashboardView.hidden = true;
        dashboardView.style.display = 'none';
        loginView.hidden = false;
        loginView.style.display = 'flex';
        if (loginError) {
            loginError.hidden = true;
            loginError.textContent = '';
        }
        if (loginPassword) loginPassword.value = '';
    }

    function startPolling() {
        stopPolling();
        pollTimer = setInterval(loadStats, POLL_INTERVAL_MS);
    }

    function stopPolling() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    // --- API Interactions ---

    async function handleLogin(e) {
        e.preventDefault();
        const username = loginUsername.value.trim();
        const password = loginPassword.value;

        if (!username || !password) return;

        loginError.hidden = true;
        btnLogin.disabled = true;
        btnLogin.querySelector('.btn-text').textContent = 'Authenticating…';

        try {
            const res = await fetch('/api/admin/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            if (res.ok) {
                const data = await res.json();
                setAdminAuth(data.adminToken, data.username);
                showDashboard();
            } else if (res.status === 429) {
                const errData = await res.json().catch(() => ({}));
                loginError.textContent = errData.message || 'Too many failed attempts. Please wait 5 minutes.';
                loginError.hidden = false;
            } else {
                loginError.textContent = 'Invalid admin credentials.';
                loginError.hidden = false;
            }
        } catch (err) {
            loginError.textContent = 'Network or connection error. Please try again.';
            loginError.hidden = false;
        } finally {
            btnLogin.disabled = false;
            btnLogin.querySelector('.btn-text').textContent = 'Sign In to Console';
        }
    }

    async function handleLogout() {
        const token = getAdminToken();
        if (token) {
            fetch('/api/admin/logout', {
                method: 'POST',
                headers: {
                    'X-Admin-Token': token,
                    'Authorization': 'Bearer ' + token
                }
            }).catch(() => {});
        }
        clearAdminAuth();
        showLogin();
    }

    async function loadStats() {
        if (isFetching) return;
        const token = getAdminToken();
        if (!token) {
            showLogin();
            return;
        }

        isFetching = true;
        try {
            const res = await fetch('/api/admin/stats', {
                headers: {
                    'X-Admin-Token': token,
                    'Authorization': 'Bearer ' + token
                }
            });

            if (res.status === 401) {
                console.warn('Admin token unauthorized (401), returning to login');
                clearAdminAuth();
                showLogin();
                return;
            }

            if (res.ok) {
                const data = await res.json();
                renderDashboard(data);
            }
        } catch (err) {
            console.warn('Failed to load telemetry stats:', err);
        } finally {
            isFetching = false;
        }
    }

    // --- Dashboard Rendering ---

    function renderDashboard(data) {
        if (!data) return;

        const { livePulse, recentActivity, usersAndDevices, ephemeralStorage, systemHealth } = data;

        // 1. KPI: Active Right Now
        if (livePulse) {
            kpiOnlineUsers.textContent = formatNumber(livePulse.onlineUsersCount);
            kpiActiveTimeline.textContent =
                `${formatNumber(livePulse.activeUsersLast5m)} active in 5m · ${formatNumber(livePulse.activeUsersLast24h)} in 24h`;
            kpiFeedSockets.textContent = `${formatNumber(livePulse.openFeedSocketsCount)} feed sockets`;
            kpiSignalSockets.textContent = `${formatNumber(livePulse.openSignalSocketsCount)} signal sockets`;
        }

        // 2. KPI: Total Vaults & Users
        if (usersAndDevices) {
            kpiTotalUsers.textContent = formatNumber(usersAndDevices.totalUsers);
            kpiUsersBreakdown.textContent =
                `${formatNumber(usersAndDevices.namedAccountsCount)} claimed · ${formatNumber(usersAndDevices.anonymousVaultsCount)} anonymous`;
            kpiMultiDevice.innerHTML = `<span>${formatNumber(usersAndDevices.multiDeviceVaultsCount)} multi-device vaults</span>`;
            kpiTotalDevicesChip.innerHTML = `<span>${formatNumber(usersAndDevices.totalPairedDevices)} paired devices</span>`;
        }

        // 3. KPI: Shared Items
        if (ephemeralStorage) {
            kpiLiveItems.textContent = formatNumber(ephemeralStorage.liveItemsTotal);
            const today = ephemeralStorage.lifetimeItemsShared ? ephemeralStorage.lifetimeItemsShared.today : 0;
            const allTime = ephemeralStorage.lifetimeItemsShared ? ephemeralStorage.lifetimeItemsShared.allTime : 0;
            kpiItemsVolume.textContent = `${formatNumber(today)} shared today · ${formatNumber(allTime)} all-time`;
            kpiExpiringChip.innerHTML = `<span>⏳ ${formatNumber(ephemeralStorage.expiringSoonCount)} expiring in &lt;10m</span>`;
            kpiCustomRoomsChip.innerHTML = `<span>${formatNumber(ephemeralStorage.totalCustomRooms)} custom rooms</span>`;
        }

        // 4. KPI: Encrypted Storage & JVM
        if (systemHealth) {
            kpiBlobBytes.textContent = formatBytes(systemHealth.activeBlobBytes);
            kpiDiskStorage.textContent =
                `${formatNumber(systemHealth.activeBlobCount)} blobs · ${formatBytes(systemHealth.diskFreeBytes)} free`;

            const usedMb = systemHealth.jvmMemoryUsedMb || 0;
            const maxMb = systemHealth.jvmMemoryMaxMb || 1;
            const percent = Math.min(100, Math.round((usedMb / maxMb) * 100));

            kpiJvmLabel.textContent = `${usedMb} / ${maxMb} MB (${percent}%)`;
            kpiJvmBar.style.width = `${percent}%`;

            kpiUptimeLabel.textContent = `Uptime: ${formatUptime(systemHealth.uptimeSeconds)}`;
            kpiCpuLabel.textContent = `CPU: ${systemHealth.cpuLoadPercent != null ? systemHealth.cpuLoadPercent : 0}%`;
        }

        // 5. Activity Table
        renderActivityTable(recentActivity);

        // 6. Breakdown Charts
        renderContentTypeMix(ephemeralStorage ? ephemeralStorage.liveItemsByType : {});
        renderPlatformMix(usersAndDevices ? usersAndDevices.devicesByPlatform : {});
        renderTtlChoices(ephemeralStorage ? ephemeralStorage.popularTtlChoices : {});
    }

    function renderActivityTable(activityList) {
        if (!activityList || activityList.length === 0) {
            activityTableBody.innerHTML = '<tr><td colspan="5" class="empty-state">No live activity events recorded yet.</td></tr>';
            activityCountBadge.textContent = '0 events';
            return;
        }

        activityCountBadge.textContent = `${activityList.length} events`;
        const rowsHtml = activityList.map(item => {
            const actionClass = getActionBadgeClass(item.action);
            const typeBadge = item.itemType ? getItemTypeBadge(item.itemType) : '';
            const platformIcon = getPlatformIcon(item.platform);

            return `
                <tr>
                    <td class="time-col">${escapeHtml(item.timestamp || '')}</td>
                    <td class="vault-col">${escapeHtml(item.vaultHash || 'Vault #anon')}</td>
                    <td><span class="action-badge ${actionClass}">${escapeHtml(formatActionLabel(item.action))}</span></td>
                    <td>${typeBadge}${escapeHtml(item.description || '')}</td>
                    <td><span class="platform-pill">${platformIcon} ${escapeHtml(formatDisplayPlatform(item.platform))}</span></td>
                </tr>
            `;
        }).join('');

        activityTableBody.innerHTML = rowsHtml;
    }

    function renderContentTypeMix(typeMap) {
        if (!typeMap) return;

        const colors = {
            TEXT: 'fill-slate',
            LINK: 'fill-cyan',
            IMAGE: 'fill-emerald',
            FILE: 'fill-amber',
            PDF: 'fill-rose',
            AUDIO: 'fill-purple'
        };

        const total = Object.values(typeMap).reduce((a, b) => a + b, 0);
        contentTypeTotalLabel.textContent = `${formatNumber(total)} active item${total === 1 ? '' : 's'}`;

        if (total === 0) {
            contentTypeBars.innerHTML = '<div class="chart-subtitle">No active items in feed</div>';
            return;
        }

        const keys = ['TEXT', 'LINK', 'IMAGE', 'FILE', 'PDF', 'AUDIO'];
        contentTypeBars.innerHTML = keys.map(key => {
            const count = typeMap[key] || 0;
            const pct = Math.round((count / total) * 100);
            const fillClass = colors[key] || 'fill-indigo';
            return renderBarRow(key, count, pct, fillClass);
        }).join('');
    }

    function renderPlatformMix(platformMap) {
        if (!platformMap) return;

        const colors = {
            Android: 'fill-emerald',
            Web: 'fill-indigo',
            Linux: 'fill-amber',
            Windows: 'fill-cyan',
            macOS: 'fill-purple',
            iOS: 'fill-rose'
        };

        const total = Object.values(platformMap).reduce((a, b) => a + b, 0);
        platformTotalLabel.textContent = `${formatNumber(total)} paired device${total === 1 ? '' : 's'}`;

        if (total === 0) {
            platformBars.innerHTML = '<div class="chart-subtitle">No devices registered</div>';
            return;
        }

        const entries = Object.entries(platformMap).filter(([_, count]) => count > 0);
        if (entries.length === 0) {
            platformBars.innerHTML = '<div class="chart-subtitle">No active devices</div>';
            return;
        }

        platformBars.innerHTML = entries.map(([platform, count]) => {
            const pct = Math.round((count / total) * 100);
            const fillClass = colors[platform] || 'fill-indigo';
            return renderBarRow(platform, count, pct, fillClass);
        }).join('');
    }

    function renderTtlChoices(ttlMap) {
        if (!ttlMap) return;

        const colors = {
            '10m': 'fill-rose',
            '30m': 'fill-amber',
            '1h': 'fill-emerald',
            '4h': 'fill-cyan',
            '24h': 'fill-indigo',
            'Custom': 'fill-purple'
        };

        const total = Object.values(ttlMap).reduce((a, b) => a + b, 0);
        ttlTotalLabel.textContent = `${formatNumber(total)} timer choice${total === 1 ? '' : 's'}`;

        if (total === 0) {
            ttlBars.innerHTML = '<div class="chart-subtitle">No TTL data yet</div>';
            return;
        }

        const keys = ['10m', '30m', '1h', '4h', '24h', 'Custom'];
        ttlBars.innerHTML = keys.map(key => {
            const count = ttlMap[key] || 0;
            const pct = total > 0 ? Math.round((count / total) * 100) : 0;
            const fillClass = colors[key] || 'fill-indigo';
            return renderBarRow(key, count, pct, fillClass);
        }).join('');
    }

    function renderBarRow(label, count, pct, fillClass) {
        return `
            <div class="chart-bar-row">
                <div class="chart-bar-info">
                    <span class="chart-bar-label">${escapeHtml(label)}</span>
                    <span class="chart-bar-value">${formatNumber(count)} (${pct}%)</span>
                </div>
                <div class="chart-bar-track">
                    <div class="chart-bar-fill ${fillClass}" style="width: ${pct}%"></div>
                </div>
            </div>
        `;
    }

    // --- Formatters & Helpers ---

    function formatNumber(n) {
        if (n == null) return '0';
        return Number(n).toLocaleString();
    }

    function formatBytes(bytes) {
        if (bytes == null || bytes <= 0) return '0 B';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }

    function formatUptime(seconds) {
        if (!seconds || seconds <= 0) return '0s';
        const d = Math.floor(seconds / 86400);
        const h = Math.floor((seconds % 86400) / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        if (d > 0) return `${d}d ${h}h ${m}m`;
        if (h > 0) return `${h}h ${m}m`;
        if (m > 0) return `${m}m ${s}s`;
        return `${s}s`;
    }

    function getActionBadgeClass(action) {
        switch (action) {
            case 'ITEM_SHARED': return 'action-item-shared';
            case 'DEVICE_PAIRED': return 'action-device-paired';
            case 'VAULT_MERGED': return 'action-vault-merged';
            case 'SESSION_CREATED': return 'action-session-created';
            case 'REAPER_PURGED': return 'action-reaper-purged';
            case 'LAN_P2P_COMPLETED': return 'action-lan-p2p';
            default: return 'action-item-shared';
        }
    }

    function formatActionLabel(action) {
        if (!action) return 'EVENT';
        return action.replace(/_/g, ' ');
    }

    function getItemTypeBadge(type) {
        const cls = 'badge-' + String(type).toLowerCase();
        return `<span class="item-type-badge ${cls}">${escapeHtml(type)}</span>`;
    }

    function getPlatformIcon(platform) {
        if (!platform) return '🌐';
        const p = platform.toUpperCase();
        if (p.includes('ANDROID')) return '🤖';
        if (p.includes('IOS') || p.includes('IPHONE') || p.includes('IPAD')) return '🍏';
        if (p.includes('MAC') || p.includes('DARWIN')) return '💻';
        if (p.includes('WIN')) return '🪟';
        if (p.includes('LINUX')) return '🐧';
        return '🌐';
    }

    function formatDisplayPlatform(platform) {
        if (!platform) return 'Web';
        const p = platform.toUpperCase();
        if (p.includes('ANDROID')) return 'Android';
        if (p.includes('IOS') || p.includes('IPHONE')) return 'iOS';
        if (p.includes('MAC')) return 'macOS';
        if (p.includes('WIN')) return 'Windows';
        if (p.includes('LINUX')) return 'Linux';
        return 'Web';
    }

    function escapeHtml(str) {
        if (str == null) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // --- Event Listeners & Bootstrapping ---

    loginForm.addEventListener('submit', handleLogin);
    btnRefresh.addEventListener('click', () => {
        btnRefresh.classList.add('spinning');
        loadStats().finally(() => {
            setTimeout(() => btnRefresh.classList.remove('spinning'), 400);
        });
    });
    btnLogout.addEventListener('click', handleLogout);

    // Initial check
    if (getAdminToken()) {
        showDashboard();
    } else {
        showLogin();
    }
})();
