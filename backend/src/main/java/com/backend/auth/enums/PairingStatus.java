package com.backend.auth.enums;

public enum PairingStatus {

    /** waiting for someone to approve */
    PENDING,
    /** approved: a token exists but the newcomer has not collected it yet */
    APPROVED,
    /** the token was handed over */
    DELIVERED,
    /** somebody said no */
    DENIED
}
