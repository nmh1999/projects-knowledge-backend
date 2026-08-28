package com.projectsknowledge.general.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactionServiceTest {

    private final SecretRedactionService service = new SecretRedactionService();

    @Test
    void redactsSensitivePropertyValues() {
        assertThat(service.redact("payment.client-secret=ABC123")).isEqualTo("payment.client-secret= [REDACTED]");
        assertThat(service.redact("normal.timeout=30")).isEqualTo("normal.timeout=30");
    }

    @Test
    void redactsPrivateKeys() {
        assertThat(service.redact("-----BEGIN PRIVATE KEY-----abc")).isEqualTo("[REDACTED PRIVATE KEY]");
    }
}
