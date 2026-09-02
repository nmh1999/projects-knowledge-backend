package com.projectsknowledge.general.exception;

/** Stable error categories consumed by the browser; messages may change without breaking the contract. */
public enum ApiErrorCode {
    REQUEST_FAILED,
    INVALID_REQUEST,
    INVALID_REQUEST_ID,
    INTERNAL_ERROR,
    REQUEST_CANCELLED,
    CODEX_UNAVAILABLE,
    CODEX_TIMEOUT,
    CODEX_AUTH_REQUIRED,
    CODEX_INVALID_RESPONSE,
    CODEX_REQUEST_REJECTED,
    CODEX_REQUEST_FAILED,
}
