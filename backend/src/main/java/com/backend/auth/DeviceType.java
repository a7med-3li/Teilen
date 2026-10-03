package com.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** What kind of app a paired device is. Purely informational — it shows up in the device list. */
public enum DeviceType {

    PHONE,
    WEB;

    public static DeviceType parse(String requested) {
        if (requested == null || requested.isBlank()) {
            return WEB;
        }
        try {
            return valueOf(requested.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown deviceType '" + requested + "', expected PHONE or WEB");
        }
    }
}
