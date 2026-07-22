package gay.runescape.gnomeball;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Tile;
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
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
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
    private static final String KEY_CUSTOM_FIELD_SLOTS = "customFieldSlots";
    private static final String KEY_HOSTED_GAMES = "hostedGameKeys";
    private static final int CUSTOM_SLOT_COUNT = 3;
    private static final int MAX_REMEMBERED_HOST_GAMES = 5;

    private static final int    GNOMEBALL_ITEM_ID = 2528;
    private static final int    PEACEFUL_HANDEGG_ITEM_ID = 9470; // F2P-accessible substitute for the Gnomeball
    private static final String COLOR_REFEREE = "3CB34A";
    private static final String COLOR_TEAM_A  = "3C78DC";
    private static final String COLOR_TEAM_B  = "C83C3C";

    // Tag effect — STUNNED spotanim, played directly on the tagged player
    private static final int    TAG_SPOTANIM_ID = 80;
    private static final int    ITEM_RUBBER_CHICKEN = 4566;
    private static final int    ITEM_STALE_BAGUETTE = 20590;
    private static final int    ITEM_BEACH_BOXING_GLOVES_YELLOW = 11705;
    private static final int    ITEM_BEACH_BOXING_GLOVES_PINK   = 11706;
    private static final long   TAG_IMMUNITY_MS = 1200; // 2 game ticks @ 600ms each

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private GnomeballConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private ModelOutlineRenderer modelOutlineRenderer;
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

    // Throttles roster refetches triggered by PLAYER_JOINED/ROLE_ASSIGNED/PLAYER_LEFT events.
    // A poll batch can contain several such events at once, and at scale (many clients, players
    // joining in a burst) each client re-fetching the full roster per individual event multiplies
    // load on the server well beyond what's needed — a leading+trailing throttle collapses any
    // burst within the window into at most one immediate fetch plus one trailing catch-up fetch.
    private static final long ROSTER_REFRESH_THROTTLE_MS = 2000;
    private volatile long lastRosterFetchMs = 0;
    private volatile ScheduledFuture<?> pendingRosterRefresh = null;

    // ---- game state ----
    private volatile String gameId   = null;
    private volatile String writeKey = null;

    // Write keys for games this account has hosted, keyed by gameId. Kept separate from the
    // active-session config (KEY_WRITE_KEY et al.) so that leaving a game — which clears the
    // active session — doesn't strand the host without a way to reclaim host privileges if they
    // rejoin the same still-live game later. Bounded so it doesn't grow across a long history of
    // hosted games.
    private final Map<String, String> hostedGameKeys = new LinkedHashMap<String, String>()
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest)
        {
            return size() > MAX_REMEMBERED_HOST_GAMES;
        }
    };
    private volatile String joinCode = null;
    private volatile String hostRsn  = null;
    private volatile GamePhase phase = GamePhase.DISCONNECTED;
    private volatile long deadlineMs  = 0;
    private volatile String winner   = null;
    private volatile String teamAName = "Team A";
    private volatile String teamBName = "Team B";
    private volatile int teamAScore = 0;
    private volatile int teamBScore = 0;
    private volatile boolean presetPlacementMode = false;
    private volatile boolean presetRemovalMode = false;
    private volatile FieldPreset selectedPreset = null;
    private volatile int presetRotationSteps = 0; // quarter-turns clockwise: 0/1/2/3 = 0/90/180/270 degrees
    private final FieldPreset[] customSlots = new FieldPreset[CUSTOM_SLOT_COUNT]; // null = empty slot
    private volatile String zoneTeam = null; // "TEAM_A" or "TEAM_B"
    private final Set<WorldPoint> zoneTiles = new HashSet<>();
    private volatile WorldPoint lastSelfPosition = null;
    private volatile String ballHolder   = null;
    private volatile String tagObligationTagger = null;
    private volatile String tagImmunePlayer = null;
    private volatile long   tagImmuneUntil = 0;
    private volatile boolean obligationActive = false;
    private volatile String obligationTeam = null; // "TEAM_A" or "TEAM_B" — the team that owes the pending delivery
    private volatile String obligationKind = null; // "GOAL" or "OUT_OF_BOUNDS" — governs who can fulfill it
    private volatile long   interceptionFlashUntil = 0;
    private volatile String interceptionPlayer     = null;
    private volatile String interceptionTeam       = null;
    private volatile long   outOfBoundsFlashUntil  = 0;
    private volatile long goalFlashUntil = 0;
    private volatile String goalFlashTeam = null;
    private volatile int goalFlashOldScore = 0;
    private volatile int goalFlashNewScore = 0;
    private volatile long whistleFlashUntil = 0;
    private volatile String hostMessageText = null;
    private volatile long hostMessageFlashUntil = 0;
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
        loadCustomFieldSlots();
        loadHostedGameKeys();

        panel = new GnomeballPanel(this);
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Gnomeball")
            .icon(icon)
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        playerOverlay = new PlayerOverlay(client, config, this, rosterReducer, modelOutlineRenderer);
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
            timerPaused = false; pausedRemainingMs = 0; whistleFlashUntil = 0; ballHolder = null;
            tagObligationTagger = null;
            tagImmunePlayer = null; tagImmuneUntil = 0;
            obligationActive = false; obligationTeam = null; obligationKind = null;
            goalFlashUntil = 0; goalFlashTeam = null; goalFlashOldScore = 0; goalFlashNewScore = 0;
            interceptionFlashUntil = 0; interceptionPlayer = null; interceptionTeam = null;
            outOfBoundsFlashUntil = 0;
            hostMessageText = null; hostMessageFlashUntil = 0;
            if (rosterReducer != null) rosterReducer.reset();
            if (tileReducer != null) tileReducer.reset();
            SwingUtilities.invokeLater(() -> panel.refresh());
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        filterOffRosterPlayerEntry(event);
        colorizeRosterPlayerEntry(event);

        if (!isHost()) return;
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return;

        if ("Walk here".equals(event.getOption()))
        {
            if (presetPlacementMode || presetRemovalMode)
            {
                addPresetMenuEntries();
                return;
            }
            if (zoneTeam != null)
            {
                addZoneMenuEntries();
                return;
            }
            addTileMenuEntries(event);
            return;
        }

        if (!"Follow".equals(event.getOption())) return;
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;
        Player followTarget = (Player) event.getMenuEntry().getActor();
        String followTargetRsn = followTarget.getName() != null ? Text.toJagexName(followTarget.getName()) : null;

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

    /** While enlisted on a team during an active game, strips any menu entry targeting another
     * player who isn't part of the game (unlisted, or an OBSERVER) — e.g. "Follow", "Trade with",
     * or "Use Gnomeball ->" on a random bystander — so a team player can't accidentally interact
     * with (and, in particular, pass the ball to) someone who was never in the roster. NPCs,
     * objects, and ground items are untouched; only entries whose actor is a Player are inspected. */
    private void filterOffRosterPlayerEntry(MenuEntryAdded event)
    {
        if (phase != GamePhase.ACTIVE) return;

        String localRsn = localRsn();
        if (localRsn == null) return;
        GnomeballRole myRole = rosterReducer.getRole(localRsn);
        if (myRole != GnomeballRole.TEAM_A && myRole != GnomeballRole.TEAM_B) return;

        MenuEntry entry = event.getMenuEntry();
        if (!(entry.getActor() instanceof Player)) return;
        Player target = (Player) entry.getActor();
        if (target == null || target == client.getLocalPlayer() || target.getName() == null) return;

        String targetRsn = Text.toJagexName(target.getName());
        if (targetRsn == null || targetRsn.isBlank()) return;

        GnomeballRole targetRole = rosterReducer.getRole(targetRsn);
        if (targetRole == GnomeballRole.TEAM_A || targetRole == GnomeballRole.TEAM_B || targetRole == GnomeballRole.REFEREE) return;

        client.getMenu().removeMenuEntry(entry);
    }

    /** Recolors a player-targeted menu entry's target text to match that player's team/referee
     * color, so e.g. "Follow" or "Trade with" on an enlisted player reads in team colors instead
     * of the client's default (usually white/friend-list) color. Applies during LOBBY and ACTIVE,
     * regardless of the local player's own role — this is purely a legibility aid, not a
     * restriction, so hosts/observers/referees see the coloring too. */
    private void colorizeRosterPlayerEntry(MenuEntryAdded event)
    {
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return;

        MenuEntry entry = event.getMenuEntry();
        if (!(entry.getActor() instanceof Player)) return;
        Player target = (Player) entry.getActor();
        if (target == null || target.getName() == null) return;

        String targetRsn = Text.toJagexName(target.getName());
        if (targetRsn == null || targetRsn.isBlank()) return;

        String colorHex = roleColorHex(rosterReducer.getRole(targetRsn));
        if (colorHex == null) return;

        String plainTarget = Text.removeTags(entry.getTarget());
        entry.setTarget("<col=" + colorHex + ">" + plainTarget + "</col>");
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Snapshot our own position once per tick so a same-tick attack can still be matched
        // against where we were a moment ago, even if we've since stepped away.
        lastSelfPosition = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;

        if (phase != GamePhase.ACTIVE || timerPaused || ballHolder == null) return;

        // Scoring/turnovers are disabled until the pending obligation is fulfilled
        if (obligationActive) return;

        String localRsn = localRsn();
        if (localRsn == null || !ballHolder.equalsIgnoreCase(localRsn)) return;

        GnomeballRole myRole = rosterReducer.getRole(localRsn);
        if (myRole != GnomeballRole.TEAM_A && myRole != GnomeballRole.TEAM_B) return;

        if (client.getLocalPlayer() == null) return;
        WorldPoint pos = client.getLocalPlayer().getWorldLocation();
        String scoringTeam = myRole == GnomeballRole.TEAM_A ? "TEAM_A" : "TEAM_B";

        String zoneType = myRole == GnomeballRole.TEAM_A ? "ZONE_A" : "ZONE_B";
        if (tileReducer.hasMarker(pos, zoneType))
        {
            onZoneScore(scoringTeam);
            return;
        }

        // Only enforced once the host has actually marked out a field — an unmarked field
        // has no "outside" to step out of.
        if (tileReducer.hasFieldTiles() && !tileReducer.isWithinField(pos))
        {
            onOutOfBounds(scoringTeam);
        }
    }

    private void onZoneScore(String scoringTeam)
    {
        int oldScore = "TEAM_A".equals(scoringTeam) ? teamAScore : teamBScore;
        int newScore = oldScore + 1;

        // Optimistic flash preview only — the actual teamAScore/teamBScore increment happens
        // exclusively in the GOAL_SCORED event handler (below), once the server echoes this
        // goal back over the poll. GOAL_SCORED is a delta, so applying it here too would
        // double-count on the scorer's own client once that echo arrives.
        goalFlashTeam     = scoringTeam;
        goalFlashOldScore = oldScore;
        goalFlashNewScore = newScore;
        goalFlashUntil    = System.currentTimeMillis() + 3000;
        obligationActive = true;
        obligationTeam = scoringTeam;
        obligationKind = "GOAL";
        String deliveryTarget = rosterReducer.countRole(GnomeballRole.REFEREE) == 0
            ? "a member of the opposing team"
            : "a referee";
        addChatMessage("You scored! Please pass the Gnomeball to " + deliveryTarget + ".");
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

    private void onOutOfBounds(String offendingTeam)
    {
        obligationActive = true;
        obligationTeam = offendingTeam;
        obligationKind = "OUT_OF_BOUNDS";
        outOfBoundsFlashUntil = System.currentTimeMillis() + 3000;
        String opposingTeamName = "TEAM_A".equals(offendingTeam) ? teamBName : teamAName;
        addChatMessage("You've stepped out of bounds! Please pass the Gnomeball to " + opposingTeamName + ".");
        SwingUtilities.invokeLater(() -> panel.refresh());

        final String gid = gameId;
        final String rsn = localRsn();
        if (gid == null || rsn == null) return;
        executor.submit(() ->
        {
            try { apiClient.outOfBounds(gid, rsn); }
            catch (Exception ex) { log.warn("Out of bounds report failed: {}", ex.getMessage()); }
        });
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (phase != GamePhase.ACTIVE) return;

        if (event.getMenuEntry().getActor() instanceof Player)
        {
            log.debug("Player-targeted menu click: action={} option={} target={}",
                event.getMenuAction(), event.getMenuOption(), event.getMenuTarget());
        }

        if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_PLAYER) return;

        String localRsn = localRsn();
        if (localRsn == null) return;

        // Must currently hold the ball
        if (ballHolder == null || !ballHolder.equalsIgnoreCase(localRsn))
        {
            log.debug("Pass blocked: not ball holder (ballHolder={}, self={})", ballHolder, localRsn);
            return;
        }

        // Must be an enlisted team player
        GnomeballRole myRole = rosterReducer.getRole(localRsn);
        if (myRole != GnomeballRole.TEAM_A && myRole != GnomeballRole.TEAM_B)
        {
            log.debug("Pass blocked: sender role is {}", myRole);
            return;
        }

        // Must be throwing a gnomeball (or the F2P-friendly Peaceful handegg)
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return;
        Item item = inv.getItem(event.getParam0());
        if (item == null)
        {
            log.debug("Pass blocked: no item at inventory slot {}", event.getParam0());
            return;
        }
        int itemId = item.getId();
        if (itemId != GNOMEBALL_ITEM_ID && itemId != PEACEFUL_HANDEGG_ITEM_ID)
        {
            log.debug("Pass blocked: item id {} is not a gnomeball/handegg", itemId);
            return;
        }

        // Target must have a free weapon slot
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;
        Player target = (Player) event.getMenuEntry().getActor();
        if (target == null || target.getName() == null) return;

        PlayerComposition comp = target.getPlayerComposition();
        if (comp == null) return;
        int[] equipIds = comp.getEquipmentIds();
        if (equipIds == null || equipIds[KitType.WEAPON.getIndex()] != 0)
        {
            log.debug("Pass blocked: target weapon slot not free (raw id={})",
                equipIds != null ? equipIds[KitType.WEAPON.getIndex()] : "null-array");
            return;
        }

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

        // Brief immunity after receiving the ball back from a fulfilled tag
        if (tagImmunePlayer != null && tagImmunePlayer.equalsIgnoreCase(selfRsn) && System.currentTimeMillis() < tagImmuneUntil) return;

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

        // Must be wielding a tagging-eligible item (Rubber chicken / Stale baguette / Beach boxing gloves)
        PlayerComposition comp = attacker.getPlayerComposition();
        if (comp == null) return;
        int[] equipIds = comp.getEquipmentIds();
        if (equipIds == null) return;
        int weaponSlotId = equipIds[KitType.WEAPON.getIndex()];
        if (weaponSlotId < PlayerComposition.ITEM_OFFSET) return;
        int weaponId = weaponSlotId - PlayerComposition.ITEM_OFFSET;
        if (weaponId != ITEM_RUBBER_CHICKEN && weaponId != ITEM_STALE_BAGUETTE
            && weaponId != ITEM_BEACH_BOXING_GLOVES_YELLOW && weaponId != ITEM_BEACH_BOXING_GLOVES_PINK) return;

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

        String zoneType = "TEAM_A".equals(zoneTeam) ? "ZONE_A" : "ZONE_B";
        Set<WorldPoint> tiles = new HashSet<>(zoneTiles);
        cancelZoneMode();

        executor.submit(() -> markZoneTiles(tiles, zoneType));
    }

    /** Marks every tile in an arbitrary tile set as the given zone type. Must be called from a
     * background thread. Zone tiles render filled with their own type-based color — no separate
     * boundary marking is needed. */
    private void markZoneTiles(Set<WorldPoint> tiles, String zoneType)
    {
        for (WorldPoint wp : tiles)
        {
            try { apiClient.markTile(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(), zoneType, null); }
            catch (Exception ex) { log.warn("Zone tile mark failed at {},{}: {}", wp.getX(), wp.getY(), ex.getMessage()); }
        }
    }

    private void addPresetMenuEntries()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint center = tile.getWorldLocation();
        if (center == null) return;
        FieldPreset preset = selectedPreset;
        if (preset == null) return;

        client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelPresetMode());

        client.createMenuEntry(-1)
            .setOption("Rotate Field")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> rotatePresetNext());

        int degrees = presetRotationSteps * 90;
        String suffix = degrees != 0 ? " (" + degrees + "°)" : "";
        if (presetRemovalMode)
        {
            client.createMenuEntry(-1)
                .setOption("<col=FF4444>Remove " + preset.name + suffix + "</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> removePreset(center));
        }
        else
        {
            client.createMenuEntry(-1)
                .setOption("<col=00FF00>Place " + preset.name + suffix + "</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> commitPreset(center));
        }
    }

    private void commitPreset(WorldPoint center)
    {
        FieldPreset preset = selectedPreset;
        int rotationSteps = presetRotationSteps;
        cancelPresetMode();
        if (!isHost() || gameId == null || preset == null) return;

        List<FieldPreset.PlacedTile> placedTiles = preset.layout(center, rotationSteps);

        executor.submit(() ->
        {
            for (FieldPreset.PlacedTile pt : placedTiles)
            {
                try { apiClient.markTile(gameId, writeKey, pt.point.getX(), pt.point.getY(), pt.point.getPlane(), pt.tileType, pt.color); }
                catch (Exception ex) { log.warn("Commit preset tile failed at {},{}: {}", pt.point.getX(), pt.point.getY(), ex.getMessage()); }
            }
        });
    }

    private void removePreset(WorldPoint center)
    {
        FieldPreset preset = selectedPreset;
        int rotationSteps = presetRotationSteps;
        cancelPresetMode();
        if (!isHost() || gameId == null || preset == null) return;

        // Clear every type at each covered position (not just the preset's own declared type) —
        // e.g. removing a FIELD-only Custom Grid should also strip any ZONE_A/ZONE_B a host
        // placed on top of it, matching "wipe this footprint clean" rather than "surgically undo
        // only what this exact preset would have placed." Dedup positions since a tile can carry
        // more than one type (e.g. Standard Field's FIELD+ZONE_A coexisting).
        Set<WorldPoint> uniquePoints = new HashSet<>();
        for (FieldPreset.PlacedTile pt : preset.layout(center, rotationSteps)) uniquePoints.add(pt.point);

        executor.submit(() ->
        {
            for (WorldPoint wp : uniquePoints)
            {
                try { apiClient.unmarkTile(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(), null); }
                catch (Exception ex) { log.warn("Remove preset tile failed at {},{}: {}", wp.getX(), wp.getY(), ex.getMessage()); }
            }
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
            .setOption("<col=" + COLOR_TEAM_A + ">Zone A</col>")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "ZONE_A"));
        subMenu.createMenuEntry(-1)
            .setOption("<col=" + COLOR_TEAM_B + ">Zone B</col>")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "ZONE_B"));
        subMenu.createMenuEntry(-1)
            .setOption("Field")
            .setTarget("").setType(MenuAction.RUNELITE)
            .onClick(me -> toggleTile(wp, "FIELD"));
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
                // Absolute score set — host correction (scoreboard +/- buttons) only.
                // Never implies a goal was scored, so it does not arm the obligation.
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
            case "GOAL_SCORED":
            {
                // Real zone-goal — delta increment that also arms the obligation to
                // deliver the ball to a referee before scoring can resume.
                String team = safeStr(e.payload, "team");
                if ("TEAM_A".equals(team))
                {
                    goalFlashTeam = "TEAM_A";
                    goalFlashOldScore = teamAScore;
                    goalFlashNewScore = ++teamAScore;
                    goalFlashUntil = System.currentTimeMillis() + 3000;
                }
                else if ("TEAM_B".equals(team))
                {
                    goalFlashTeam = "TEAM_B";
                    goalFlashOldScore = teamBScore;
                    goalFlashNewScore = ++teamBScore;
                    goalFlashUntil = System.currentTimeMillis() + 3000;
                }
                obligationActive = true;
                obligationTeam = team;
                obligationKind = "GOAL";
                break;
            }
            case "OUT_OF_BOUNDS":
            {
                // The offending team must deliver the ball to any member of the opposing
                // team before scoring can resume — a referee does not fulfill this one.
                obligationActive = true;
                obligationTeam = safeStr(e.payload, "team");
                obligationKind = "OUT_OF_BOUNDS";
                outOfBoundsFlashUntil = System.currentTimeMillis() + 3000;
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
            case "HOST_MESSAGE":
            {
                String message = safeStr(e.payload, "message");
                if (message != null && !message.isBlank())
                {
                    hostMessageText = message;
                    hostMessageFlashUntil = System.currentTimeMillis() + 5000;
                }
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
                boolean fulfillsTagObligation = tagObligationTagger != null && tagObligationTagger.equalsIgnoreCase(newHolder);

                // While an obligation is pending, a cross-team pass is expected — it's the required
                // delivery (opposing team fulfilling a goal with no referee, or an out-of-bounds
                // turnover), not a steal — so it shouldn't flash as an interception.
                if (newHolder != null && ballHolder != null && !manualAssign && !fulfillsTagObligation && !obligationActive)
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
                if (fulfillsTagObligation)
                {
                    tagObligationTagger = null;
                    tagImmunePlayer = newHolder;
                    tagImmuneUntil = System.currentTimeMillis() + TAG_IMMUNITY_MS;
                }
                if (obligationActive && newHolder != null)
                {
                    GnomeballRole newHolderRole = rosterReducer.getRole(newHolder);
                    boolean fulfillsObligation;
                    if ("OUT_OF_BOUNDS".equals(obligationKind))
                    {
                        // Strictly a team-to-team turnover — a referee never fulfills this one.
                        GnomeballRole opposingRole = "TEAM_A".equals(obligationTeam) ? GnomeballRole.TEAM_B : GnomeballRole.TEAM_A;
                        fulfillsObligation = newHolderRole == opposingRole;
                    }
                    else
                    {
                        fulfillsObligation = newHolderRole == GnomeballRole.REFEREE;
                        if (!fulfillsObligation && rosterReducer.countRole(GnomeballRole.REFEREE) == 0 && obligationTeam != null)
                        {
                            // No referee currently in the game — fall back to requiring delivery
                            // to a member of the opposing team instead.
                            GnomeballRole opposingRole = "TEAM_A".equals(obligationTeam) ? GnomeballRole.TEAM_B : GnomeballRole.TEAM_A;
                            fulfillsObligation = newHolderRole == opposingRole;
                        }
                    }
                    if (fulfillsObligation)
                    {
                        obligationActive = false;
                        obligationTeam = null;
                        obligationKind = null;
                    }
                }
                ballHolder = newHolder;
                break;
            }
            case "BALL_CLEARED":
            {
                // No longer auto-fired after a goal — this is now purely the host's manual "Clear Ball"
                // action, which doubles as an override to release a stuck obligation (e.g. a rogue
                // player who won't return the ball to the required target).
                ballHolder = null;
                tagObligationTagger = null;
                obligationActive = false;
                obligationTeam = null;
                obligationKind = null;
                break;
            }
            case "PLAYER_TAGGED":
            {
                String tagger = safeStr(e.payload, "tagger");
                String target = safeStr(e.payload, "target");
                if (target != null)
                {
                    tagObligationTagger = tagger;
                    final String finalTagger = tagger;
                    final String finalTarget = target;
                    clientThread.invokeLater(() -> onPlayerTagged(finalTagger, finalTarget));
                }
                break;
            }
            case "PLAYER_JOINED":
            case "ROLE_ASSIGNED":
            case "PLAYER_LEFT":
                requestRosterRefresh();
                break;
        }

        SwingUtilities.invokeLater(() -> panel.refresh());
    }

    // -------------------------------------------------------------------------
    // Tag effect
    // -------------------------------------------------------------------------

    /** Must be called on the client thread. */
    private void onPlayerTagged(String tagger, String target)
    {
        spawnTagEffect(target);

        String selfRsn = localRsn();
        if (tagger == null || selfRsn == null || !target.equalsIgnoreCase(selfRsn)) return;

        String taggerNumber = rosterReducer.getNumber(tagger);
        String label = (taggerNumber != null && !taggerNumber.isEmpty()) ? tagger + " (" + taggerNumber + ")" : tagger;
        String colorHex = roleColorHex(rosterReducer.getRole(tagger));
        if (colorHex != null) label = "<col=" + colorHex + ">" + label + "</col>";
        addChatMessage("You've been tagged! You must pass the Gnomeball to " + label + ".");
    }

    private static String roleColorHex(GnomeballRole role)
    {
        if (role == null) return null;
        switch (role)
        {
            case TEAM_A:   return COLOR_TEAM_A;
            case TEAM_B:   return COLOR_TEAM_B;
            case REFEREE:  return COLOR_REFEREE;
            default:       return null;
        }
    }

    /** Plays the STUNNED spotanim on a named player. Must be called on the client thread. */
    private void spawnTagEffect(String rsn)
    {
        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            if (!rsn.equalsIgnoreCase(Text.toJagexName(p.getName()))) continue;
            p.createSpotAnim(0, TAG_SPOTANIM_ID, 0, 0);
            return;
        }
    }

    /** Throttled entry point for roster refreshes triggered by roster-affecting events — prefer
     * this over calling {@link #refreshRosterNow()} directly from an event handler. Fetches
     * immediately if the throttle window has elapsed since the last fetch; otherwise schedules a
     * single trailing fetch for when the window does elapse (a repeat call while one's already
     * pending is a no-op), so a burst of join/leave/role events collapses into at most two
     * roster fetches total instead of one per event. */
    private void requestRosterRefresh()
    {
        long elapsed = System.currentTimeMillis() - lastRosterFetchMs;
        if (elapsed >= ROSTER_REFRESH_THROTTLE_MS)
        {
            refreshRosterNow();
            return;
        }
        if (pendingRosterRefresh != null && !pendingRosterRefresh.isDone()) return;
        pendingRosterRefresh = heartbeatScheduler.schedule(
            this::refreshRosterNow, ROSTER_REFRESH_THROTTLE_MS - elapsed, TimeUnit.MILLISECONDS);
    }

    private void refreshRosterNow()
    {
        lastRosterFetchMs = System.currentTimeMillis();
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
        if (snap.obligationActive != null)
        {
            obligationActive = snap.obligationActive;
            obligationTeam = snap.obligationActive ? snap.obligationTeam : null;
            obligationKind = snap.obligationActive ? snap.obligationKind : null;
        }

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
                rememberHostKey(gameId, writeKey);
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
                // Rejoining a game this account previously hosted (e.g. after clicking "Leave")
                // restores host privileges from the cached write key instead of leaving us stuck
                // as a regular player.
                writeKey = (hostRsn != null && hostRsn.equalsIgnoreCase(rsn)) ? hostedGameKeys.get(gameId) : null;
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

    public void onBroadcastMessageClicked(String message)
    {
        if (!isHost() || gameId == null) return;
        if (message == null) return;
        final String trimmed = message.trim();
        if (trimmed.isEmpty()) return;
        executor.submit(() ->
        {
            try { apiClient.broadcastMessage(gameId, writeKey, trimmed); }
            catch (Exception ex) { log.warn("Broadcast message failed: {}", ex.getMessage()); }
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

    /** Removes every currently marked field/zone tile. A tile can carry more than one type at once
     * (e.g. a STANDARD tile a host manually overlaid on a FIELD tile), so this unmarks by unique
     * (x,y,plane) with a null tileType, which the server treats as "remove everything here" —
     * one call per tile instead of one per (tile, type). */
    public void onClearArenaClicked()
    {
        if (!isHost() || gameId == null) return;
        List<TileReducer.TileEntry> snapshot = tileReducer.snapshot();
        if (snapshot.isEmpty())
        {
            addChatMessage("No field tiles to clear.");
            return;
        }

        Set<WorldPoint> uniquePoints = new HashSet<>();
        for (TileReducer.TileEntry e : snapshot) uniquePoints.add(e.point);

        executor.submit(() ->
        {
            for (WorldPoint wp : uniquePoints)
            {
                try { apiClient.unmarkTile(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(), null); }
                catch (Exception ex) { log.warn("Clear arena tile failed at {},{}: {}", wp.getX(), wp.getY(), ex.getMessage()); }
            }
        });
        addChatMessage("Clearing current arena.");
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
    public boolean       isPresetPlacementMode() { return presetPlacementMode; }
    public boolean       isPresetRemovalMode()   { return presetRemovalMode; }
    public FieldPreset   getSelectedPreset()     { return selectedPreset; }
    public TileReducer   getTileReducer() { return tileReducer; }

    public void startPresetPlacement(FieldPreset preset)
    {
        if (preset == null || preset.isEmpty()) return;
        cancelZoneMode();
        selectedPreset = preset;
        presetPlacementMode = true;
        presetRemovalMode = false;
        presetRotationSteps = 0;
    }

    public void startPresetRemoval(FieldPreset preset)
    {
        if (preset == null || preset.isEmpty()) return;
        cancelZoneMode();
        selectedPreset = preset;
        presetRemovalMode = true;
        presetPlacementMode = false;
        presetRotationSteps = 0;
    }

    public void cancelPresetMode()
    {
        presetPlacementMode = false;
        presetRemovalMode = false;
        selectedPreset = null;
        presetRotationSteps = 0;
    }

    public int getPresetRotationSteps() { return presetRotationSteps; }

    public void rotatePresetNext()
    {
        presetRotationSteps = (presetRotationSteps + 1) % 4;
    }

    public static int getCustomSlotCount() { return CUSTOM_SLOT_COUNT; }

    public FieldPreset getCustomSlot(int index)
    {
        return (index >= 0 && index < CUSTOM_SLOT_COUNT) ? customSlots[index] : null;
    }

    public void saveCurrentFieldToCustomSlot(int index)
    {
        if (index < 0 || index >= CUSTOM_SLOT_COUNT) return;
        List<TileReducer.TileEntry> snapshot = tileReducer.snapshot();
        if (snapshot.isEmpty())
        {
            addChatMessage("No field tiles to save.");
            return;
        }
        boolean wasEmpty = customSlots[index] == null;
        customSlots[index] = FieldPreset.fromTiles("Custom Slot " + (index + 1), snapshot);
        persistCustomFieldSlots();
        String message = wasEmpty
            ? "Custom Slot " + (index + 1) + " renamed — no longer empty."
            : "Saved current field to Custom Slot " + (index + 1) + ".";
        addChatMessage(message);
        SwingUtilities.invokeLater(() -> panel.refresh());
    }

    /** Queues a game chat message on the client thread. Safe to call from any thread (e.g. a
     * Swing button listener on the EDT) — RuneLite's Client asserts client-thread ownership for
     * any call that touches game state, addChatMessage included. */
    private void addChatMessage(String message)
    {
        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
    }

    private void loadCustomFieldSlots()
    {
        try
        {
            String json = configManager.getConfiguration(CONFIG_GROUP, KEY_CUSTOM_FIELD_SLOTS);
            if (json == null || json.isBlank()) return;
            Type type = new TypeToken<List<List<FieldPreset.RelativeTile>>>() {}.getType();
            List<List<FieldPreset.RelativeTile>> raw = gson.fromJson(json, type);
            if (raw == null) return;
            for (int i = 0; i < Math.min(raw.size(), CUSTOM_SLOT_COUNT); i++)
            {
                List<FieldPreset.RelativeTile> tiles = raw.get(i);
                if (tiles != null && !tiles.isEmpty())
                {
                    customSlots[i] = new FieldPreset("Custom Slot " + (i + 1), tiles);
                }
            }
        }
        catch (Exception ex) { log.warn("Failed to load custom field slots: {}", ex.getMessage()); }
    }

    private void persistCustomFieldSlots()
    {
        List<List<FieldPreset.RelativeTile>> raw = new ArrayList<>();
        for (FieldPreset preset : customSlots)
        {
            raw.add(preset != null ? preset.tiles : List.of());
        }
        configManager.setConfiguration(CONFIG_GROUP, KEY_CUSTOM_FIELD_SLOTS, gson.toJson(raw));
    }

    private void loadHostedGameKeys()
    {
        try
        {
            String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_HOSTED_GAMES, String.class);
            if (json == null || json.isBlank()) return;
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> saved = gson.fromJson(json, type);
            if (saved != null) hostedGameKeys.putAll(saved);
        }
        catch (Exception ex) { log.warn("Failed to load hosted game keys: {}", ex.getMessage()); }
    }

    private void rememberHostKey(String gid, String key)
    {
        if (gid == null || key == null) return;
        hostedGameKeys.put(gid, key);
        configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_HOSTED_GAMES, gson.toJson(hostedGameKeys));
    }

    public String        getZoneTeam()     { return zoneTeam; }
    public Set<WorldPoint> getZoneTiles()  { return zoneTiles; }
    public boolean       isZoneMode()      { return zoneTeam != null; }

    public long          getGoalFlashUntil()    { return goalFlashUntil; }
    public String        getGoalFlashTeam()     { return goalFlashTeam; }
    public int           getGoalFlashOldScore() { return goalFlashOldScore; }
    public int           getGoalFlashNewScore() { return goalFlashNewScore; }
    public long          getWhistleFlashUntil() { return whistleFlashUntil; }
    public String        getHostMessageText()      { return hostMessageText; }
    public long          getHostMessageFlashUntil() { return hostMessageFlashUntil; }
    public boolean       isTimerPaused()        { return timerPaused; }
    public long          getPausedRemainingMs() { return pausedRemainingMs; }
    public String        getBallHolder()              { return ballHolder; }
    public String        getTagObligationTagger()     { return tagObligationTagger; }
    public boolean        isObligationActive()        { return obligationActive; }
    public String         getObligationTeam()         { return obligationTeam; }
    public String         getObligationKind()         { return obligationKind; }
    public long          getInterceptionFlashUntil() { return interceptionFlashUntil; }
    public String        getInterceptionPlayer()     { return interceptionPlayer; }
    public String        getInterceptionTeam()       { return interceptionTeam; }
    public long          getOutOfBoundsFlashUntil()  { return outOfBoundsFlashUntil; }

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
        cancelPresetMode();
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
        if (pendingRosterRefresh != null) { pendingRosterRefresh.cancel(false); pendingRosterRefresh = null; }
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
        tagObligationTagger = null;
        tagImmunePlayer = null; tagImmuneUntil = 0;
        obligationActive = false; obligationTeam = null; obligationKind = null;
        interceptionFlashUntil = 0; interceptionPlayer = null; interceptionTeam = null;
        outOfBoundsFlashUntil = 0;
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