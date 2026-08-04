package gay.runescape.gnomeball;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

/** Spawns a decorative team-colored goalpost -- a real 3D model, not a 2D overlay -- at every tile
 * the host has marked with the "GOALPOST_A" or "GOALPOST_B" tile type, recolored to that team's
 * color (matching {@link GnomeballConfig}'s existing COLOR_TEAM_A/COLOR_TEAM_B). Reuses the exact
 * same generic mark-tile/unmark-tile sync mechanism {@link TileReducer} already provides for
 * field/zone tiles, same as {@link CheerleaderRenderer}.
 *
 * Model id 2640 is the raw mesh backing object id 2393 (the in-game goalpost object) -- {@code
 * ObjectComposition} exposes no {@code getModels()}/recolor-slot accessors the way {@code
 * NPCComposition} does, so there's no supported runtime path from object id to model id; 2640 was
 * found via an external cache lookup and is loaded directly via {@code client.loadModelData(int)},
 * the same primitive CheerleaderRenderer uses under its own NPCComposition lookup, just without
 * that lookup in front of it. Unlike a Cheerleader, a goalpost never animates or talks, so this
 * omits all of that machinery -- just spawn, hue-shift once per team, and re-assert position every
 * tick (a RuneLiteObject's LocalPoint goes stale across scene/region boundaries, same reasoning as
 * {@link CheerleaderRenderer#sync}). */
public class GoalpostRenderer
{
    private static final int MODEL_ID_GOALPOST = 1317;

    // RuneLiteObjectController orientation is an unsigned Jagex Angle Unit -- 2048 per full turn
    // (0-2047), so 512 = 90 degrees. A negative value here is out of range and crashed the client,
    // so -90 degrees is expressed as 2048 - 512 = 1536 instead. The real orientation now travels
    // with the tile itself (see TileEntry.orientation, rotated alongside position in
    // FieldPreset#layout) so it rotates along with the field -- these are only a fallback for a
    // tile that somehow has no orientation recorded.
    private static final int DEFAULT_ORIENTATION_TEAM_A = 1536;
    private static final int DEFAULT_ORIENTATION_TEAM_B = 512;

    private static final int RGB_TEAM_A = 0x3C78DC; // matches GnomeballPlugin.COLOR_TEAM_A
    private static final int RGB_TEAM_B = 0xC83C3C; // matches GnomeballPlugin.COLOR_TEAM_B

    private final Client client;
    private final ClientThread clientThread;
    private final Map<String, RuneLiteObject> active = new HashMap<>();

    private Model cachedModelA;
    private Model cachedModelB;
    private boolean modelLoadFailed;

    public GoalpostRenderer(Client client, ClientThread clientThread)
    {
        this.client = client;
        this.clientThread = clientThread;
    }

    /** Must be called on the client thread (game-tick handlers already are). Reconciles the active
     * goalposts against whatever "GOALPOST_A"/"GOALPOST_B" tiles are currently marked, called every
     * tick rather than only on change -- same staleness reasoning as {@link CheerleaderRenderer#sync}. */
    public void sync(List<TileReducer.TileEntry> allTiles)
    {
        Map<String, TileReducer.TileEntry> wanted = new HashMap<>();
        for (TileReducer.TileEntry entry : allTiles)
        {
            if ("GOALPOST_A".equals(entry.tileType) || "GOALPOST_B".equals(entry.tileType))
            {
                wanted.put(key(entry.point), entry);
            }
        }

        Iterator<Map.Entry<String, RuneLiteObject>> it = active.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, RuneLiteObject> e = it.next();
            if (!wanted.containsKey(e.getKey()))
            {
                e.getValue().setActive(false);
                it.remove();
            }
        }

        if (!resolveModels()) return; // model not loaded yet -- retry next tick

        for (Map.Entry<String, TileReducer.TileEntry> e : wanted.entrySet())
        {
            TileReducer.TileEntry entry = e.getValue();
            boolean isTeamA = "GOALPOST_A".equals(entry.tileType);
            Model model = isTeamA ? cachedModelA : cachedModelB;
            int fallback = isTeamA ? DEFAULT_ORIENTATION_TEAM_A : DEFAULT_ORIENTATION_TEAM_B;
            int orientation = entry.orientation != null ? entry.orientation : fallback;

            RuneLiteObject obj = active.computeIfAbsent(e.getKey(), k ->
            {
                RuneLiteObject o = client.createRuneLiteObject();
                o.setModel(model);
                o.setOrientation(orientation);
                return o;
            });

            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), entry.point);
            if (lp == null)
            {
                obj.setActive(false);
                continue;
            }
            obj.setLocation(lp, entry.point.getPlane());
            obj.setActive(true);
        }
    }

    /** Deactivates and forgets every currently-spawned goalpost -- called on disconnect/leave/
     * shutdown so nothing lingers in the scene once there's no more game state driving it. */
    public void clear()
    {
        // Callers include Swing button handlers (Leave Game) as well as client-thread event
        // subscribers -- RuneLiteObject.setActive() asserts client-thread ownership, so this must
        // dispatch there itself rather than trust the caller's thread.
        clientThread.invoke(() ->
        {
            for (RuneLiteObject obj : active.values()) obj.setActive(false);
            active.clear();
        });
    }

    /** Builds the two team-recolored models (once, cached thereafter). Loads the raw mesh fresh
     * per team (rather than reusing one {@link ModelData} instance for both) since {@code
     * recolor()} mutates its face-color data -- reusing one instance across both builds would
     * compound A's shift into B's. No confirmed swap slot on this model to redirect via the normal
     * {@code client.loadModel(id, colorToFind, colorToReplace)} idiom, so this hue-shifts the raw
     * mesh by hand instead, same approach as {@link CheerleaderRenderer#buildHueShiftedModel}. */
    private boolean resolveModels()
    {
        if ((cachedModelA != null && cachedModelB != null) || modelLoadFailed) return cachedModelA != null;
        try
        {
            ModelData dataA = client.loadModelData(MODEL_ID_GOALPOST);
            ModelData dataB = client.loadModelData(MODEL_ID_GOALPOST);
            if (dataA == null || dataB == null) return false; // not loaded yet -- retry next tick

            cachedModelA = buildHueShiftedModel(dataA, RGB_TEAM_A);
            cachedModelB = buildHueShiftedModel(dataB, RGB_TEAM_B);
        }
        catch (Exception ignored)
        {
            modelLoadFailed = true;
            return false;
        }
        return true;
    }

    private Model buildHueShiftedModel(ModelData data, int targetRgb)
    {
        int targetHue = JagexColor.unpackHue(JagexColor.rgbToHSL(targetRgb, 1.0));
        return hueShift(data, targetHue).light();
    }

    private ModelData hueShift(ModelData data, int targetHue)
    {
        short[] faceColors = data.getFaceColors();
        if (faceColors == null) return data;

        Set<Short> distinct = new HashSet<>();
        for (short c : faceColors) distinct.add(c);

        ModelData result = data;
        for (short original : distinct)
        {
            int sat = JagexColor.unpackSaturation(original);
            int lum = JagexColor.unpackLuminance(original);
            short recolored = JagexColor.packHSL(targetHue, sat, lum);
            result = result.recolor(original, recolored);
        }
        return result;
    }

    private static String key(WorldPoint wp)
    {
        return wp.getX() + ":" + wp.getY() + ":" + wp.getPlane();
    }
}
