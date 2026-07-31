package gay.runescape.gnomeball;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class EventSocketTest
{
    private MockWebServer server;
    private OkHttpClient http;
    private final Gson gson = new Gson();
    private EventSocket eventSocket;

    // MockWebServer's own connection-handler thread for a WS upgrade blocks
    // until the SERVER side of that socket closes -- the client closing (or
    // even the whole client OkHttpClient shutting down) isn't sufficient, and
    // without this MockWebServer.shutdown() hangs and throws "Gave up
    // waiting for executor to shut down". Every server-side listener below
    // registers its WebSocket here so tearDown can close them all first.
    private final List<WebSocket> serverSockets = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient();
    }

    @After
    public void tearDown() throws Exception
    {
        if (eventSocket != null) eventSocket.shutdown();
        for (WebSocket ws : serverSockets)
        {
            ws.close(1000, "test teardown");
        }
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        server.shutdown();
    }

    private String wsBaseUrl()
    {
        return "ws://" + server.getHostName() + ":" + server.getPort();
    }

    private static String eventJson(int seq, String type)
    {
        return "{\"seq\":" + seq + ",\"eventId\":\"e" + seq + "\",\"ts\":\"2026-01-01T00:00:00Z\","
            + "\"type\":\"" + type + "\",\"payload\":{}}";
    }

    /** Messages arrive in order and reach listener.onEvent, correctly parsed. */
    @Test
    public void messagesArriveInOrderAndParsedCorrectly() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(3);
        List<ApiClient.EventOut> received = new CopyOnWriteArrayList<>();
        List<Exception> errors = new CopyOnWriteArrayList<>();

        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener()
        {
            @Override
            public void onOpen(WebSocket webSocket, Response response)
            {
                serverSockets.add(webSocket);
                webSocket.send(eventJson(1, "PLAYER_JOINED"));
                webSocket.send(eventJson(2, "ROLE_ASSIGNED"));
                webSocket.send(eventJson(3, "GAME_STARTED"));
            }
        }));

        eventSocket = new EventSocket(http, gson, new EventListener()
        {
            @Override
            public void onEvent(ApiClient.EventOut e)
            {
                received.add(e);
                latch.countDown();
            }

            @Override
            public void onError(Exception e) { errors.add(e); }
        }, wsBaseUrl());

        eventSocket.start("game1", 0, "TestRSN");

        assertTrue("expected 3 events within 5s", latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, errors.size());
        assertEquals(3, received.size());
        assertEquals(1, received.get(0).seq);
        assertEquals("PLAYER_JOINED", received.get(0).type);
        assertEquals(2, received.get(1).seq);
        assertEquals("ROLE_ASSIGNED", received.get(1).type);
        assertEquals(3, received.get(2).seq);
        assertEquals("GAME_STARTED", received.get(2).type);
    }

    /** A server-initiated close triggers a reconnect attempt against the mock server. */
    @Test
    public void serverInitiatedCloseTriggersReconnect() throws Exception
    {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener()
        {
            @Override
            public void onOpen(WebSocket webSocket, Response response)
            {
                serverSockets.add(webSocket);
                webSocket.close(1000, "server closing");
            }
        }));

        CountDownLatch secondConnectionOpened = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener()
        {
            @Override
            public void onOpen(WebSocket webSocket, Response response)
            {
                serverSockets.add(webSocket);
                secondConnectionOpened.countDown();
            }
        }));

        eventSocket = new EventSocket(http, gson, new EventListener()
        {
            @Override public void onEvent(ApiClient.EventOut e) { }
            @Override public void onError(Exception e) { }
        }, wsBaseUrl());

        eventSocket.start("game1", 0, "TestRSN");

        assertTrue("expected a reconnect within 5s of the server-initiated close",
            secondConnectionOpened.await(5, TimeUnit.SECONDS));
        assertEquals(2, server.getRequestCount());
    }

    /**
     * The reconnect request's afterSeq matches the last successfully-applied
     * lastSeq (from an event actually received), not the original initialSeq
     * passed to start() -- the detail most likely to regress silently.
     */
    @Test
    public void reconnectUsesLastAppliedSeqNotInitialSeq() throws Exception
    {
        CountDownLatch firstEventReceived = new CountDownLatch(1);
        CountDownLatch reconnected = new CountDownLatch(1);

        // First connection: sends one event (seq=5) then closes, forcing a
        // reconnect. Both responses are enqueued up front so MockWebServer's
        // FIFO queue dispatcher matches them in arrival order regardless of
        // exactly when the reconnect attempt happens.
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener()
        {
            @Override
            public void onOpen(WebSocket webSocket, Response response)
            {
                serverSockets.add(webSocket);
                webSocket.send(eventJson(5, "TILE_MARKED"));
                webSocket.close(1000, "closing after one event");
            }
        }));
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener()
        {
            @Override
            public void onOpen(WebSocket webSocket, Response response)
            {
                serverSockets.add(webSocket);
                reconnected.countDown();
            }
        }));

        eventSocket = new EventSocket(http, gson, new EventListener()
        {
            @Override
            public void onEvent(ApiClient.EventOut e) { firstEventReceived.countDown(); }

            @Override
            public void onError(Exception e) { }
        }, wsBaseUrl());

        // initialSeq=1, deliberately different from the seq=5 event the
        // server sends, so a reconnect using the stale initialSeq=1 instead
        // of the applied seq=5 is distinguishable in the recorded request.
        eventSocket.start("game1", 1, "TestRSN");

        assertTrue("expected the seq=5 event within 5s", firstEventReceived.await(5, TimeUnit.SECONDS));
        assertTrue("expected a reconnect within 5s", reconnected.await(5, TimeUnit.SECONDS));

        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertTrue("first connect should use the initial afterSeq=1: " + first.getPath(),
            first.getPath().contains("afterSeq=1"));

        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("expected a reconnect request", second);
        assertTrue("reconnect should use afterSeq=5 (last applied), not afterSeq=1 (initial): " + second.getPath(),
            second.getPath().contains("afterSeq=5"));
    }
}
