package dev.crystal.plugins.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SemVer 2.0 precedence, without dependencies. Versions that are not SemVer compare as plain strings. */
final class SemanticVersions {

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?");

    private SemanticVersions() {
    }

    static int compare(String a, String b) {
        Matcher x = SEMVER.matcher(a);
        Matcher y = SEMVER.matcher(b);
        if (!x.matches() || !y.matches()) {
            return a.compareTo(b);
        }
        for (int group = 1; group <= 3; group++) {
            int c = compareNumeric(x.group(group), y.group(group));
            if (c != 0) {
                return c;
            }
        }
        String preA = x.group(4);
        String preB = y.group(4);
        if (preA == null || preB == null) {
            // A release outranks its pre-releases.
            return preA == null ? (preB == null ? 0 : 1) : -1;
        }
        String[] idsA = preA.split("\\.");
        String[] idsB = preB.split("\\.");
        for (int i = 0; i < Math.min(idsA.length, idsB.length); i++) {
            boolean numA = idsA[i].chars().allMatch(Character::isDigit);
            boolean numB = idsB[i].chars().allMatch(Character::isDigit);
            int c = numA && numB ? compareNumeric(idsA[i], idsB[i])
                    : numA ? -1 : numB ? 1 : idsA[i].compareTo(idsB[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(idsA.length, idsB.length);
    }

    private static int compareNumeric(String a, String b) {
        String x = a.replaceFirst("^0+(?=.)", "");
        String y = b.replaceFirst("^0+(?=.)", "");
        return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
    }
}
