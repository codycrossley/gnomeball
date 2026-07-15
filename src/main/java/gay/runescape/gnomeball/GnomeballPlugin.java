package gay.runescape.gnomeball;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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
import net.runelite.api.Actor;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.kit.KitType;
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

    private static final int    GNOMEBALL_ITEM_ID = 2528;
    private static final String COLOR_REFEREE = "3CB34A";
    private static final String COLOR_TEAM_A  = "3C78DC";
    private static final String COLOR_TEAM_B  = "C83C3C";

    // Tag effect — copied from the Landmines detonation spotanim in the Skwid Games plugin
    private static final int    TAG_MODEL_ID  = 3960;
    private static final int    TAG_ANIM_ID   = 1230;
    private static final int    ITEM_RUBBER_CHICKEN = 4566;
    private static final int    ITEM_STALE_BAGUETTE = 20590;

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
    private final List<RuneLiteObject> activeTagEffects = new ArrayList<>();
    private volatile WorldPoint lastSelfPosition = null;
    private volatile String ballHolder   = null;
    private volatile long   interceptionFlashUntil = 0;
    private volatile String interceptionPlayer     = null;
    private volatile String interceptionTeam       = null;
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

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Snapshot our own position once per tick so a same-tick attack can still be matched
        // against where we were a moment ago, even if we've since stepped away.
        lastSelfPosition = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;

        if (phase != GamePhase.ACTIVE || timerPaused || ballHolder == null) return;

        String localRsn = localRsn();
        if (localRsn == null || !ballHolder.equalsIgnoreCase(localRsn)) return;

        GnomeballRole myRole = rosterReducer.getRole(localRsn);
        if (myRole != GnomeballRole.TEAM_A && myRole != GnomeballRole.TEAM_B) return;

        String zoneType = myRole == GnomeballRole.TEAM_A ? "ZONE_A" : "ZONE_B";
        if (client.getLocalPlayer() == null) return;
        WorldPoint pos = client.getLocalPlayer().getWorldLocation();
        if (!tileReducer.hasMarker(pos, zoneType)) return;

        onZoneScore(myRole == GnomeballRole.TEAM_A ? "TEAM_A" : "TEAM_B");
    }

    private void onZoneScore(String scoringTeam)
    {
        int oldScore = "TEAM_A".equals(scoringTeam) ? teamAScore : teamBScore;
        int newScore = oldScore + 1;

        // Optimistic local update — prevents re-triggering on subsequent ticks and shows flash immediately
        goalFlashTeam     = scoringTeam;
        goalFlashOldScore = oldScore;
        goalFlashNewScore = newScore;
        goalFlashUntil    = System.currentTimeMillis() + 3000;
        if ("TEAM_A".equals(scoringTeam)) teamAScore = newScore;
        else                              teamBScore = newScore;
        ballHolder = null;
        SwingUtilities.invokeLater(() -> panel.refresh());

        final String gid = gameId;
        final String rsn = localRsn();
        if (gid == null || rsn == null) return;
        executor.submit(() ->
        {
            try { apiClient.zoneGoal(gid, rsn); }
            catch (Exception ex) { log.warn("Zone goal failed: {}", ex.getMessage()); }
        });
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (phase != GamePhase.ACTIVE) return;
        if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_PLAYER) return;

        String localRsn = localRsn();
        if (localRsn == null) return;

        // Must currently hold the ball
        if (ballHolder == null || !ballHolder.equalsIgnoreCase(localRsn)) return;

        // Must be an enlisted team player
        GnomeballRole myRole = rosterReducer.getRole(localRsn);
        if (myRole != GnomeballRole.TEAM_A && myRole != GnomeballRole.TEAM_B) return;

        // Must be throwing a gnomeball
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return;
        Item item = inv.getItem(event.getParam0());
        if (item == null || item.getId() != GNOMEBALL_ITEM_ID) return;

        // Target must have a free weapon slot
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;
        Player target = (Player) event.getMenuEntry().getActor();
        if (target == null || target.getName() == null) return;

        PlayerComposition comp = target.getPlayerComposition();
        if (comp == null) return;
        int[] equipIds = comp.getEquipmentIds();
        if (equipIds == null || equipIds[KitType.WEAPON.getIndex()] != 0) return;

        String targetRsn = Text.toJagexName(target.getName());
        if (targetRsn == null || targetRsn.isBlank()) return;

        log.debug("Gnomeball pass: {} -> {}", localRsn, targetRsn);
        onPassBallClicked(targetRsn);
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (phase != GamePhase.ACTIVE) return;

        Actor actor = event.getActor();
        if (!(actor instanceof Player)) return;
        if (actor.getAnimation() == -1) return;

        Player attacker = (Player) actor;
        if (attacker.getName() == null) return;
        String attackerRsn = Text.toJagexName(attacker.getName());
        if (attackerRsn == null || attackerRsn.isBlank()) return;

        String selfRsn = localRsn();
        if (selfRsn == null) return;

        // Only the current ball holder can be tagged
        if (ballHolder == null || !ballHolder.equalsIgnoreCase(selfRsn)) return;

        // The attacker must actually be targeting the local (ball-holding) player.
        // getInteracting() can lag a tick behind the animation when the attacker had to walk
        // into range first, so also accept melee-adjacency as proof they're swinging at us.
        // Adjacency is checked against both our current position and our position as of the
        // last tick, since we may have already stepped away by the time the animation lands.
        Player localPlayer = client.getLocalPlayer();
        WorldPoint attackerPos = attacker.getWorldLocation();
        boolean targetingMe = localPlayer != null && attacker.getInteracting() == localPlayer;
        boolean adjacentNow = localPlayer != null
            && attackerPos != null
            && localPlayer.getWorldLocation() != null
            && attackerPos.distanceTo(localPlayer.getWorldLocation()) <= 1;
        boolean adjacentLastTick = attackerPos != null
            && lastSelfPosition != null
            && attackerPos.distanceTo(lastSelfPosition) <= 1;
        if (!targetingMe && !adjacentNow && !adjacentLastTick) return;

        GnomeballRole attackerRole = rosterReducer.getRole(attackerRsn);
        GnomeballRole selfRole = rosterReducer.getRole(selfRsn);
        boolean attackerIsTeam = attackerRole == GnomeballRole.TEAM_A || attackerRole == GnomeballRole.TEAM_B;
        boolean selfIsTeam = selfRole == GnomeballRole.TEAM_A || selfRole == GnomeballRole.TEAM_B;
        if (!attackerIsTeam || !selfIsTeam || attackerRole == selfRole) return;

        // Must be wielding a whackable weapon (Rubber chicken / Stale baguette)
        PlayerComposition comp = attacker.getPlayerComposition();
        if (comp == null) return;
        int[] equipIds = comp.getEquipmentIds();
        if (equipIds == null) return;
        int weaponSlotId = equipIds[KitType.WEAPON.getIndex()];
        if (weaponSlotId < PlayerComposition.ITEM_OFFSET) return;
        int weaponId = weaponSlotId - PlayerComposition.ITEM_OFFSET;
        if (weaponId != ITEM_RUBBER_CHICKEN && weaponId != ITEM_STALE_BAGUETTE) return;

        final String gid = gameId;
        if (gid == null) return;
        final String self = selfRsn;
        final String tagger = attackerRsn;

        log.debug("Whack tag: {} -> {} (anim={}, weapon={})", tagger, self, actor.getAnimation(), weaponId);
        executor.submit(() ->
        {
            try { apiClient.tagPlayer(gid, tagger, self); }
            catch (Exception ex) { log.debug("Tag report failed: {}", ex.getMessage()); }
        });
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
        String zoneType = "TEAM_A".equals(zoneTeam) ? "ZONE_A" : "ZONE_B";
        Set<WorldPoint> tiles = new HashSet<>(zoneTiles);
        cancelZoneMode();

        executor.submit(() ->
        {
            // Boundary edges — per-tile try/catch so one failure doesn't abort the rest
            for (WorldPoint wp : tiles)
            {
                int x = wp.getX(), y = wp.getY(), plane = wp.getPlane();
                try
                {
                    if (!tiles.contains(new WorldPoint(x, y + 1, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_N", colorHex);
                    if (!tiles.contains(new WorldPoint(x, y - 1, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_S", colorHex);
                    if (!tiles.contains(new WorldPoint(x + 1, y, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_E", colorHex);
                    if (!tiles.contains(new WorldPoint(x - 1, y, plane)))
                        apiClient.markTile(gameId, writeKey, x, y, plane, "BOUNDARY_W", colorHex);
                }
                catch (Exception ex) { log.warn("Zone boundary mark failed at {},{}: {}", x, y, ex.getMessage()); }
            }

            // Zone detection tiles — separate pass so any failure here never affects boundary rendering
            for (WorldPoint wp : tiles)
            {
                try { apiClient.markTile(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(), zoneType, null); }
                catch (Exception ex) { log.warn("Zone detection mark failed at {},{}: {}", wp.getX(), wp.getY(), ex.getMessage()); }
            }
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
            case "BALL_ASSIGNED":
            {
                String newHolder = safeStr(e.payload, "player");
                boolean manualAssign = safeBool(e.payload, "manual");
                if (newHolder != null && ballHolder != null && !manualAssign)
                {
                    GnomeballRole prevRole = rosterReducer.getRole(ballHolder);
                    GnomeballRole newRole  = rosterReducer.getRole(newHolder);
                    boolean prevIsTeam = prevRole == GnomeballRole.TEAM_A || prevRole == GnomeballRole.TEAM_B;
                    boolean newIsTeam  = newRole  == GnomeballRole.TEAM_A || newRole  == GnomeballRole.TEAM_B;
                    if (prevIsTeam && newIsTeam && prevRole != newRole)
                    {
                        interceptionPlayer    = newHolder;
                        interceptionTeam      = newRole == GnomeballRole.TEAM_A ? "TEAM_A" : "TEAM_B";
                        interceptionFlashUntil = System.currentTimeMillis() + 3000;
                    }
                }
                ballHolder = newHolder;
                break;
            }
            case "BALL_CLEARED":
            {
                ballHolder = null;
                break;
            }
            case "PLAYER_TAGGED":
            {
                String target = safeStr(e.payload, "target");
                if (target != null)
                {
                    final String targetRsn = target;
                    clientThread.invokeLater(() -> spawnTagEffect(targetRsn));
                }
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

    // -------------------------------------------------------------------------
    // Tag effect — copied from the Landmines detonation spotanim (Skwid Games plugin)
    // -------------------------------------------------------------------------

    /** Spawns the tag effect on a named player at their current location. Must be called on the client thread. */
    private void spawnTagEffect(String rsn)
    {
        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            if (!rsn.equalsIgnoreCase(Text.toJagexName(p.getName()))) continue;
            WorldPoint wp = p.getWorldLocation();
            if (wp == null) return;
            spawnTagEffectAt(wp);
            return;
        }
    }

    /** Spawns a world-space tag effect at {@code wp}. Must be called on the client thread. */
    private void spawnTagEffectAt(WorldPoint wp)
    {
        Model model = client.loadModel(TAG_MODEL_ID);
        if (model == null) return;

        Collection<WorldPoint> locals = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : locals)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            RuneLiteObject obj = client.createRuneLiteObject();
            obj.setModel(model);
            AnimationController ac = new AnimationController(client, TAG_ANIM_ID);
            ac.setOnFinished(_ac -> obj.setActive(false));
            obj.setAnimationController(ac);
            obj.setLocation(lp, wp.getPlane());
            obj.setActive(true);
            activeTagEffects.add(obj);
        }
    }

    /** Deactivates all active tag effect objects. Must be called on the client thread. */
    private void clearActiveTagEffects()
    {
        for (RuneLiteObject obj : activeTagEffects)
        {
            if (obj.isActive()) obj.setActive(false);
        }
        activeTagEffects.clear();
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
        if (snap.ballHolder != null) ballHolder = snap.ballHolder;

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

    private void onPassBallClicked(String targetRsn)
    {
        final String gid = gameId;
        final String rsn = localRsn();
        if (gid == null || rsn == null) return;
        executor.submit(() ->
        {
            try { apiClient.passBall(gid, rsn, targetRsn); }
            catch (Exception ex) { log.warn("Pass ball failed: {}", ex.getMessage()); }
        });
    }

    public void onClearBallClicked()
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.clearBall(gameId, writeKey); }
            catch (Exception ex) { log.warn("Clear ball failed: {}", ex.getMessage()); }
        });
    }

    public void onAssignBallClicked(String playerRsn)
    {
        if (!isHost() || gameId == null) return;
        executor.submit(() ->
        {
            try { apiClient.assignBall(gameId, writeKey, playerRsn); }
            catch (Exception ex) { log.warn("Assign ball failed: {}", ex.getMessage()); }
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
    public String        getBallHolder()              { return ballHolder; }
    public long          getInterceptionFlashUntil() { return interceptionFlashUntil; }
    public String        getInterceptionPlayer()     { return interceptionPlayer; }
    public String        getInterceptionTeam()       { return interceptionTeam; }

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
        timerPaused = false; pausedRemainingMs = 0; whistleFlashUntil = 0; ballHolder = null;
        interceptionFlashUntil = 0; interceptionPlayer = null; interceptionTeam = null;
        if (rosterReducer != null) rosterReducer.reset();
        if (tileReducer != null) tileReducer.reset();
        clientThread.invokeLater(this::clearActiveTagEffects);
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

    private static boolean safeBool(com.google.gson.JsonObject o, String key)
    {
        try { return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean(); }
        catch (Exception ignored) { return false; }
    }

    private static long parseEpochMs(String iso)
    {
        if (iso == null) return 0;
        try { return java.time.Instant.parse(iso).toEpochMilli(); }
        catch (Exception ignored) { return 0; }
    }
}