package org.robolectric.shadows;

import android.app.KeyguardManager;
import org.robolectric.Robolectric;
import org.robolectric.internal.Implementation;
import org.robolectric.internal.Implements;
import org.robolectric.internal.RealObject;

/**
 * Shadows the {@code android.app.KeyguardManager} class.
 */
@Implements(KeyguardManager.class)
public class ShadowKeyguardManager {
    @RealObject private KeyguardManager realKeyguardManager;

    private KeyguardManager.KeyguardLock keyguardLock = Robolectric.newInstanceOf(KeyguardManager.KeyguardLock.class);

    private boolean inRestrictedInputMode = false;

    @Implementation
    public boolean inKeyguardRestrictedInputMode() {
        return inRestrictedInputMode;
    }

    @Implementation
    public KeyguardManager.KeyguardLock newKeyguardLock(String tag) {
        return keyguardLock;
    }

    public void setinRestrictedInputMode(boolean restricted) {
        inRestrictedInputMode = restricted;
    }
}
