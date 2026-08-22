package com.mircowuffwuff.sharpdroid;

/**
 * orders two {@code sharpemuVersion} strings.
 *
 * <p><b>a file of its own, with nothing Android in it.</b> this rule decides which build a list puts
 * first and which one a badge calls outdated, and getting it wrong is silent -- a plausible order with
 * one build in the wrong place. kept apart from {@link SharpEmuBuild}, it compiles and runs on a
 * plain JVM, so the ordering can be checked against real version strings without a device.
 *
 * <p><b>the rule is SharpEmu's release order, and it is not semver.</b> semver says a suffixed
 * version precedes the bare one -- {@code 1.0.0-beta} then {@code 1.0.0}. SharpEmu's releases run the
 * other way: the bare version comes first and suffixed ones follow it.
 *
 * <pre>
 *   0.0.1
 *   0.0.2
 *   0.0.2-beta.2   0.0.2-beta.3   0.0.2-beta.4   0.0.2-beta.5
 *   0.0.3
 *   0.0.3-hotfix-1   0.0.3-hotfix-2
 *   0.0.3-release.2
 * </pre>
 *
 * <p>so this file exists partly to say that out loud: anybody who reads the comparator against semver
 * will think it is inverted and will be tempted to "fix" it, and the fix would reorder every build
 * list in the app.
 *
 * <p><b>suffix labels order alphabetically rather than by a table of known words.</b> a table would
 * have to answer what an unknown label does, and a third-party build may carry any label at all -- so
 * the rule that applies to {@code hotfix} before {@code release} is the rule that applies to
 * everything, and it is total. it happens to agree with the words themselves: alpha, beta, rc.
 *
 * <p>third-party builds are why this is a comparator rather than an assumption about our own version
 * strings, and why it never throws: any two strings order, deterministically.
 */
final class Versions {

    private Versions() {
    }

    /** negative, zero or positive, as {@code a} sorts before, with, or after {@code b}. */
    static int compare(String a, String b) {
        String coreA = core(a);
        String coreB = core(b);

        int byNumbers = compareDotted(coreA, coreB);
        if (byNumbers != 0) {
            return byNumbers;
        }

        String suffixA = a.substring(coreA.length());
        String suffixB = b.substring(coreB.length());

        // **the bare version comes first**, which is where this departs from semver. see above.
        if (suffixA.isEmpty() != suffixB.isEmpty()) {
            return suffixA.isEmpty() ? -1 : 1;
        }
        if (suffixA.isEmpty()) {
            return 0;
        }

        int byLabel = label(suffixA).compareTo(label(suffixB));
        if (byLabel != 0) {
            return byLabel;
        }

        // the numbers within the label, so hotfix-10 follows hotfix-2 rather than preceding it.
        int byOrdinal = compareNumbers(numbers(suffixA), numbers(suffixB));
        if (byOrdinal != 0) {
            return byOrdinal;
        }

        // two strings this rule cannot separate still have to order, or a sort is unstable and a
        // list reorders itself between two refreshes of the same directory.
        return a.compareTo(b);
    }

    /** the leading dotted numbers: {@code 0.0.3} out of {@code 0.0.3-hotfix-2}. */
    private static String core(String version) {
        int i = 0;
        while (i < version.length()
                && (Character.isDigit(version.charAt(i)) || version.charAt(i) == '.')) {
            i++;
        }
        // a trailing dot belongs to whatever follows it, not to the numbers.
        while (i > 0 && version.charAt(i - 1) == '.') {
            i--;
        }
        return version.substring(0, i);
    }

    /**
     * compares dotted numbers numerically, never lexically.
     *
     * <p>lexically, {@code 0.0.10} precedes {@code 0.0.9}. a missing component is zero, so
     * {@code 1.0} and {@code 1.0.0} are one version.
     */
    private static int compareDotted(String a, String b) {
        String[] partsA = a.isEmpty() ? new String[0] : a.split("\\.");
        String[] partsB = b.isEmpty() ? new String[0] : b.split("\\.");
        for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
            long na = i < partsA.length ? parse(partsA[i]) : 0;
            long nb = i < partsB.length ? parse(partsB[i]) : 0;
            if (na != nb) {
                return na < nb ? -1 : 1;
            }
        }
        return 0;
    }

    /** the word in a suffix: {@code hotfix} out of {@code -hotfix-2}. lowercased, never null. */
    private static String label(String suffix) {
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (Character.isLetter(c)) {
                word.append(Character.toLowerCase(c));
            } else if (word.length() > 0) {
                break;
            }
        }
        return word.toString();
    }

    /** every run of digits in a suffix, in order. */
    private static long[] numbers(String suffix) {
        String[] runs = suffix.split("\\D+");
        int count = 0;
        for (String run : runs) {
            if (!run.isEmpty()) {
                count++;
            }
        }
        long[] found = new long[count];
        int at = 0;
        for (String run : runs) {
            if (!run.isEmpty()) {
                found[at++] = parse(run);
            }
        }
        return found;
    }

    private static int compareNumbers(long[] a, long[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long na = i < a.length ? a[i] : 0;
            long nb = i < b.length ? b[i] : 0;
            if (na != nb) {
                return na < nb ? -1 : 1;
            }
        }
        return 0;
    }

    /** a number, or zero for anything that is not one. version strings come out of a file. */
    private static long parse(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
