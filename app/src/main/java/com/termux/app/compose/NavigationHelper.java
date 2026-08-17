package com.termux.app.compose;

import androidx.navigationevent.NavigationEventDispatcher;
import androidx.navigationevent.NavigationEventDispatcherOwner;

import java.lang.reflect.Constructor;

/**
 * Java helper to create NavigationEventDispatcher instances.
 * Uses reflection to avoid Kotlin binary compatibility issues with synthetic constructors.
 */
public final class NavigationHelper {

    private NavigationHelper() {}

    /** Create a default NavigationEventDispatcher via reflection. */
    public static NavigationEventDispatcher createDispatcher() {
        try {
            // Try no-arg constructor first
            Constructor<NavigationEventDispatcher> ctor =
                    NavigationEventDispatcher.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e1) {
            try {
                // Try (Function0, Function1) - OnBackCompletedFallback, OnForwardCompletedFallback
                Class<?> f0 = Class.forName("kotlin.jvm.functions.Function0");
                Class<?> f1 = Class.forName("kotlin.jvm.functions.Function1");
                Constructor<NavigationEventDispatcher> ctor =
                        NavigationEventDispatcher.class.getDeclaredConstructor(f0, f1);
                ctor.setAccessible(true);
                return ctor.newInstance(null, null);
            } catch (Exception e2) {
                try {
                    // Try (NavigationEventDispatcher, Function0, Function1)
                    Class<?> f0 = Class.forName("kotlin.jvm.functions.Function0");
                    Class<?> f1 = Class.forName("kotlin.jvm.functions.Function1");
                    Constructor<NavigationEventDispatcher> ctor =
                            NavigationEventDispatcher.class.getDeclaredConstructor(
                                    NavigationEventDispatcher.class, f0, f1);
                    ctor.setAccessible(true);
                    return ctor.newInstance(null, null, null);
                } catch (Exception e3) {
                    throw new RuntimeException(
                            "Cannot create NavigationEventDispatcher: all constructor attempts failed", e3);
                }
            }
        }
    }

    /** Create a simple NavigationEventDispatcherOwner wrapping the given dispatcher. */
    public static NavigationEventDispatcherOwner createOwner(NavigationEventDispatcher dispatcher) {
        return new NavigationEventDispatcherOwner() {
            @Override
            public NavigationEventDispatcher getNavigationEventDispatcher() {
                return dispatcher;
            }
        };
    }
}
