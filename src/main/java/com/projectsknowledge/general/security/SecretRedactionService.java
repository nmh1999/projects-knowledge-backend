package com.projectsknowledge.general.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Removes secrets from source lines before they leave the backend. */
@Service
public class SecretRedactionService {

    private static final List<String> SENSITIVE_KEYS = List.of(
        "password",
        "secret",
        "client-secret",
        "client_secret",
        "api-key",
        "api_key",
        "token",
        "private-key",
        "private_key",
        "access-key",
        "access_key",
        "credentials"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
        "-----BEGIN(?:[ A-Z]+)? PRIVATE KEY-----.*",
        Pattern.CASE_INSENSITIVE
    );

    public String redact(String line) {
        if (line == null || line.isBlank()) return line;
        if (PRIVATE_KEY.matcher(line).find()) return "[REDACTED PRIVATE KEY]";
        String lower = line.toLowerCase();
        if (SENSITIVE_KEYS.stream().noneMatch(lower::contains)) return line;

        int separator = firstSeparator(line);
        if (separator >= 0) return line.substring(0, separator + 1) + " [REDACTED]";
        return "[REDACTED]";
    }

    private int firstSeparator(String line) {
        int result = -1;
        for (char separator : new char[] { '=', ':', '"' }) {
            int position = line.indexOf(separator);
            if (position >= 0 && (result < 0 || position < result)) result = position;
        }
        return result;
    }
}
