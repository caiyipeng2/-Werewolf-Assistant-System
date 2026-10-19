package com.langyu.assistant;

import java.util.Arrays;
import java.util.Collections;

/** Replays usage event delivery, including delayed publication and OEM activity ordering. */
public final class ForegroundTrackerTests {
    private static final String GAME = "com.netease.lrs.vivo";
    private static final long NOW = 200_000_000L;
    private static int checks;

    public static void main(String[] args) {
        bootstrapFindsAnAlreadyRunningGame();
        eventTimeCursorRetainsDelayedDelivery();
        lateEventsAreReplayedWithoutRegressingNewerForeground();
        duplicateAndUnorderedWindowsAreIdempotent();
        activityPauseOnlyClearsItsOwnActivity();
        unknownAndManualRecoveryUseABoundedHistory();
        recoveryCannotResurrectAnOldGameOverAnotherApplication();
        temporarySystemActivityDoesNotRestoreAnUnconfirmedGame();
        lockscreenAndScreenOffRejectTheSource();
        onlyTheExactSelectedPackageIsAccepted();
        oldHistoryCanExpireWithoutLosingObservedCurrentState();
        missingOrFutureEventsNeverAuthenticateTheSource();
        System.out.println("PASS: " + checks + " foreground tracking behavior assertions");
    }

    private static ForegroundTracker.Event event(long timestamp, int type, String pkg, String activity) {
        return new ForegroundTracker.Event(timestamp, type, pkg, activity);
    }

    private static ForegroundTracker.Event resumed(long timestamp, String pkg, String activity) {
        return event(timestamp, ForegroundTracker.RESUMED, pkg, activity);
    }

    private static ForegroundTracker.Event paused(long timestamp, String pkg, String activity) {
        return event(timestamp, ForegroundTracker.PAUSED, pkg, activity);
    }

    private static void apply(ForegroundTracker tracker, long now, ForegroundTracker.Event... events) {
        long latest = 0;
        for (ForegroundTracker.Event e : events) latest = Math.max(latest, e.timestamp);
        tracker.record(Arrays.asList(events), latest, now);
    }

    private static void check(boolean actual, String explanation) {
        checks++;
        if (!actual) throw new AssertionError(explanation);
    }

    private static void bootstrapFindsAnAlreadyRunningGame() {
        ForegroundTracker tracker = new ForegroundTracker();
        check(tracker.queryStart(NOW) == NOW - 86_400_000L,
                "bootstrap must reach the preceding 24 hours, not only the preceding minute");
        apply(tracker, NOW, resumed(NOW - 120_000, GAME, "GameActivity"));
        check(tracker.isForeground(GAME), "game resumed before monitor startup remains discoverable");
        check(!tracker.isForeground(""), "an empty selected source never authenticates");
    }

    private static void eventTimeCursorRetainsDelayedDelivery() {
        ForegroundTracker tracker = new ForegroundTracker();
        tracker.queryStart(NOW);
        apply(tracker, NOW, resumed(NOW - 20_000, "launcher", "Home"));
        check(tracker.queryStart(NOW + 1_000) <= NOW - 30_000,
                "cursor follows actual event time with ten seconds of overlap");
        apply(tracker, NOW + 1_000, resumed(NOW - 2_000, GAME, "GameActivity"));
        check(tracker.isForeground(GAME), "an event published more than 500 ms late is not skipped");
        tracker.record(Collections.emptyList(), NOW - 2_000, NOW + 30_000);
        check(tracker.queryStart(NOW + 30_001) <= NOW - 12_000,
                "an empty poll does not advance the event cursor to wall-clock now");
    }

    private static void lateEventsAreReplayedWithoutRegressingNewerForeground() {
        ForegroundTracker tracker = new ForegroundTracker();
        apply(tracker, NOW, resumed(NOW - 5_000, GAME, "GameActivity"));
        apply(tracker, NOW + 1_000, resumed(NOW - 8_000, "old.app", "OldActivity"));
        check(tracker.isForeground(GAME), "late older resume must not overwrite a newer game resume");
        apply(tracker, NOW + 2_000, paused(NOW - 6_000, GAME, "GameActivity"));
        check(tracker.isForeground(GAME), "older pause must not clear a newer resume of the same activity");
        apply(tracker, NOW + 3_000, resumed(NOW - 1_000, "other.app", "OtherActivity"));
        check(!tracker.isForeground(GAME), "a delayed newer non-game resume closes source access");
        apply(tracker, NOW + 4_000, resumed(NOW - 7_000, GAME, "GameActivity"));
        check(!tracker.isForeground(GAME), "another recovery of older game events does not restore access");
    }

