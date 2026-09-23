package gay.runescape.gnomeball;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Queues "team just scored" notifications so two that land close together (e.g. a
 * reconciliation poll catching up after both teams scored in quick succession) each get their
 * own turn instead of the second one silently overwriting the first before it's ever rendered.
 * The notification itself (see TimerOverlay#renderGoalFlash) is a full-canvas banner -- there's
 * no room to show two at once, so "queue and play in order" rather than "show both" is the fix.
 *
 * Deliberately has no RuneLite dependency (unlike the rest of this plugin) so it can be unit
 * tested directly -- GnomeballPlugin owns the single instance, and callers pass in the current
 * time rather than this class reading the clock itself, so tests can drive it deterministically. */
public class GoalFlashQueue
{
    public static final long DEFAULT_DURATION_MS = 3000;

    public static final class Flash
    {
        public final String team;
        public final int oldScore;
        public final int newScore;
        private long until;

        private Flash(String team, int oldScore, int newScore)
        {
            this.team = team;
            this.oldScore = oldScore;
            this.newScore = newScore;
        }

        public long until()
        {
            return until;
        }

        private boolean matches(String team, int oldScore, int newScore)
        {
            return Objects.equals(this.team, team) && this.oldScore == oldScore && this.newScore == newScore;
        }
    }

    private final long durationMs;
    private final Deque<Flash> pending = new ArrayDeque<>();
    private Flash current;

    public GoalFlashQueue()
    {
        this(DEFAULT_DURATION_MS);
    }

    public GoalFlashQueue(long durationMs)
    {
        this.durationMs = durationMs;
    }

    /** Arms a new flash, queuing it behind whatever's currently showing (if that hasn't expired
     * yet as of {@code nowMs}) rather than replacing it outright. Safe to call from any thread.
     *
     * A flash identical to the one showing or already queued (same team, same old/new score) is
     * dropped: that's the same goal reported twice -- the scorer's own optimistic preview
     * (GnomeballPlugin#onZoneScore) followed by the server's GOAL_SCORED echo, which carry the
     * exact same scores since the preview never bumps the local score. Two genuinely separate
     * goals can't collide here, since each real goal advances the score. */
    public synchronized void enqueue(String team, int oldScore, int newScore, long nowMs)
    {
        if (current != null && nowMs < current.until && current.matches(team, oldScore, newScore)) return;
        for (Flash queued : pending)
        {
            if (queued.matches(team, oldScore, newScore)) return;
        }

        Flash flash = new Flash(team, oldScore, newScore);
        if (current == null || nowMs >= current.until)
        {
            flash.until = nowMs + durationMs;
            current = flash;
        }
        else
        {
            pending.addLast(flash);
        }
    }

    /** Returns whatever flash should be showing right now, advancing to the next queued one if
     * the current one's duration has elapsed as of {@code nowMs}. Call this on every read (an
     * overlay polling once per frame, say) rather than caching the result -- it's what actually
     * drives the queue forward from one flash to the next. */
    public synchronized Flash current(long nowMs)
    {
        if (current != null && nowMs >= current.until)
        {
            Flash next = pending.pollFirst();
            if (next != null) next.until = nowMs + durationMs;
            current = next;
        }
        return current;
    }

    public synchronized void clear()
    {
        pending.clear();
        current = null;
    }

    public synchronized int pendingCount()
    {
        return pending.size();
    }
}
