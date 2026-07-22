package io.github.fairyxh.volumelimiter;

import android.app.Application;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;


import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class VolumeLimiterApp extends Application implements XposedServiceHelper.OnServiceListener {
    public interface ServiceListener {
        void onServiceChanged(XposedService service);
    }

    private static final Set<ServiceListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    public static void addListener(ServiceListener listener) {
        LISTENERS.add(listener);
        listener.onServiceChanged(service);
    }

    public static void removeListener(ServiceListener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        notifyListeners();
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
            notifyListeners();
        }
    }

    private static void notifyListeners() {
        for (ServiceListener listener : LISTENERS) {
            listener.onServiceChanged(service);
        }
    }
}
