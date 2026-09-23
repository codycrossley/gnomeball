package gay.runescape.gnomeball;

import java.awt.Shape;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.callback.ClientThread;

/** Renders every referee-placed {@link TileReducer#FLAG} tile as a floating, gently bobbing
 * Yellow bead, so players can see where a referee wants them to go, plus a one-shot spotanim the
 * moment a flag is placed (see {@link #playPlacedSpotanim}). Same "diff the wanted set against
 * what's spawned every tick" shape as {@link GoalpostRenderer}, plus real per-model clickbox
 * hit-testing (see {@link #hoveredFlag}) so a referee can right-click the bead itself to remove it
 * -- a RuneLiteObject has no native menu of its own.
 *
 * The bead is an item, not a raw model: ITEM_ID_FLAG resolves to its model via
 * ItemComposition#getInventoryModel(), then gets scaled up from its tiny inventory size (same as
 * Rune Party's RuneMatchRuneModel). A RuneLiteObject's Z is an absolute height in the same space
 * Perspective#getTileHeight returns, not a ground-relative offset, so hovering means offsetting
 * from the tile's real height (same as Rune Party's SandwichItemModel). */
public class FlagRenderer
{
    private static final int ITEM_ID_FLAG = ItemID.YELLOW_BEAD;

    // Mesh#scale takes 1/128ths, so 250 is ~2x the bead's natural inventory size.
    private static final int MODEL_SCALE = 250;

    // Base height the bead floats above the ground, plus how far the sine wave swings around that
    // base. Height increases downward in this coordinate space, so subtracting raises the bead.
    private static final int HOVER_HEIGHT = 60;
    private static final int HOVER_BOB_AMPLITUDE = 15;
    private static final double HOVER_BOB_PERIOD_MS = 1400.0;
    private static final int SPOTANIM_ID_FLAG = SpotanimID.OLM_PLAYERSWAP_2;

    // How long the spotanim lives, in ~20ms client cycles (not 600ms game ticks) -- same default
    // as Rune Party's SPOTANIM_DEFAULT_DURATION_CYCLES. A projectile's animation loops for as long
    // as it lives, so this should roughly match the chosen spotanim's own length.
    private static final int SPOTANIM_DURATION_CYCLES = 60;

    private final Client client;
    private final ClientThread clientThread;
    private final Map<WorldPoint, RuneLiteObject> objects = new HashMap<>();

    // Built once, the first time the bead's ModelData loads -- every flag shares this instance.
    private Model cachedModel;

    public FlagRenderer(Client client, ClientThread clientThread)
    {
        this.client = client;
        this.clientThread = clientThread;
    }

    /** Must be called on the client thread (game-tick handlers already are). Called every tick
     * rather than only on change -- a RuneLiteObject's LocalPoint goes stale across scene/region
     * boundaries, same reasoning as {@link GoalpostRenderer#sync}. */
    public void sync(List<TileReducer.TileEntry> allTiles)
    {
        Set<WorldPoint> wanted = new HashSet<>();
        for (TileReducer.TileEntry entry : allTiles)
        {
            if (TileReducer.FLAG.equals(entry.tileType)) wanted.add(entry.point);
        }

        Iterator<Map.Entry<WorldPoint, RuneLiteObject>> it = objects.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<WorldPoint, RuneLiteObject> e = it.next();
            if (!wanted.contains(e.getKey()))
            {
                e.getValue().setActive(false);
                it.remove();
            }
        }

