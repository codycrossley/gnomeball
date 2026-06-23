package gay.runescape.gnomeball;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class EventPoller
{
    public interface Listener
    {
        void onEvent(ApiClient.EventOut e);
        void onError(Exception e);
    }

    private final ApiClient api;
    private final Listener listener;

    private final ScheduledExecutorService exec =
        Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "gnomeball-poller");
            t.setDaemon(true);
            return t;
        });

    private volatile String gameId;
    private final AtomicInteger lastSeq = new AtomicInteger(0);
    private volatile boolean running = false;
    private volatile ScheduledFuture<?> scheduled;

    public EventPoller(ApiClient api, Listener listener)
    {
        this.api = api;
        this.listener = listener;
    }

    public synchronized void start(String gameId)
    {
        start(gameId, 0);
    }

    public synchronized void start(String gameId, int initialSeq)
    {
        stop();
        this.gameId = gameId;
        this.lastSeq.set(initialSeq);
        this.running = true;
        scheduled = exec.scheduleAtFixedRate(this::tick, 0, 500, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop()
    {
        running = false;
        if (scheduled != null)
        {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    public synchronized boolean isRunning()
    {
        return running;
    }

    public synchronized void shutdown()
    {
        stop();
        exec.shutdownNow();
    }

    private void tick()
    {
        if (!running) return;

        final String gid = this.gameId;
        if (gid == null || gid.isBlank()) return;

        try
        {
            int after = lastSeq.get();
            ApiClient.ReadEventsResponse resp = api.readEvents(gid, after);

            for (ApiClient.EventOut e : resp.events)
            {
                lastSeq.set(Math.max(lastSeq.get(), e.seq));
                listener.onEvent(e);
            }
        }
        catch (Exception e)
        {
            listener.onError(e);
        }
    }
}