    private static void duplicateAndUnorderedWindowsAreIdempotent() {
        ForegroundTracker tracker = new ForegroundTracker();
        ForegroundTracker.Event a = resumed(NOW - 3_000, GAME, "GameActivity");
        ForegroundTracker.Event b = resumed(NOW - 1_000, "other.app", "OtherActivity");
        apply(tracker, NOW, b, a, b, a);
        check(!tracker.isForeground(GAME), "unordered observations are applied chronologically");
        long version = tracker.sourceVersion();
        apply(tracker, NOW + 1_000, a, b, a);
        check(tracker.sourceVersion() == version, "overlapping duplicate windows do not invalidate a frame");
        ForegroundTracker sameTimestamp = new ForegroundTracker();
        apply(sameTimestamp, NOW, paused(NOW - 1, GAME, "GameActivity"), resumed(NOW - 1, GAME, "GameActivity"));
        check(!sameTimestamp.isForeground(GAME), "equal-time resume/pause ambiguity is rejected conservatively");
    }

    private static void activityPauseOnlyClearsItsOwnActivity() {
        ForegroundTracker tracker = new ForegroundTracker();
        apply(tracker, NOW,
                resumed(NOW - 4_000, GAME, "EntryActivity"),
                resumed(NOW - 3_000, GAME, "GameActivity"),
                paused(NOW - 2_000, GAME, "EntryActivity"));
        check(tracker.isForeground(GAME), "pausing old activity does not hide current activity in the same package");
        apply(tracker, NOW + 1_000, event(NOW - 1_000, ForegroundTracker.STOPPED, GAME, "EntryActivity"));
        check(tracker.isForeground(GAME), "stopping old activity does not hide current activity");
        apply(tracker, NOW + 2_000, paused(NOW + 500, GAME, "GameActivity"));
        check(!tracker.isForeground(GAME), "pausing the actual foreground activity closes access");
        apply(tracker, NOW + 3_000, resumed(NOW + 2_500, GAME, "GameActivity"));
        apply(tracker, NOW + 4_000, paused(NOW + 3_500, GAME, null));
        check(!tracker.isForeground(GAME), "missing activity on a source pause is treated conservatively");
    }

    private static void unknownAndManualRecoveryUseABoundedHistory() {
        ForegroundTracker tracker = new ForegroundTracker();
        check(tracker.queryStart(5_000) == 0, "bootstrap never queries negative time");
        apply(tracker, NOW, resumed(NOW - 5_000, GAME, "GameActivity"));
        check(tracker.queryStart(NOW + 1_000) == NOW - 15_000, "known foreground uses event cursor overlap");
        tracker.requestRecovery();
        check(tracker.queryStart(NOW + 2_000) == NOW + 2_000 - 86_400_000L,
                "manual scan can request a full bounded recovery even with a known foreground");
        apply(tracker, NOW + 3_000, paused(NOW + 2_500, GAME, "GameActivity"));
        check(tracker.queryStart(NOW + 20_000) == NOW + 20_000 - 86_400_000L,
                "unknown foreground periodically retries the bootstrap history");
        check(tracker.queryStart(NOW + 20_100) >= NOW - 8_000,
                "unknown foreground recovery is throttled between routine polls");
    }

    private static void recoveryCannotResurrectAnOldGameOverAnotherApplication() {
        ForegroundTracker tracker = new ForegroundTracker();
        ForegroundTracker.Event game = resumed(NOW - 30_000, GAME, "GameActivity");
        ForegroundTracker.Event other = resumed(NOW - 1_000, "other.app", "OtherActivity");
        apply(tracker, NOW, game, other);
        tracker.requestRecovery();
        tracker.queryStart(NOW + 1_000);
        apply(tracker, NOW + 1_000, game);
        check(!tracker.isForeground(GAME), "full recovery merges history and retains known newer non-game activity");
        apply(tracker, NOW + 2_000, paused(NOW + 500, "other.app", "OtherActivity"));
        apply(tracker, NOW + 3_000, game);
        check(!tracker.isForeground(GAME), "unknown foreground never falls back to an earlier game resume");
    }

