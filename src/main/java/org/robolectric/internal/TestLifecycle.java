package org.robolectric.internal;

import org.robolectric.RobolectricContext;

import java.lang.reflect.Method;

public interface TestLifecycle<T> {
    void init(RobolectricContext robolectricContext);

    T createApplication(Method method);

    void prepareTest(Object test);

    void beforeTest(Method method);

    void afterTest(Method method);
}