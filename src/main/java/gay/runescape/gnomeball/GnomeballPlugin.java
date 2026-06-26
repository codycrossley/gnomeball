package gay.runescape.gnomeball;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(name = "Gnomeball")
public class GnomeballPlugin extends Plugin
{
    private static final String CONFIG_GROUP  = "gnomeball";
    private static final String KEY_GAME_ID   = "activeGameId";
    private static final String KEY_JOIN_CODE = "activeJoinCode";
    private static final String KEY_WRITE_KEY = "activeWriteKey";
    private static final String KEY_HOST_RSN  = "activeHostRsn";
    private static final String KEY_PHASE     = "activePhase";
    private static final String KEY_DEADLINE  = "activeDeadlineMs";

    private static final String COLOR_REFEREE = "DCB428";
    private static final String COLOR_TEAM_A  = "3C78DC";
    private static final String COLOR_TEAM_B  = "C83C3C";

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private GnomeballConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

    private GnomeballPanel panel;
    private NavigationButton navButton;
    private PlayerOverlay playerOverlay;
    private TimerOverlay timerOverlay;
    private TileOverlay tileOverlay;

    private ApiClient apiClient;
    private EventPoller poller;
    private RosterReducer rosterReducer;
    private TileReducer tileReducer;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "gnomeball-actions");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r ->
    {
        Thread t = new Thread(r, "gnomeball-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> heartbeatFuture = null;
    private volatile ScheduledFuture<?> onlineRefreshFuture = null;

    // ---- game state ----
    private volatile String gameId   = null;
    private volatile String writeKey = null;
    private volatile String joinCode = null;
    private volatile String hostRsn  = null;
    private volatile GamePhase phase = GamePhase.DISCONNECTED;
    private volatile long deadlineMs  = 0;
    private volatile String winner   = null;
    private volatile String teamAName = "Team A";
    private volatile String teamBName = "Team B";
    private volatile int teamAScore = 0;
    private volatile int teamBScore = 0;
    private volatile boolean gridPlacementMode = false;
    private volatile boolean gridRemovalMode = false;
    private volatile int gridWidth = 5;
    private volatile int gridHeight = 5;
    private volatile String zoneTeam = null; // "TEAM_A" or "TEAM_B"
    private final Set<WorldPoint> zoneTiles = new HashSet<>();
    private volatile long goalFlashUntil = 0;
    private volatile String goalFlashTeam = null;
    private volatile int goalFlashOldScore = 0;
    private volatile int goalFlashNewScore = 0;
    private volatile long whistleFlashUntil = 0;
    private volatile boolean timerPaused = false;
    private volatile long pausedRemainingMs = 0;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void startUp()
    {
        apiClient     = new ApiClient(okHttpClient, gson);
        rosterReducer = new RosterReducer();
        tileReducer   = new TileReducer();

        panel = new GnomeballPanel(this);
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Gnomeball")
            .icon(icon)
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        playerOverlay = new PlayerOverlay(client, config, this, rosterReducer);
        timerOverlay = new TimerOverlay(client, this);
        tileOverlay = new TileOverlay(client, config, this, tileReducer);
        overlayManager.add(playerOverlay);
        overlayManager.add(timerOverlay);
        overlayManager.add(tileOverlay);

        poller = new EventPoller(apiClient, new EventPoller.Listener()
        {
            @Override public void onEvent(ApiClient.EventOut e) { handleEvent(e); }
            @Override public void onError(Exception e) { log.debug("Poll error: {}", e.getMessage()); }
        });

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            String savedId = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_GAME_ID, String.class);
            if (savedId != null && !savedId.isBlank())
            {
                resumeGameAsync(savedId);
            }
        }
    }

    @Override
    protected void shutDown()
    {
        stopPeriodicTasks();
        heartbeatScheduler.shutdownNow();
        if (poller != null) poller.shutdown();
        executor.shutdownNow();
        if (playerOverlay != null) overlayManager.remove(playerOverlay);
        if (timerOverlay != null) overlayManager.remove(timerOverlay);
        if (tileOverlay != null) overlayManager.remove(tileOverlay);
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        resetState();
    }

    @Provides
    GnomeballConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GnomeballConfig.class);
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            if (!poller.isRunning())
            {
                String savedId = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_GAME_ID, String.class);
                if (savedId != null && !savedId.isBlank())
                {
                    resumeGameAsync(savedId);
                    return;
                }
            }
            SwingUtilities.invokeLater(() -> panel.refresh());
            return;
        }

        if (event.getGameState() == GameState.LOGIN_SCREEN
            || event.getGameState() == GameState.HOPPING)
        {
            poller.stop();
            stopPeriodicTasks();
            gameId = null; writeKey = null; joinCode = null; hostRsn = null;
            phase = GamePhase.DISCONNECTED; deadlineMs = 0; winner = null;
            teamAName = "Team A"; teamBName = "Team B"; teamAScore = 0; teamBScore = 0;
            if (rosterReducer != null) rosterReducer.reset();
            if (tileReducer != null) tileReducer.reset();
            SwingUtilities.invokeLater(() -> panel.refresh());
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (!isHost()) return;
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return;

        if ("Walk here".equals(event.getOption()))
        {
            if (zoneTeam != null)
            {
                addZoneMenuEntries();
                return;
            }
            if (gridPlacementMode || gridRemovalMode)
            {
                addGridMenuEntries();
                return;
            }
            addTileMenuEntries(event);
            return;
        }

        if (!"Follow".equals(event.getOption())) return;
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;

        MenuEntry enlistEntry = client.createMenuEntry(-1)
            .setOption("Enlist")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE_PLAYER)
            .setIdentifier(event.getIdentifier());

        Menu subMenu = enlistEntry.createSubMenu();
        subMenu.createMenuEntry(-1)
            .setOption("<col=" + COLOR_TEAM_B + ">" + teamBName + "</col>")
            .setTarget("").setType(MenuAction.RUNELITE_PLAYER).setIdentifier(event.getIdentifier())
            .onClick(me -> handleEnlistClick(me, GnomeballRole.TEAM_B));
        subMenu.createMenuEntry(-1)
            .setOption("<col=" + COLOR_TEAM_A + ">" + teamAName + "</col>")
            .setTarget("").setType(MenuAction.RUNELITE_PLAYER).setIdentifier(event.getIdentifier())
            .onClick(me -> handleEnlistClick(me, GnomeballRole.TEAM_A));
        subMenu.createMenuEntry(-1)
            .setOption("<col=" + COLOR_REFEREE + ">Referee</col>")
            .setTarget("").setType(MenuAction.RUNELITE_PLAYER).setIdentifier(event.getIdentifier())
            .onClick(me -> handleEnlistClick(me, GnomeballRole.REFEREE));
    }

    private void addZoneMenuEntries()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint wp = tile.getWorldLocation();
        if (wp == null) return;

        String teamLabel = "TEAM_A".equals(zoneTeam) ? teamAName : teamBName;
        String teamColor = "TEAM_A".equals(zoneTeam) ? COLOR_TEAM_A : COLOR_TEAM_B;

        client.createMenuEntry(-1)
            .setOption("Cancel Zone")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelZoneMode());

        if (!zoneTiles.isEmpty())
        {
            client.createMenuEntry(-1)
                .setOption("<col=" + teamColor + ">Confirm " + teamLabel + " Zone (" + zoneTiles.size() + " tiles)</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> commitZone());
        }

        boolean alreadySelected = zoneTiles.contains(wp);
        client.createMenuEntry(-1)
            .setOption(alreadySelected ? "Remove Tile" : "<col=" + teamColor + ">Add Tile</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me ->
            {
                if (zoneTiles.contains(wp))
                    zoneTiles.remove(wp);
                else
                    zoneTiles.add(wp);
            });
    }

    private void commitZone()
    {
        if (!isHost() || gameId == null || zoneTeam == null || zoneTiles.isEmpty()) return;

        String colorHex = "TEAM_A".equals(zoneTeam) ? "#3C78DC" : "#C83C3C";
        Set<WorldPoint> tiles = new HashSet<>(zoneTiles);
        cancelZoneMode();

        executor.submit(() ->
        {
            try
            {
                for (WorldPoint wp : tiles)
                {
                    int x = wp.getX(), y = wp.getY(), plane = wp.getPlane();
                    if (!tiles.contains(new WorldPoint(x, y + 1, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_N", colorHex);
                    if (!tiles.contains(new WorldPoint(x, y - 1, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_S", colorHex);
                    if (!tiles.contains(new WorldPoint(x + 1, y, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_E", colorHex);
                    if (!tiles.contains(new WorldPoint(x - 1, y, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_W", colorHex);
                }
            }
            catch (Exception ex) { log.warn("Commit zone failed: {}", ex.getMessage()); }
        });
    }

    private void addGridMenuEntries()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint center = tile.getWorldLocation();
        if (center == null) return;

        client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelGridMode());

        if (gridPlacementMode)
        {
            client.createMenuEntry(-1)
                .setOption("<col=00FF00>Place Grid (" + gridWidth + "x" + gridHeight + ")</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> commitGrid(center));
        }
        else if (gridRemovalMode)
        {
            client.createMenuEntry(-1)
                .setOption("<col=FF4444>Remove Grid (" + gridWidth + "x" + gridHeight + ")</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> removeGrid(center));
        }
    }

    private void commitGrid(WorldPoint center)
    {
        cancelGridMode();
        if (!isHost() || gameId == null) return;

        int startX = center.getX() - gridWidth / 2;
        int startY = center.getY() - gridHeight / 2;
        int endX = startX + gridWidth - 1;
        int endY = startY + gridHeight - 1;
        int plane = center.getPlane();

        executor.submit(() ->
        {
            try
            {
                for (int x = startX; x <= endX; x++)
                {
                    apiClient.markTile(gameId, writeKey, x, startY, plane, "BOUNDARY_S", null);
                    apiClient.markTile(gameId, writeKey, x, endY, plane, "BOUNDARY_N", null);
                }
                for (int y = startY; y <= endY; y++)
                {
                    apiClient.markTile(gameId, writeKey, startX, y, plane, "BOUNDARY_W", null);
                    apiClient.markTile(gameId, writeKey, endX, y, plane, "BOUNDARY_E", null);
                }
            }
            catch (Exception ex) { log.warn("Commit grid failed: {}", ex.getMessage()); }
        });
    }

    private void removeGrid(WorldPoint center)
    {
        cancelGridMode();
        if (!isHost() || gameId == null) return;

        int startX = center.getX() - gridWidth / 2;
        int startY = center.getY() - gridHeight / 2;
        int endX = startX + gridWidth - 1;
        int endY = startY + gridHeight - 1;
        int plane = center.getPlane();

        executor.submit(() ->
        {
            try
            {
                for (int x = startX; x <= endX; x++)
                {
                    apiClient.unmarkTile(gameId, writeKey, x, startY, plane, "BOUNDARY_S");
                    apiClient.unmarkTile(gameId, writeKey, x, endY, plane, "BOUNDARY_N");
                }
                for (int y = startY; y <= endY; y++)
                {
                    apiClient.unmarkTile(gameId, writeKey, startX, y, plane, "BOUNDARY_W");
                    apiClient.unmarkTile(gameId, writeKey, endX, y, plane, "BOUNDARY_E");
                }
            }
            catch (Exception ex) { log.warn("Remove grid failed: {}", ex.getMessage()); }
        });
    }

    private void addTileMenuEntries(MenuEntryAdded event)
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint wp = tile.getWorldLocation();
        if (wp == null) return;

        boolean hasAny = tileReducer.hasAnyMarker(wp);

        MenuEntry markEntry = client.createMenuEntry(-1)
            .setOption(hasAny ? "Edit Tile" : "Mark Tile")
            .setTarget("")
            .setType(MenuAction.RUNELITE);

        Menu subMenu = markEntry.createSubMenu();

        if (hasAny)
        {
            subMenu.createMenuEntry(-1)
                .setOption("Unmark All")
                .setTarget("").setType(MenuAction.RUNELITE)
                .onClick(me -> onUnmarkTile(wp, null));
        }

        subMenu.createMenuEntry(-1)
            .setOption("Boundary W")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "BOUNDARY_W"));
        subMenu.createMenuEntry(-1)
            .setOption("Boundary S")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "BOUNDARY_S"));
        subMenu.createMenuEntry(-1)
            .setOption("Boundary E")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "BOUNDARY_E"));
        subMenu.createMenuEntry(-1)
            .setOption("Boundary N")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "BOUNDARY_N"));
        subMenu.createMenuEntry(-1)
            .setOption("Standard")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "STANDARD"));
    }

    private void toggleTile(WorldPoint wp, String tileType)
    {
        if (tileReducer.hasMarker(wp, tileType))
        {
            onUnmarkTile(wp, tileType);
        }
        else
        {
            onMarkTile(wp, tileType);
        }
    }

    private void onMarkTile(WorldPoint wp, String tileType)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.markTile(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(), tileType, null); }
            catch (Exception ex) { log.warn("Mark tile failed: {}", ex.getMessage()); }
        });
    }

    private void onUnmarkTile(WorldPoint wp, String tileType)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.unmarkTile(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(), tileType); }
            catch (Exception ex) { log.warn("Unmark tile failed: {}", ex.getMessage()); }
        });
    }

    private void handleEnlistClick(MenuEntry menuEntry, GnomeballRole role)
    {
        Player player = (Player) menuEntry.getActor();
        if (player == null || player.getName() == null) return;
        String rsn = Text.toJagexName(player.getName());
        if (rsn == null || rsn.isBlank()) return;
        onAssignRoleClicked(rsn, role);
    }

    // -------------------------------------------------------------------------
    // Server event handling
    // -------------------------------------------------------------------------

    private void handleEvent(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;
        final String type = e.type.toUpperCase(Locale.ROOT);

        rosterReducer.apply(e);
        tileReducer.apply(e);

        switch (type)
        {
            case "GAME_STARTED":
            {
                phase = GamePhase.ACTIVE;
                timerPaused = false;
                pausedRemainingMs = 0;
                if (e.payload != null)
                {
                    long startMs = parseEpochMs(safeStr(e.payload, "startTime"));
                    int durationSecs = safeInt(e.payload, "durationSeconds");
                    if (startMs > 0 && durationSecs > 0)
                        deadlineMs = startMs + (durationSecs * 1000L);
                }
                saveSession();
                break;
            }
            case "GAME_ENDED":
            {
                phase = GamePhase.ENDED;
                if (e.payload != null) winner = safeStr(e.payload, "winner");
                clearSession();
                poller.stop();
                stopPeriodicTasks();
                break;
            }
            case "TEAM_RENAMED":
            {
                String team = safeStr(e.payload, "team");
                String name = safeStr(e.payload, "name");
                if ("TEAM_A".equals(team) && name != null) teamAName = name;
                else if ("TEAM_B".equals(team) && name != null) teamBName = name;
                break;
            }
            case "SCORE_UPDATED":
            {
                String team = safeStr(e.payload, "team");
                int score = safeInt(e.payload, "score");
                if ("TEAM_A".equals(team))
                {
                    if (score > teamAScore)
                    {
                        goalFlashTeam = "TEAM_A";
                        goalFlashOldScore = teamAScore;
                        goalFlashNewScore = score;
                        goalFlashUntil = System.currentTimeMillis() + 3000;
                    }
                    teamAScore = score;
                }
                else if ("TEAM_B".equals(team))
                {
                    if (score > teamBScore)
                    {
                        goalFlashTeam = "TEAM_B";
                        goalFlashOldScore = teamBScore;
                        goalFlashNewScore = score;
                        goalFlashUntil = System.currentTimeMillis() + 3000;
                    }
                    teamBScore = score;
                }
                break;
            }
            case "WHISTLE_BLOWN":
            {
                long rem = safeLong(e.payload, "remainingMs");
                if (!timerPaused)
                {
                    pausedRemainingMs = rem > 0 ? rem : Math.max(0, deadlineMs - System.currentTimeMillis());
                    timerPaused = true;
                }
                whistleFlashUntil = System.currentTimeMillis() + 3000;
                break;
            }
            case "TIMER_PAUSED":
            {
                long rem = safeLong(e.payload, "remainingMs");
                if (rem > 0) pausedRemainingMs = rem;
                timerPaused = true;
                break;
            }
            case "TIMER_RESUMED":
            {
                long dl = safeLong(e.payload, "deadlineMs");
                if (dl > 0) deadlineMs = dl;
                timerPaused = false;
                break;
            }
            case "PLAYER_JOINED":
            case "ROLE_ASSIGNED":
            case "PLAYER_LEFT":
                refreshRosterNow();
                break;
        }

        SwingUtilities.invokeLater(() -> panel.refresh());
    }

    private void refreshRosterNow()
    {
        final String gid = gameId;
        if (gid == null) return;
        executor.submit(() ->
        {
            try
            {
                ApiClient.RosterSnapshot snap = apiClient.fetchRoster(gid);
                syncGameState(snap);
                SwingUtilities.invokeLater(() -> panel.refresh());
            }
            catch (Exception ex) { log.debug("Roster refresh failed: {}", ex.getMessage()); }
        });
    }

    private void syncGameState(ApiClient.RosterSnapshot snap)
    {
        rosterReducer.syncFromRoster(snap.players);
        if (snap.teamAName != null) teamAName = snap.teamAName;
        if (snap.teamBName != null) teamBName = snap.teamBName;
        if (snap.teamAScore > teamAScore)
        {
            goalFlashTeam = "TEAM_A";
            goalFlashOldScore = teamAScore;
            goalFlashNewScore = snap.teamAScore;
            goalFlashUntil = System.currentTimeMillis() + 3000;
        }
        if (snap.teamBScore > teamBScore)
        {
            goalFlashTeam = "TEAM_B";
            goalFlashOldScore = teamBScore;
            goalFlashNewScore = snap.teamBScore;
            goalFlashUntil = System.currentTimeMillis() + 3000;
        }
        teamAScore = snap.teamAScore;
        teamBScore = snap.teamBScore;

        if (snap.status != null)
        {
            try { phase = GamePhase.valueOf(snap.status); }
            catch (IllegalArgumentException ignored) {}
        }
        if (snap.startTime != null && snap.durationSeconds != null && snap.durationSeconds > 0)
        {
            long startMs = parseEpochMs(snap.startTime);
            if (startMs > 0) deadlineMs = startMs + (snap.durationSeconds * 1000L);
        }

        if (Boolean.TRUE.equals(snap.paused))
        {
            timerPaused = true;
            if (snap.pausedRemainingMs != null) pausedRemainingMs = snap.pausedRemainingMs;
        }
        else
        {
            timerPaused = false;
        }
    }

    // -------------------------------------------------------------------------
    // Panel callbacks
    // -------------------------------------------------------------------------

    public void onCreateClicked()
    {
        String rsn = localRsn();
        if (rsn == null) { log.debug("Cannot create game: not logged in"); return; }

        executor.submit(() ->
        {
            try
            {
                ApiClient.CreateGameResult result = apiClient.createGame(rsn);
                gameId   = result.gameId;
                writeKey = result.writeKey;
                joinCode = result.joinCode;
                hostRsn  = rsn;
                phase    = GamePhase.LOBBY;
                saveSession();
                poller.start(gameId);
                startPeriodicTasks();
                SwingUtilities.invokeLater(() -> panel.refresh());
            }
            catch (Exception ex) { log.warn("Create game failed: {}", ex.getMessage()); }
        });
    }

    public void onJoinClicked(String code)
    {
        if (ApiClient.isBlank(code)) return;
        String rsn = localRsn();
        if (rsn == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.JoinResult result = apiClient.joinGame(code, rsn);
                gameId   = result.gameId;
                joinCode = code.toUpperCase(Locale.ROOT);
                hostRsn  = result.hostRsn;
                writeKey = null;
                phase    = GamePhase.LOBBY;

                ApiClient.RosterSnapshot snap = apiClient.fetchRoster(gameId);
                rosterReducer.loadSnapshot(snap.players);
                syncGameState(snap);
                loadTiles();
                saveSession();
                poller.start(gameId, snap.latestSeq);
                startPeriodicTasks();
                SwingUtilities.invokeLater(() -> panel.refresh());
            }
            catch (Exception ex) { log.warn("Join game failed: {}", ex.getMessage()); }
        });
    }

    public void onStartClicked(int durationSeconds)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.startGame(gameId, writeKey, durationSeconds); }
            catch (Exception ex) { log.warn("Start game failed: {}", ex.getMessage()); }
        });
    }

    public void onEndClicked()
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.endGame(gameId, writeKey); }
            catch (Exception ex) { log.warn("End game failed: {}", ex.getMessage()); }
        });
    }

    public void onLeaveClicked()
    {
        final String gid = gameId;
        final String rsn = localRsn();
        poller.stop();
        stopPeriodicTasks();
        resetState();
        SwingUtilities.invokeLater(() -> panel.refresh());

        if (gid != null && rsn != null)
        {
            executor.submit(() ->
            {
                try { apiClient.leaveGame(gid, rsn); }
                catch (Exception ex) { log.debug("Leave game failed: {}", ex.getMessage()); }
            });
        }
    }

    public void onRenameTeam(String team, String name)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.renameTeam(gameId, writeKey, team, name); }
            catch (Exception ex) { log.warn("Rename team failed: {}", ex.getMessage()); }
        });
    }

    public void onUpdateScore(String team, int score)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.updateScore(gameId, writeKey, team, score); }
            catch (Exception ex) { log.warn("Update score failed: {}", ex.getMessage()); }
        });
    }

    public void onAssignRoleClicked(String playerRsn, GnomeballRole role)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.assignRole(gameId, writeKey, playerRsn, role); }
            catch (Exception ex) { log.warn("Assign role failed: {}", ex.getMessage()); }
        });
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public GamePhase     getPhase()      { return phase; }
    public String        getGameId()     { return gameId; }
    public String        getJoinCode()   { return joinCode; }
    public long          getDeadlineMs() { return deadlineMs; }
    public String        getWinner()     { return winner; }
    public boolean       isHost()        { return writeKey != null; }
    public RosterReducer getRoster()     { return rosterReducer; }
    public GnomeballConfig getConfig()   { return config; }
    public String        getTeamAName()  { return teamAName; }
    public String        getTeamBName()  { return teamBName; }
    public int           getTeamAScore() { return teamAScore; }
    public int           getTeamBScore() { return teamBScore; }
    public boolean       isGridPlacementMode() { return gridPlacementMode; }
    public boolean       isGridRemovalMode()   { return gridRemovalMode; }
    public int           getGridWidth()  { return gridWidth; }
    public int           getGridHeight() { return gridHeight; }
    public TileReducer   getTileReducer() { return tileReducer; }

    public void startGridPlacement(int width, int height)
    {
        gridWidth = width;
        gridHeight = height;
        gridPlacementMode = true;
        gridRemovalMode = false;
    }

    public void startGridRemoval(int width, int height)
    {
        gridWidth = width;
        gridHeight = height;
        gridRemovalMode = true;
        gridPlacementMode = false;
    }

    public void cancelGridMode()
    {
        gridPlacementMode = false;
        gridRemovalMode = false;
    }

    public String        getZoneTeam()     { return zoneTeam; }
    public Set<WorldPoint> getZoneTiles()  { return zoneTiles; }
    public boolean       isZoneMode()      { return zoneTeam != null; }

    public long          getGoalFlashUntil()    { return goalFlashUntil; }
    public String        getGoalFlashTeam()     { return goalFlashTeam; }
    public int           getGoalFlashOldScore() { return goalFlashOldScore; }
    public int           getGoalFlashNewScore() { return goalFlashNewScore; }
    public long          getWhistleFlashUntil() { return whistleFlashUntil; }
    public boolean       isTimerPaused()        { return timerPaused; }
    public long          getPausedRemainingMs() { return pausedRemainingMs; }

    public boolean isReferee()
    {
        String rsn = localRsn();
        if (rsn == null) return false;
        return rosterReducer.getRole(rsn) == GnomeballRole.REFEREE;
    }

    public void onBlowWhistleClicked()
    {
        final String gid = gameId;
        final String rsn = localRsn();
        if (gid == null || rsn == null) return;

        // Optimistic local update so the referee sees it immediately
        final long remaining = timerPaused ? pausedRemainingMs
            : Math.max(0, deadlineMs - System.currentTimeMillis());
        if (!timerPaused)
        {
            pausedRemainingMs = remaining;
            timerPaused = true;
        }
        whistleFlashUntil = System.currentTimeMillis() + 3000;
        SwingUtilities.invokeLater(() -> panel.refresh());

        executor.submit(() ->
        {
            try { apiClient.blowWhistle(gid, rsn, remaining); }
            catch (Exception ex) { log.warn("Blow whistle failed: {}", ex.getMessage()); }
        });
    }

    public void onTimerStartStopClicked()
    {
        final String gid = gameId;
        final String rsn = localRsn();
        if (gid == null || rsn == null) return;

        if (timerPaused)
        {
            // Optimistic resume
            deadlineMs = System.currentTimeMillis() + pausedRemainingMs;
            timerPaused = false;
            SwingUtilities.invokeLater(() -> panel.refresh());

            executor.submit(() ->
            {
                try { apiClient.resumeTimer(gid, rsn); }
                catch (Exception ex) { log.warn("Resume timer failed: {}", ex.getMessage()); }
            });
        }
        else
        {
            // Optimistic pause
            final long remaining = Math.max(0, deadlineMs - System.currentTimeMillis());
            pausedRemainingMs = remaining;
            timerPaused = true;
            SwingUtilities.invokeLater(() -> panel.refresh());

            executor.submit(() ->
            {
                try { apiClient.pauseTimer(gid, rsn, remaining); }
                catch (Exception ex) { log.warn("Pause timer failed: {}", ex.getMessage()); }
            });
        }
    }

    public void startZoneMode(String team)
    {
        cancelGridMode();
        zoneTeam = team;
        zoneTiles.clear();
    }

    public void cancelZoneMode()
    {
        zoneTeam = null;
        zoneTiles.clear();
    }

    // -------------------------------------------------------------------------
    // Session persistence
    // -------------------------------------------------------------------------

    private void saveSession()
    {
        if (gameId == null) return;
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_GAME_ID,   gameId);
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_JOIN_CODE, joinCode != null ? joinCode : "");
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_WRITE_KEY, writeKey != null ? writeKey : "");
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_HOST_RSN,  hostRsn != null ? hostRsn : "");
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PHASE,     phase.name());
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_DEADLINE,  String.valueOf(deadlineMs));
    }

    private void clearSession()
    {
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_GAME_ID);
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_JOIN_CODE);
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_WRITE_KEY);
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_HOST_RSN);
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_PHASE);
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_DEADLINE);
    }

    private void resumeGameAsync(String savedGameId)
    {
        executor.submit(() ->
        {
            try
            {
                ApiClient.RosterSnapshot snap = apiClient.fetchRoster(savedGameId);
                rosterReducer.loadSnapshot(snap.players);
                syncGameState(snap);

                gameId = savedGameId;
                loadTiles();
                joinCode = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_JOIN_CODE, String.class);
                String savedWriteKey = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_WRITE_KEY, String.class);
                writeKey = (savedWriteKey != null && !savedWriteKey.isEmpty()) ? savedWriteKey : null;
                hostRsn  = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_HOST_RSN, String.class);

                String savedPhaseStr = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PHASE, String.class);
                try { phase = savedPhaseStr != null ? GamePhase.valueOf(savedPhaseStr) : GamePhase.LOBBY; }
                catch (IllegalArgumentException ignored) { phase = GamePhase.LOBBY; }

                String savedDeadlineStr = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_DEADLINE, String.class);
                try { deadlineMs = savedDeadlineStr != null ? Long.parseLong(savedDeadlineStr) : 0; }
                catch (NumberFormatException ignored) { deadlineMs = 0; }

                poller.start(savedGameId, snap.latestSeq);
                startPeriodicTasks();
                log.debug("Resumed gnomeball game {}", savedGameId);
                SwingUtilities.invokeLater(() -> panel.refresh());
            }
            catch (Exception ex)
            {
                log.debug("Failed to resume game {}: {}", savedGameId, ex.getMessage());
                clearSession();
                SwingUtilities.invokeLater(() -> panel.refresh());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Periodic tasks (heartbeat + online refresh)
    // -------------------------------------------------------------------------

    private void startPeriodicTasks()
    {
        stopPeriodicTasks();
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() ->
        {
            final String gid = gameId;
            final String rsn = localRsn();
            if (gid == null || rsn == null) return;
            try { apiClient.sendHeartbeat(gid, rsn); }
            catch (Exception ex) { log.debug("Heartbeat failed: {}", ex.getMessage()); }
        }, 0, 15, TimeUnit.SECONDS);

        onlineRefreshFuture = heartbeatScheduler.scheduleAtFixedRate(() ->
        {
            final String gid = gameId;
            if (gid == null) return;
            try
            {
                ApiClient.RosterSnapshot snap = apiClient.fetchRoster(gid);
                syncGameState(snap);
                SwingUtilities.invokeLater(() -> panel.refresh());
            }
            catch (Exception ex) { log.debug("Online refresh failed: {}", ex.getMessage()); }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopPeriodicTasks()
    {
        if (heartbeatFuture != null)   { heartbeatFuture.cancel(false);   heartbeatFuture = null; }
        if (onlineRefreshFuture != null) { onlineRefreshFuture.cancel(false); onlineRefreshFuture = null; }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String localRsn()
    {
        if (client.getLocalPlayer() == null) return null;
        String name = client.getLocalPlayer().getName();
        return name != null ? Text.toJagexName(name) : null;
    }

    private void resetState()
    {
        clearSession();
        gameId = null; writeKey = null; joinCode = null; hostRsn = null;
        phase = GamePhase.DISCONNECTED; deadlineMs = 0; winner = null;
        teamAName = "Team A"; teamBName = "Team B"; teamAScore = 0; teamBScore = 0;
        timerPaused = false; pausedRemainingMs = 0; whistleFlashUntil = 0;
        if (rosterReducer != null) rosterReducer.reset();
        if (tileReducer != null) tileReducer.reset();
    }

    private void loadTiles()
    {
        final String gid = gameId;
        if (gid == null) return;
        try
        {
            ApiClient.TilesResponse resp = apiClient.fetchTiles(gid);
            tileReducer.loadAll(resp.tiles);
        }
        catch (Exception ex) { log.debug("Load tiles failed: {}", ex.getMessage()); }
    }

    private static String safeStr(com.google.gson.JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    private static int safeInt(com.google.gson.JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : 0; }
        catch (Exception ignored) { return 0; }
    }

    private static long safeLong(com.google.gson.JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsLong() : 0; }
        catch (Exception ignored) { return 0; }
    }

    private static long parseEpochMs(String iso)
    {
        if (iso == null) return 0;
        try { return java.time.Instant.parse(iso).toEpochMilli(); }
        catch (Exception ignored) { return 0; }
    }
}