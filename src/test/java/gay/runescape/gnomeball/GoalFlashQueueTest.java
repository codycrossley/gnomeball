package gay.runescape.gnomeball;

import org.junit.Test;

import static org.junit.Assert.*;

/** Covers the "goal notification sometimes only displays for one team" bug -- two flashes armed
 * close together (e.g. a reconciliation poll catching up after both teams scored) used to share
 * a single set of fields, so the second one silently clobbered the first before it was ever
 * rendered. GoalFlashQueue fixes that by queuing instead of overwriting. */
public class GoalFlashQueueTest
{
    @Test
    public void firstEnqueueBecomesCurrentImmediately()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 0, 1, 1_000L);

        GoalFlashQueue.Flash flash = q.current(1_000L);
        assertNotNull(flash);
        assertEquals("TEAM_A", flash.team);
        assertEquals(0, flash.oldScore);
        assertEquals(1, flash.newScore);
        assertEquals(4_000L, flash.until());
    }

    @Test
    public void secondFlashWhileFirstStillShowingIsQueuedNotDropped()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 0, 1, 1_000L);
        q.enqueue("TEAM_B", 0, 1, 1_100L); // arrives 100ms later, well within TEAM_A's 3s window

        // TEAM_A's flash is still current -- TEAM_B's must not have overwritten it.
        GoalFlashQueue.Flash current = q.current(1_100L);
        assertEquals("TEAM_A", current.team);
        assertEquals(1, q.pendingCount());
    }

    @Test
    public void queuedFlashPlaysOnceTheCurrentOneExpires()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 0, 1, 1_000L);
        q.enqueue("TEAM_B", 0, 1, 1_100L);

        // Past TEAM_A's until (1000 + 3000 = 4000) -- querying now should advance the queue.
        GoalFlashQueue.Flash next = q.current(4_050L);
        assertNotNull("TEAM_B's flash must still play, not be lost", next);
        assertEquals("TEAM_B", next.team);
        assertEquals(0, q.pendingCount());
        // Freshly started, not backdated to when it was enqueued.
        assertEquals(4_050L + 3000, next.until());
    }

    @Test
    public void bothTeamsScoringInTheSameTickEachGetTheirOwnFlash()
    {
        // Mirrors GnomeballPlugin#syncGameState: both deltas detected in the same reconciliation
        // pass, enqueued back to back at the same instant.
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 3, 4, 5_000L);
        q.enqueue("TEAM_B", 2, 3, 5_000L);

        GoalFlashQueue.Flash first = q.current(5_000L);
        assertEquals("TEAM_A", first.team);

        GoalFlashQueue.Flash second = q.current(8_001L);
        assertEquals("TEAM_B", second.team);
        assertEquals(2, second.oldScore);
        assertEquals(3, second.newScore);
    }

    @Test
    public void noFlashOnceQueueIsExhausted()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 0, 1, 1_000L);

        assertNull(q.current(10_000L)); // long past the 4000 expiry, nothing queued behind it
    }

    @Test
    public void currentReturnsNullWhenNothingWasEverEnqueued()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        assertNull(q.current(0L));
    }

    @Test
    public void clearDropsBothCurrentAndQueued()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 0, 1, 1_000L);
        q.enqueue("TEAM_B", 0, 1, 1_000L);

        q.clear();

        assertNull(q.current(1_000L));
        assertEquals(0, q.pendingCount());
    }

    @Test
    public void thirdSimultaneousGoalAlsoSurvivesTheQueue()
    {
        GoalFlashQueue q = new GoalFlashQueue(3000);
        q.enqueue("TEAM_A", 0, 1, 0L);
        q.enqueue("TEAM_B", 0, 1, 0L);
        q.enqueue("TEAM_A", 1, 2, 0L);

        assertEquals("TEAM_A", q.current(0L).team);
        assertEquals(2, q.pendingCount());

        assertEquals("TEAM_B", q.current(3_001L).team);
        assertEquals(1, q.pendingCount());

        GoalFlashQueue.Flash third = q.current(6_002L);
        assertEquals("TEAM_A", third.team);
        assertEquals(1, third.oldScore);
        assertEquals(2, third.newScore);
        assertEquals(0, q.pendingCount());
    }
}