    private static void temporarySystemActivityDoesNotRestoreAnUnconfirmedGame() {
        ForegroundTracker tracker = new ForegroundTracker();
        apply(tracker, NOW,
                resumed(NOW - 3_000, GAME, "GameActivity"),
                resumed(NOW - 2_000, "com.android.systemui", "DialogActivity"),
                paused(NOW - 1_000, "com.android.systemui", "DialogActivity"));
        check(!tracker.isForeground(GAME), "system dialog does not authenticate the underlying game");
        apply(tracker, NOW + 1_000, resumed(NOW + 500, GAME, "GameActivity"));
        check(tracker.isForeground(GAME), "a new actual game resume can authenticate again");
    }

    private static void lockscreenAndScreenOffRejectTheSource() {
        ForegroundTracker tracker = new ForegroundTracker();
        apply(tracker, NOW, resumed(NOW - 3_000, GAME, "GameActivity"),
                event(NOW - 2_000, ForegroundTracker.KEYGUARD_SHOWN, null, null));
        check(!tracker.isForeground(GAME), "lockscreen blocks the selected source");
        apply(tracker, NOW + 1_000, event(NOW + 500, ForegroundTracker.SCREEN_OFF, null, null));
        apply(tracker, NOW + 2_000, event(NOW + 1_500, ForegroundTracker.KEYGUARD_HIDDEN, null, null));
        check(!tracker.isForeground(GAME), "an unlocked but non-interactive screen remains blocked");
        apply(tracker, NOW + 3_000, event(NOW + 2_500, ForegroundTracker.SCREEN_ON, null, null));
        check(tracker.isForeground(GAME), "visible unlocked source may resume without changing its activity");
        apply(tracker, NOW + 4_000, event(NOW + 3_500, ForegroundTracker.SHUTDOWN, null, null));
        check(!tracker.isForeground(GAME), "shutdown invalidates an old source");
    }

    private static void onlyTheExactSelectedPackageIsAccepted() {
        ForegroundTracker tracker = new ForegroundTracker();
        apply(tracker, NOW, resumed(NOW - 1, GAME, "GameActivity"));
        check(!tracker.isForeground("com.netease.werewolf"), "channel package does not silently match a different configured source");
        check(!tracker.isForeground("com.netease.lrs"), "package prefix is not a source match");
        check(tracker.isForeground(GAME), "exact selected package remains accepted");
    }

    private static void oldHistoryCanExpireWithoutLosingObservedCurrentState() {
        ForegroundTracker tracker = new ForegroundTracker();
        apply(tracker, NOW, resumed(NOW - 1_000, GAME, "GameActivity"));
        long nextDay = NOW + 86_400_001L;
        apply(tracker, nextDay);
        check(tracker.isForeground(GAME), "retiring old event storage preserves the last observed current state");
        check(tracker.queryStart(nextDay) >= nextDay - 86_400_000L,
                "event-time cursor is always capped at the rolling 24-hour history bound");
        apply(tracker, nextDay + 1_000, paused(nextDay + 500, GAME, "GameActivity"));
        check(!tracker.isForeground(GAME), "new pause still invalidates a source whose resume was retired");
    }

    private static void missingOrFutureEventsNeverAuthenticateTheSource() {
        ForegroundTracker tracker = new ForegroundTracker();
        tracker.record(Collections.emptyList(), 0, NOW);
        check(!tracker.isForeground(GAME), "empty usage history does not assume the selected game is visible");
        apply(tracker, NOW, resumed(NOW + 1_000, GAME, "GameActivity"));
        check(!tracker.isForeground(GAME), "future event timestamps cannot authenticate the current screen");
        apply(tracker, NOW, resumed(NOW - 86_400_001L, GAME, "GameActivity"));
        check(!tracker.isForeground(GAME), "unknown source cannot be bootstrapped from history outside the bound");
        apply(tracker, NOW, resumed(NOW - 1, null, "GameActivity"));
        check(!tracker.isForeground(GAME), "resume without a package cannot authenticate a source");
    }
}
