package io.github.fairyxh.volumelimiter.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolves app ownership without I/O on the volume adjustment path. */
public final class AudioAppResolver {
    public static final class Result {
        public final String directPackage;
        public final String focusPackage;
        public final String foregroundPackage;
        public final List<String> candidates;

        Result(String directPackage, String focusPackage, String foregroundPackage,
               List<String> candidates) {
            this.directPackage = directPackage;
            this.focusPackage = focusPackage;
            this.foregroundPackage = foregroundPackage;
            this.candidates = candidates;
        }
    }

    private final AudioFocusTracker focusTracker;
    private final ForegroundAppResolver foregroundResolver;

    public AudioAppResolver(AudioFocusTracker focusTracker, ForegroundAppResolver foregroundResolver) {
        this.focusTracker = focusTracker;
        this.foregroundResolver = foregroundResolver;
    }

    public Result resolve(Object[] hookArguments) {
        String direct = findDirectPackage(hookArguments);
        String focus = focusTracker.resolve();
        String foreground = foregroundResolver.resolve();
        List<String> candidates = new ArrayList<>(3);
        addUnique(candidates, direct);
        addUnique(candidates, focus);
        addUnique(candidates, foreground);
        return new Result(direct, focus, foreground,
                Collections.unmodifiableList(candidates));
    }

    private static String findDirectPackage(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String value && AudioFocusTracker.isPackageName(value)) {
                return value;
            }
        }
        return null;
    }

    private static void addUnique(List<String> values, String value) {
        if (value != null && !values.contains(value)) {
            values.add(value);
        }
    }
}