package gay.runescape.gnomeball;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket-backed replacement for the 500ms {@code EventPoller} it replaces.
 * Satisfies the same start/stop/shutdown/isRunning shape so callers don't
 * need to change how they use it -- only how events arrive changes.
 *
 * Reconnects with full-jitter exponential backoff (delay = random(0,
 * min(30s, 500ms * 2^attempt))): full jitter specifically because a server
 * restart drops every connected client's socket at once, and plain
 * exponential backoff would produce a synchronized reconnect wave from
 * every client at the same moment.
 */
@Slf4j
public class EventSocket
{
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final long BASE_BACKOFF_MS = 500;

    private final OkHttpClient http;
    private final Gson gson;
    private final EventListener listener;
    private final String wsBaseUrl;

    private final ScheduledExecutorService reconnectExec =
        Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "gnomeball-ws-reconnect");
            t.setDaemon(true);
            return t;
        });

    private volatile String gameId;
    private volatile String playerRsn;
    private final AtomicInteger lastSeq = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private volatile boolean running = false;
    private WebSocket webSocket;
    private ScheduledFuture<?> reconnectTask;

    public EventSocket(OkHttpClient okHttpClient, Gson gson, EventListener listener)
    {
        // Derived from ApiClient.BASE_URL rather than a second hardcoded
        // constant, so flipping the commented-out localhost BASE_URL for
        // local dev can't leave this pointed at production by mistake.
        this(okHttpClient, gson, listener, ApiClient.BASE_URL.replaceFirst("^http", "ws"));
    }

    /** Visible for testing -- lets tests point this at a MockWebServer instead of production. */
    EventSocket(OkHttpClient okHttpClient, Gson gson, EventListener listener, String wsBaseUrl)
    {
        // Pings let OkHttp detect a silently-dead connection (NAT timeout,
        // network partition) faster than plain TCP would on its own.
        this.http = okHttpClient.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
        this.gson = gson;
        this.listener = listener;
        this.wsBaseUrl = wsBaseUrl;
    }

    public void start(String gameId, String playerRsn)
    {
        start(gameId, 0, playerRsn);
    }

    public synchronized void start(String gameId, int initialSeq, String playerRsn)
    {
        stop();
        this.gameId = gameId;
        this.playerRsn = playerRsn;
        this.lastSeq.set(initialSeq);
        this.reconnectAttempt.set(0);
        this.running = true;
        connect();
    }

    public synchronized void stop()
    {
        running = false;
        if (reconnectTask != null)
        {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        if (webSocket != null)
        {
            webSocket.close(1000, "client stopping");
            webSocket = null;
        }
    }

    public synchronized boolean isRunning()
    {
        return running;
    }

    public synchronized void shutdown()
    {
        stop();
        reconnectExec.shutdownNow();
    }

    private synchronized void connect()
    {
        if (!running) return;

        final String gid = this.gameId;
        if (gid == null || gid.isBlank()) return;

        String url = wsUrl(gid, lastSeq.get(), playerRsn);
        Request req = new Request.Builder().url(url).build();
        webSocket = http.newWebSocket(req, new Listener());
    }

    private String wsUrl(String gameId, int afterSeq, String playerRsn)
    {
        StringBuilder sb = new StringBuilder(wsBaseUrl)
            .append("/v1/games/").append(gameId).append("/ws")
            .append("?afterSeq=").append(afterSeq);
        if (playerRsn != null && !playerRsn.isBlank())
        {
            sb.append("&player=").append(urlEncode(playerRsn));
        }
        return sb.toString();
    }

    private static String urlEncode(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void scheduleReconnect()
    {
        synchronized (this)
        {
            if (!running) return;

            int attempt = reconnectAttempt.getAndIncrement();
            long cap = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(attempt, 20)));
            long delay = ThreadLocalRandom.current().nextLong(0, cap + 1);
            log.debug("Scheduling WS reconnect attempt {} in {}ms", attempt + 1, delay);

            reconnectTask = reconnectExec.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }
    }

    private final class Listener extends WebSocketListener
    {
        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            reconnectAttempt.set(0);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            if (!running) return;
            try
            {
                ApiClient.EventOut e = gson.fromJson(text, ApiClient.EventOut.class);
                if (e == null) return;
                lastSeq.set(Math.max(lastSeq.get(), e.seq));
                listener.onEvent(e);
            }
            catch (Exception ex)
            {
                listener.onError(ex);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason)
        {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            if (running) scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response)
        {
            listener.onError(t instanceof Exception ? (Exception) t : new RuntimeException(t));
            if (running) scheduleReconnect();
        }
    }
}
