package com.projectsknowledge.general.integration.codex.schema.response;

/** Public connection metadata only; account names, emails and tokens are deliberately excluded. */
public record DtoCodexStatus(
    boolean enabled,
    boolean connected,
    boolean ready,
    String authenticationType,
    String model,
    String reasoningEffort,
    int activeRequests
) {
    public static DtoCodexStatus disabled(String model, String reasoningEffort) {
        return new DtoCodexStatus(false, false, false, "", clean(model), clean(reasoningEffort), 0);
    }

    public static DtoCodexStatus unavailable(String model, String reasoningEffort) {
        return new DtoCodexStatus(true, false, false, "", clean(model), clean(reasoningEffort), 0);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
