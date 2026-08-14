package gay.runescape.gnomeball;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ApiClient
{
    static final String BASE_URL = "https://gnomeball.shrunk.studio/gnomeball";
    //static final String BASE_URL = "http://localhost:8002/gnomeball";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson;

    public ApiClient(OkHttpClient httpClient, Gson gson)
    {
        this.http = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
        this.gson = gson;
    }

    // -------------------------------------------------------------------------
    // Game lifecycle
    // -------------------------------------------------------------------------

    public CreateGameResult createGame(String hostRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("host", hostRsn);

        try (Response resp = post("/v1/games", body, null))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Create game failed (" + resp.code() + "): " + raw);
            CreateGameResponse parsed = gson.fromJson(raw, CreateGameResponse.class);
            return new CreateGameResult(parsed.gameId, parsed.joinCode, parsed.writeKey, parsed.playerToken);
        }
    }

    public JoinResult joinGame(String joinCode, String playerRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/join/" + joinCode, body, null))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Join failed (" + resp.code() + "): " + raw);
            JoinResponse parsed = gson.fromJson(raw, JoinResponse.class);
            return new JoinResult(parsed.gameId, parsed.host, parsed.playerToken);
        }
    }

    public void startGame(String gameId, String writeKey, int durationSeconds) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("durationSeconds", durationSeconds);

        try (Response resp = post("/v1/games/" + gameId + "/start", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Start game failed (" + resp.code() + "): " + raw);
        }
    }

    public void endGame(String gameId, String writeKey) throws IOException
    {
        try (Response resp = post("/v1/games/" + gameId + "/end", new JsonObject(), writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("End game failed (" + resp.code() + "): " + raw);
        }
    }

    public void leaveGame(String gameId, String playerRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/leave", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Leave failed (" + resp.code() + "): " + raw);
        }
    }

    public void passBall(String gameId, String fromRsn, String toRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", fromRsn);
        body.addProperty("target", toRsn);

        try (Response resp = post("/v1/games/" + gameId + "/pass-ball", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Pass ball failed (" + resp.code() + "): " + raw);
        }
    }

    public void tagPlayer(String gameId, String taggerRsn, String targetRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", taggerRsn);
        body.addProperty("target", targetRsn);

        try (Response resp = post("/v1/games/" + gameId + "/tag-player", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Tag player failed (" + resp.code() + "): " + raw);
        }
    }

    public void zoneGoal(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/zone-goal", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Zone goal failed (" + resp.code() + "): " + raw);
        }
    }

    public void outOfBounds(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/out-of-bounds", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Out of bounds failed (" + resp.code() + "): " + raw);
        }
    }

    public void clearBall(String gameId, String writeKey) throws IOException
    {
        try (Response resp = post("/v1/games/" + gameId + "/clear-ball", new JsonObject(), writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Clear ball failed (" + resp.code() + "): " + raw);
        }
    }

    public void assignBall(String gameId, String writeKey, String playerRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/assign-ball", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Assign ball failed (" + resp.code() + "): " + raw);
        }
    }

    public void assignRole(String gameId, String writeKey, String playerRsn, GnomeballRole role) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("role", role.name());

        try (Response resp = post("/v1/games/" + gameId + "/assign-role", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Assign role failed (" + resp.code() + "): " + raw);
        }
    }

    public void renameTeam(String gameId, String writeKey, String team, String name) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("team", team);
        body.addProperty("name", name);

        try (Response resp = post("/v1/games/" + gameId + "/rename-team", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Rename team failed (" + resp.code() + "): " + raw);
        }
    }

    public void updateScore(String gameId, String writeKey, String team, int score) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("team", team);
        body.addProperty("score", score);

        try (Response resp = post("/v1/games/" + gameId + "/update-score", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Update score failed (" + resp.code() + "): " + raw);
        }
    }

    public void broadcastMessage(String gameId, String playerRsn, String message, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("message", message);

        try (Response resp = post("/v1/games/" + gameId + "/broadcast", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Broadcast message failed (" + resp.code() + "): " + raw);
        }
    }

    public void markTile(String gameId, String writeKey, int x, int y, int plane, String tileType, String color) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);
        body.addProperty("tileType", tileType);
        if (color != null) body.addProperty("color", color);

        try (Response resp = post("/v1/games/" + gameId + "/mark-tile", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Mark tile failed (" + resp.code() + "): " + raw);
        }
    }

    public void unmarkTile(String gameId, String writeKey, int x, int y, int plane, String tileType) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);
        if (tileType != null) body.addProperty("tileType", tileType);

        try (Response resp = post("/v1/games/" + gameId + "/unmark-tile", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Unmark tile failed (" + resp.code() + "): " + raw);
        }
    }

    /** Batch counterpart to markTile/unmarkTile -- one request for a whole preset's worth of
     * tiles instead of one request per tile, so committing/removing a large preset doesn't mean
     * hundreds of sequential blocking round-trips (see commitPreset/removePreset). */
    public void markTiles(String gameId, String writeKey, List<TileSpec> tiles) throws IOException
    {
        JsonArray arr = new JsonArray();
        for (TileSpec t : tiles)
        {
            JsonObject tileObj = new JsonObject();
            tileObj.addProperty("x", t.x);
            tileObj.addProperty("y", t.y);
            tileObj.addProperty("plane", t.plane);
            tileObj.addProperty("tileType", t.tileType);
            if (t.color != null) tileObj.addProperty("color", t.color);
            if (t.orientation != null) tileObj.addProperty("orientation", t.orientation);
            arr.add(tileObj);
        }
        JsonObject body = new JsonObject();
        body.add("tiles", arr);

        try (Response resp = post("/v1/games/" + gameId + "/mark-tiles", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Mark tiles failed (" + resp.code() + "): " + raw);
        }
    }

    public void unmarkTiles(String gameId, String writeKey, List<PointSpec> points) throws IOException
    {
        JsonArray arr = new JsonArray();
        for (PointSpec p : points)
        {
            JsonObject pointObj = new JsonObject();
            pointObj.addProperty("x", p.x);
            pointObj.addProperty("y", p.y);
            pointObj.addProperty("plane", p.plane);
            if (p.tileType != null) pointObj.addProperty("tileType", p.tileType);
            arr.add(pointObj);
        }
        JsonObject body = new JsonObject();
        body.add("tiles", arr);

        try (Response resp = post("/v1/games/" + gameId + "/unmark-tiles", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Unmark tiles failed (" + resp.code() + "): " + raw);
        }
    }

    public TilesResponse fetchTiles(String gameId) throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/games/" + gameId + "/tiles")
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Fetch tiles failed (" + resp.code() + "): " + raw);
            TilesResponse parsed = gson.fromJson(raw, TilesResponse.class);
            if (parsed == null) throw new IOException("Empty response from tiles endpoint");
            if (parsed.tiles == null) parsed.tiles = Collections.emptyList();
            return parsed;
        }
    }

    public void blowWhistle(String gameId, String playerRsn, long remainingMs, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("remainingMs", remainingMs);

        try (Response resp = post("/v1/games/" + gameId + "/whistle", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Whistle failed (" + resp.code() + "): " + raw);
        }
    }

    public void pauseTimer(String gameId, String playerRsn, long remainingMs, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("remainingMs", remainingMs);

        try (Response resp = post("/v1/games/" + gameId + "/pause-timer", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Pause timer failed (" + resp.code() + "): " + raw);
        }
    }

    public void resumeTimer(String gameId, String playerRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/resume-timer", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Resume timer failed (" + resp.code() + "): " + raw);
        }
    }

    public void setTimer(String gameId, String playerRsn, long remainingMs, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("remainingMs", remainingMs);

        try (Response resp = post("/v1/games/" + gameId + "/set-timer", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Set timer failed (" + resp.code() + "): " + raw);
        }
    }

    public void sendHeartbeat(String gameId, String playerRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/heartbeat", body, null))
        {
            if (!resp.isSuccessful())
            {
                String raw = bodyString(resp);
                throw new IOException("Heartbeat failed (" + resp.code() + "): " + raw);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Event polling
    // -------------------------------------------------------------------------

    public ReadEventsResponse readEvents(String gameId, int afterSeq) throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/games/" + gameId + "/events?afterSeq=" + afterSeq)
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Read events failed (" + resp.code() + "): " + raw);
            ReadEventsResponse parsed = gson.fromJson(raw, ReadEventsResponse.class);
            if (parsed == null) throw new IOException("Empty response from events endpoint");
            if (parsed.events == null) parsed.events = Collections.emptyList();
            return parsed;
        }
    }

    public RosterSnapshot fetchRoster(String gameId) throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/games/" + gameId + "/roster")
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Fetch roster failed (" + resp.code() + "): " + raw);
            RosterSnapshot parsed = gson.fromJson(raw, RosterSnapshot.class);
            if (parsed == null) throw new IOException("Empty response from roster endpoint");
            if (parsed.players == null) parsed.players = Collections.emptyList();
            return parsed;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Response post(String path, JsonObject body, String writeKey) throws IOException
    {
        Request.Builder builder = new Request.Builder()
            .url(BASE_URL + path)
            .post(RequestBody.create(JSON, gson.toJson(body)));
        if (writeKey != null && !writeKey.isEmpty())
        {
            builder.header("Authorization", "Bearer " + writeKey);
        }
        return http.newCall(builder.build()).execute();
    }

    private static String bodyString(Response resp) throws IOException
    {
        return resp.body() != null ? resp.body().string() : "";
    }

    // -------------------------------------------------------------------------
    // Result / response types
    // -------------------------------------------------------------------------

    public static final class CreateGameResult
    {
        public final String gameId;
        public final String joinCode;
        public final String writeKey;
        public final String playerToken;

        public CreateGameResult(String gameId, String joinCode, String writeKey, String playerToken)
        {
            this.gameId = gameId;
            this.joinCode = joinCode;
            this.writeKey = writeKey;
            this.playerToken = playerToken;
        }
    }

    public static final class TileSpec
    {
        public final int x, y, plane;
        public final String tileType;
        public final String color; // nullable
        public final Integer orientation; // nullable -- Jagex Angle Units, GOALPOST_A/B only

        public TileSpec(int x, int y, int plane, String tileType, String color, Integer orientation)
        {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.tileType = tileType;
            this.color = color;
            this.orientation = orientation;
        }
    }

    public static final class PointSpec
    {
        public final int x, y, plane;
        public final String tileType; // nullable -- omitted strips every type at this position

        public PointSpec(int x, int y, int plane, String tileType)
        {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.tileType = tileType;
        }
    }

    public static final class JoinResult
    {
        public final String gameId;
        public final String hostRsn;
        public final String playerToken;

        public JoinResult(String gameId, String hostRsn, String playerToken)
        {
            this.gameId = gameId;
            this.hostRsn = hostRsn;
            this.playerToken = playerToken;
        }
    }

    public static class ReadEventsResponse
    {
        public String gameId;
        public int latestSeq;
        public List<EventOut> events;
    }

    public static class EventOut
    {
        public int seq;
        public String eventId;
        public String ts;
        public String type;
        public JsonObject payload;
    }

    public static class RosterSnapshot
    {
        public String gameId;
        public int latestSeq;
        public String status;
        public String ballHolder;
        public String startTime;
        public Integer durationSeconds;
        public Boolean paused;
        public Long pausedRemainingMs;
        public String teamAName;
        public String teamBName;
        public int teamAScore;
        public int teamBScore;
        public Boolean obligationActive;
        public String obligationTeam;
        public String obligationScorer;
        public String obligationKind;
        public List<RosterPlayerOut> players;
    }

    public static class RosterPlayerOut
    {
        public String rsn;
        public String role;
        public boolean joined;
        public boolean online;
        public String number;
    }

    public static class TilesResponse
    {
        public String gameId;
        public List<TileOut> tiles;
    }

    public static class TileOut
    {
        public int x;
        public int y;
        public int plane;
        public String tileType;
        public String color;
        public Integer orientation;
    }

    private static class CreateGameResponse
    {
        String gameId;
        String joinCode;
        String writeKey;
        String playerToken;
    }

    private static class JoinResponse
    {
        String gameId;
        String host;
        String playerToken;
    }

    static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}