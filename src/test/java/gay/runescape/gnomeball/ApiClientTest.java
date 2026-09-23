package gay.runescape.gnomeball;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Verifies ApiClient builds the requests it claims to -- correct path, JSON body and auth
 * header -- against a MockWebServer instead of a real backend. Covers kickPlayer (the new
 * referee kick/remove tool, added to fix players getting stuck on the roster after a mid-game
 * disconnect) plus a couple of the pre-existing methods it was modeled on, as a regression check
 * that giving ApiClient a per-instance base URL (see its 3-arg constructor) didn't change
 * anything about how requests are actually built. */
public class ApiClientTest
{
    private MockWebServer server;
    private ApiClient apiClient;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        apiClient = new ApiClient(new OkHttpClient(), new Gson(), server.url("/gnomeball").toString());
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private static JsonObject bodyOf(RecordedRequest req)
    {
        // gson 2.8.5 (this project's pinned version) predates the static JsonParser.parseString
        // helper -- that needs 2.8.9+ -- so this uses the older instance-method form instead.
        return new JsonParser().parse(req.getBody().readUtf8()).getAsJsonObject();
    }

    @Test
    public void kickPlayerPostsToTheRightPathWithPlayerAndTarget() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        apiClient.kickPlayer("game1", "RefRsn", "RogueRsn", "reftoken123");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("/gnomeball/v1/games/game1/kick-player", req.getPath());
        assertEquals("Bearer reftoken123", req.getHeader("Authorization"));

        JsonObject body = bodyOf(req);
        assertEquals("RefRsn", body.get("player").getAsString());
        assertEquals("RogueRsn", body.get("target").getAsString());
    }

    @Test(expected = IOException.class)
    public void kickPlayerThrowsOnNonSuccessResponse() throws Exception
    {
        // e.g. the server's require_referee check rejecting a non-referee kicker.
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"detail\":\"Player is not a referee\"}"));

        apiClient.kickPlayer("game1", "NotARef", "SomePlayer", "sometoken");
    }

    @Test
    public void tagPlayerPostsTaggerAndTarget() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        apiClient.tagPlayer("game1", "Tagger", "Target", "targettoken");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/gnomeball/v1/games/game1/tag-player", req.getPath());
        JsonObject body = bodyOf(req);
        assertEquals("Tagger", body.get("player").getAsString());
        assertEquals("Target", body.get("target").getAsString());
    }

    @Test
    public void createGameParsesTheFullResponse() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(
            "{\"gameId\":\"g1\",\"joinCode\":\"ABC123\",\"writeKey\":\"wk\",\"playerToken\":\"pt\"}"));

        ApiClient.CreateGameResult result = apiClient.createGame("HostRsn");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/gnomeball/v1/games", req.getPath());
        assertEquals("HostRsn", bodyOf(req).get("host").getAsString());

        assertEquals("g1", result.gameId);
        assertEquals("ABC123", result.joinCode);
        assertEquals("wk", result.writeKey);
        assertEquals("pt", result.playerToken);
    }

    @Test
    public void setTeamColorPostsPlayerTeamAndColor() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        apiClient.setTeamColor("game1", "RefRsn", "TEAM_A", "FF00FF", "reftoken123");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/gnomeball/v1/games/game1/set-team-color", req.getPath());
        assertEquals("Bearer reftoken123", req.getHeader("Authorization"));

        JsonObject body = bodyOf(req);
        assertEquals("RefRsn", body.get("player").getAsString());
        assertEquals("TEAM_A", body.get("team").getAsString());
        assertEquals("FF00FF", body.get("color").getAsString());
    }

    @Test(expected = IOException.class)
    public void setTeamColorThrowsOnNonSuccessResponse() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"detail\":\"Player is not a referee\"}"));

        apiClient.setTeamColor("game1", "NotARef", "TEAM_A", "FF00FF", "sometoken");
    }

    @Test
    public void changeNumberPostsPlayerAndNumber() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        apiClient.changeNumber("game1", "PlayerRsn", 42, "playertoken");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/gnomeball/v1/games/game1/change-number", req.getPath());
        assertEquals("Bearer playertoken", req.getHeader("Authorization"));

        JsonObject body = bodyOf(req);
        assertEquals("PlayerRsn", body.get("player").getAsString());
        assertEquals(42, body.get("number").getAsInt());
    }

    /** changeNumber is the one ApiClient method whose failure message a caller shows straight to
     * the player in chat (see GnomeballPlugin#onChangeNumberClicked) -- this pins down that the
     * IOException's message is the server's clean "detail" text, not a raw JSON body dump. */
    @Test
    public void changeNumberFailureMessageIsTheCleanServerDetailNotRawJson()
    {
        server.enqueue(new MockResponse().setResponseCode(409)
            .setBody("{\"detail\":\"Number 7 is already taken by SomeOtherPlayer\"}"));

        try
        {
            apiClient.changeNumber("game1", "PlayerRsn", 7, "playertoken");
            fail("expected an IOException");
        }
        catch (IOException e)
        {
            assertEquals("Number 7 is already taken by SomeOtherPlayer", e.getMessage());
        }
    }

    @Test
    public void changeNumberFailureFallsBackToAGenericMessageForAnUnshapedErrorBody()
    {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("not json"));

        try
        {
            apiClient.changeNumber("game1", "PlayerRsn", 7, "playertoken");
            fail("expected an IOException");
        }
        catch (IOException e)
        {
            assertEquals("Request failed (500)", e.getMessage());
        }
    }
}
