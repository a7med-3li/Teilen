/*
 * The arrival signal. A closed tab still hears the push, but the payload carries nothing worth
 * leaking — the notification says only that the feed has something new; the feed itself is read
 * with the account token when the tab is focused.
 */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
    event.waitUntil(self.registration.showNotification('Teilen', {
        body: 'New item ready in your feed',
        icon: '/icon-512.png',
        badge: '/favicon.png',
        tag: 'teilen-arrival',
        renotify: true,
        data: {url: '/'}
    }));
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        self.clients.matchAll({type: 'window', includeUncontrolled: true}).then((windows) => {
            for (const client of windows) {
                if ('focus' in client) {
                    return client.focus();
                }
            }
            if (self.clients.openWindow) {
                return self.clients.openWindow('/');
            }
        })
    );
});
