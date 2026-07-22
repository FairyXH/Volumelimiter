package io.github.fairyxh.volumelimiter.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AudioFocusTrackerTest {
    @Test
    public void recognizesAndroidPackageNames() {
        assertTrue(AudioFocusTracker.isPackageName("com.example.music"));
        assertTrue(AudioFocusTracker.isPackageName("com.tencent.mobileqq"));
    }

    @Test
    public void rejectsFocusClientIdsAndNonPackages() {
        assertFalse(AudioFocusTracker.isPackageName("AudioFocus_For_Phone_Ring_And_Calls"));
        assertFalse(AudioFocusTracker.isPackageName("android.media.AudioManager@1ab23"));
        assertFalse(AudioFocusTracker.isPackageName("music"));
        assertFalse(AudioFocusTracker.isPackageName("com.example:service"));
    }

    @Test
    public void extractsPlainClientIdBeforeCallingPackage() {
        Object[] args = {new Object(), 1, new Object(), new Object(),
                "player-session-42", "com.example.music", null, 0};

        assertEquals("player-session-42", AudioFocusTracker.findClientIdForTest(args));
    }

    @Test
    public void releaseByClientIdRestoresPreviousOwner() {
        AudioFocusTracker tracker = new AudioFocusTracker();
        tracker.trackOwner("com.player.first", "client-first", null);
        tracker.trackOwner("com.player.second", "client-second", null);

        tracker.releaseMatchingArgs(new Object[]{"client-second"}, false);

        assertEquals("com.player.first", tracker.resolve());
    }

    @Test
    public void releaseByPackageRemovesKilledAppOwner() {
        AudioFocusTracker tracker = new AudioFocusTracker();
        tracker.trackOwner("com.example.music", "client-music", null);

        tracker.releaseMatchingArgs(new Object[]{"com.example.music"}, false);

        assertNull(tracker.resolve());
    }

    @Test
    public void discardWithoutIdentifiersRemovesTopOwner() {
        AudioFocusTracker tracker = new AudioFocusTracker();
        tracker.trackOwner("com.example.music", "client-music", null);

        tracker.releaseMatchingArgs(new Object[0], true);

        assertNull(tracker.resolve());
    }
}
