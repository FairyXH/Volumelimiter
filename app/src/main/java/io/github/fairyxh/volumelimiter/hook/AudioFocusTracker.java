package io.github.fairyxh.volumelimiter.hook;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import io.github.fairyxh.volumelimiter.utils.LogUtils;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Maintains a small in-memory approximation of MediaFocusControl's owner stack. */
public final class AudioFocusTracker {
    public interface OwnerChangeListener {
        void onOwnerChanged();
    }
    private static final int MAX_OWNERS = 16;
    private static final Set<String> RELEASE_METHODS = Set.of(
            "abandonAudioFocus", "unregisterAudioFocusClient", "removeFocusStackEntry",
            "removeFocusStackEntryOnDeath", "discardAudioFocusOwner");

    private static final class Owner {
        final String packageName;
        final String clientId;
        final Object token;

        Owner(String packageName, String clientId, Object token) {
            this.packageName = packageName;
            this.clientId = clientId;
            this.token = token;
        }
    }

    private final Deque<Owner> owners = new ArrayDeque<>();
    private volatile OwnerChangeListener ownerChangeListener;

    public void setOwnerChangeListener(OwnerChangeListener listener) {
        ownerChangeListener = listener;
    }

    public int install(XposedModule module, ClassLoader classLoader) {
        Class<?> focusClass;
        try {
            focusClass = Class.forName("com.android.server.audio.MediaFocusControl", false, classLoader);
        } catch (Throwable error) {
            LogUtils.debug("MediaFocusControl unavailable: " + error);
            return 0;
        }
        int installed = 0;
        Set<String> signatures = new HashSet<>();
        for (Class<?> current = focusClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String name = method.getName();
                if ((!name.equals("requestAudioFocus") && !RELEASE_METHODS.contains(name))
                        || Modifier.isAbstract(method.getModifiers()) || method.isSynthetic()
                        || !signatures.add(method.toGenericString())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    boolean request = name.equals("requestAudioFocus");
                    module.hook(method)
                            .setPriority(XposedInterface.PRIORITY_LOWEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object[] args = chain.getArgs().toArray();
                                if (!request) {
                                    try {
                                        updateRelease(name, args);
                                    } catch (Throwable error) {
                                        LogUtils.debug("Audio focus release tracking failed: " + error);
                                    }
                                    return chain.proceed();
                                }
                                Object result = chain.proceed();
                                try {
                                    updateRequest(args, result);
                                } catch (Throwable error) {
                                    LogUtils.debug("Audio focus request tracking failed: " + error);
                                }
                                return result;
                            });
                    installed++;
                } catch (Throwable error) {
                    LogUtils.debug("Unable to hook audio focus method " + method + ": " + error);
                }
            }
        }
        LogUtils.debug("Installed " + installed + " audio focus tracking hooks");
        return installed;
    }

    public synchronized String resolve() {
        Owner owner = owners.peekFirst();
        return owner == null ? null : owner.packageName;
    }

    private void updateRequest(Object[] args, Object result) {
        String packageName = findPackageName(args);
        String clientId = findClientId(args, packageName);
        Object token = findToken(args);
        if (packageName == null) {
            return;
        }
        if (result instanceof Integer && ((Integer) result) <= 0) {
            return;
        }
        trackOwner(packageName, clientId, token);
        LogUtils.debug("AudioFocus owner=" + packageName + " client=" + clientId);
        notifyOwnerChanged();
    }

    private void updateRelease(String methodName, Object[] args) {
        releaseMatchingArgs(args, methodName.equals("discardAudioFocusOwner"));
        LogUtils.debug("AudioFocus release via=" + methodName);
        notifyOwnerChanged();
    }

    private void notifyOwnerChanged() {
        OwnerChangeListener listener = ownerChangeListener;
        if (listener != null) {
            try {
                listener.onOwnerChanged();
            } catch (Throwable error) {
                LogUtils.debug("Audio focus owner callback failed: " + error);
            }
        }
    }

    synchronized void trackOwner(String packageName, String clientId, Object token) {
        owners.removeIf(owner -> matches(owner, packageName, clientId, token));
        owners.addFirst(new Owner(packageName, clientId, token));
        while (owners.size() > MAX_OWNERS) {
            owners.removeLast();
        }
    }

    synchronized void releaseMatchingArgs(Object[] args, boolean discardTop) {
        boolean removed = owners.removeIf(owner -> matchesAny(owner, args));
        if (!removed && discardTop && !owners.isEmpty()) {
            owners.removeFirst();
        }
    }

    private static boolean matches(Owner owner, String packageName, String clientId,
                                   Object token) {
        return packageName != null && packageName.equals(owner.packageName)
                || clientId != null && clientId.equals(owner.clientId)
                || token != null && token == owner.token;
    }

    private static boolean matchesAny(Owner owner, Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String value
                    && (value.equals(owner.packageName) || value.equals(owner.clientId))) {
                return true;
            }
            if (arg instanceof IBinder && arg == owner.token) {
                return true;
            }
        }
        return false;
    }

    private static Object findToken(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof IBinder) {
                return arg;
            }
        }
        return null;
    }

    private static String findPackageName(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String value && isPackageName(value)) {
                return value;
            }
        }
        return null;
    }

    private static String findClientId(Object[] args, String packageName) {
        for (Object arg : args) {
            if (arg instanceof String value && !value.equals(packageName)
                    && (value.contains("@") || value.startsWith("AudioFocus_"))) {
                return value;
            }
        }
        for (int packageIndex = 0; packageIndex < args.length; packageIndex++) {
            if (!packageName.equals(args[packageIndex])) {
                continue;
            }
            for (int i = packageIndex - 1; i >= 0; i--) {
                if (args[i] instanceof String value && !value.isBlank()) {
                    return value;
                }
            }
            break;
        }
        return null;
    }

    static String findClientIdForTest(Object[] args) {
        return findClientId(args, findPackageName(args));
    }

    static boolean isPackageName(String value) {
        if (value == null || value.length() < 3 || value.length() > 255
                || value.contains("@") || value.contains(":")) {
            return false;
        }
        int dot = value.indexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(Character.isLetterOrDigit(character) || character == '_' || character == '.')) {
                return false;
            }
        }
        return true;
    }
}