        for (WorldPoint point : wanted)
        {
            RuneLiteObject obj = objects.computeIfAbsent(point, k -> client.createRuneLiteObject());
            if (obj.getModel() == null)
            {
                Model model = resolveModel();
                if (model != null) obj.setModel(model);
            }

            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
            if (lp == null)
            {
                obj.setActive(false);
                continue;
            }
            obj.setLocation(lp, point.getPlane());
            applyHover(point, obj, System.currentTimeMillis());
            if (obj.getModel() != null && !obj.isActive()) obj.setActive(true);
        }
    }

    /** Advances every bead's bob -- called every client tick (not just every 600ms game tick, like
     * {@link #sync}) so the motion is smooth. Client thread only. */
    public void animate()
    {
        long now = System.currentTimeMillis();
        for (Map.Entry<WorldPoint, RuneLiteObject> e : objects.entrySet())
        {
            applyHover(e.getKey(), e.getValue(), now);
        }
    }

    private void applyHover(WorldPoint point, RuneLiteObject obj, long now)
    {
        LocalPoint lp = obj.getLocation();
        if (lp == null) return;

        int groundHeight = Perspective.getTileHeight(client, lp, point.getPlane());
        // Per-point phase offset so neighbouring flags don't bob in lockstep.
        double phase = (now + point.hashCode() * 137L) / HOVER_BOB_PERIOD_MS;
        int bob = (int) Math.round(Math.sin(phase * 2 * Math.PI) * HOVER_BOB_AMPLITUDE);
        obj.setZ(groundHeight - HOVER_HEIGHT - bob);
    }

    /** Resolves item -> inventory model -> recolored, scaled Model, once. The item definition/
     * ModelData can be null for a few frames right after the client starts -- retried every tick
     * until it succeeds. cloneVertices() before scaling so the shared cached ModelData is never
     * mutated.
     *
     * Every bead color shares one inventory model; the item definition's own colorToReplace/
     * colorToReplaceWith pairs are what make it yellow. The game only applies those when it
     * renders the item through its own pipeline, never for a raw loadModelData(), so they're
     * reapplied by hand here (same reason as Rune Party's ArenaFireModel recolor). */
    private Model resolveModel()
    {
        if (cachedModel != null) return cachedModel;

        ItemComposition item = client.getItemDefinition(ITEM_ID_FLAG);
        if (item == null) return null;
        ModelData raw = client.loadModelData(item.getInventoryModel());
        if (raw == null) return null;

        ModelData data = raw.cloneVertices().cloneColors();
        short[] find = item.getColorToReplace();
        short[] replace = item.getColorToReplaceWith();
        if (find != null && replace != null)
        {
            for (int i = 0; i < Math.min(find.length, replace.length); i++)
            {
                data = data.recolor(find[i], replace[i]);
            }
        }

        cachedModel = data.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE).light();
        return cachedModel;
    }

    /** Plays the flag spotanim once at {@code point}. The client API has no "spawn a stationary
     * graphic" call, so this uses the standard trick of a projectile whose source and target are
     * the same point (same as Rune Party's triggerSpotAnimAtWorldPoint). Safe from any thread. */
    public void playPlacedSpotanim(WorldPoint point)
    {
        clientThread.invoke(() ->
        {
            int start = client.getGameCycle();
            client.createProjectile(SPOTANIM_ID_FLAG, point, 0, null, point, 0, null,
                start, start + SPOTANIM_DURATION_CYCLES, 0, 0);
        });
    }

    /** The flag whose bead's real screen-space clickbox is under {@code canvasPoint}, or null.
     * Uses the object's own current Z (see applyHover), so the clickbox follows the bob. Must be
     * called on the client thread. */
    public WorldPoint hoveredFlag(Point canvasPoint)
    {
        if (canvasPoint == null) return null;
        for (Map.Entry<WorldPoint, RuneLiteObject> e : objects.entrySet())
        {
            RuneLiteObject obj = e.getValue();
            if (!obj.isActive()) continue;

            Model model = obj.getModel();
            LocalPoint lp = obj.getLocation();
            if (model == null || lp == null) continue;

            Shape clickbox = Perspective.getClickbox(client, client.getTopLevelWorldView(), model,
                obj.getOrientation(), lp.getX(), lp.getY(), obj.getZ());
            if (clickbox != null && clickbox.contains(canvasPoint.getX(), canvasPoint.getY())) return e.getKey();
        }
        return null;
    }

    /** Despawns every flag bead -- called on disconnect/leave/shutdown. Safe from any thread
     * (Swing button handlers included), same as {@link GoalpostRenderer#clear}. */
    public void clear()
    {
        clientThread.invoke(() ->
        {
            for (RuneLiteObject obj : objects.values()) obj.setActive(false);
            objects.clear();
        });
    }
}
