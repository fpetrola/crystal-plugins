package dev.crystal.plugins.build.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps Maven versions onto the SemVer that PF4J requires for {@code Plugin-Version}. */
final class PluginVersions {

    private static final Pattern MAVEN = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-.](.+))?");

    private PluginVersions() {
    }

    /**
     * {@code 1} → {@code 1.0.0}, {@code 1.2} → {@code 1.2.0}, {@code 1.2.3-SNAPSHOT} unchanged,
     * {@code 1.2.3.Final} → {@code 1.2.3-Final}.
     *
     * @throws IllegalArgumentException if the version does not start with a number
     */
    static String toSemVer(String mavenVersion) {
        Matcher m = MAVEN.matcher(mavenVersion.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Version '" + mavenVersion
                    + "' cannot be mapped to SemVer (expected MAJOR[.MINOR[.PATCH]][-qualifier])");
        }
        String semver = m.group(1) + "." + orZero(m.group(2)) + "." + orZero(m.group(3));
        String qualifier = m.group(4);
        if (qualifier != null) {
            semver += "-" + qualifier.replaceAll("[^0-9A-Za-z.-]", "-");
        }
        return semver;
    }

    private static String orZero(String part) {
        return part == null ? "0" : part;
    }
}
