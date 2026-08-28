package com.projectsknowledge.general.scanner;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Structural hints only: names come from the repository, never a project/vendor catalog. */
final class RepositoryStructure {

    private static final Set<String> MODULE_CONTAINERS = Set.of("features", "modules", "domains", "business");
    private static final Set<String> INTEGRATION_CONTAINERS = Set.of(
        "integrations",
        "connectors",
        "adapters",
        "clients"
    );

    private RepositoryStructure() {}

    static List<String> modules(Path root, List<Path> files) {
        return discover(root, files, MODULE_CONTAINERS, false, 24);
    }

    static List<String> integrations(Path root, List<Path> files) {
        return discover(root, files, INTEGRATION_CONTAINERS, true, 30);
    }

    private static List<String> discover(
        Path root,
        List<Path> files,
        Set<String> containers,
        boolean includeClients,
        int limit
    ) {
        Set<String> names = new TreeSet<>();
        for (Path file : files) {
            Path relative = root.relativize(file);
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                if (!containers.contains(relative.getName(index).toString().toLowerCase(Locale.ROOT))) continue;
                String name = relative.getName(index + 1).toString();
                if (index + 1 == relative.getNameCount() - 1) {
                    if (!includeClients) continue;
                    name = name.replaceFirst("\\.[^.]+$", "");
                    String base = name.replaceFirst("(?i)(client|connector|adapter|integration|gateway)$", "");
                    if (base.equals(name)) continue;
                    name = base;
                }
                String label = humanize(name);
                if (!label.isBlank()) names.add(label);
            }
        }
        return names.stream().limit(limit).toList();
    }

    private static String humanize(String value) {
        return Arrays.stream(value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[-_.\\s]+"))
            .filter(part -> !part.isBlank())
            .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
            .reduce((first, second) -> first + " " + second)
            .orElse("");
    }
}
