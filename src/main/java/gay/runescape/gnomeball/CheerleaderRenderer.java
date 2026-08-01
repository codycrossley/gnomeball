package gay.runescape.gnomeball;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import lombok.extern.slf4j.Slf4j;

/** Spawns a decorative Gnome cheerleader (NPC 3158) -- a real 3D model, not a 2D overlay -- at
 * every tile the host has marked with the "CHEERLEADER_A" or "CHEERLEADER_B" tile type, recolored
 * to that team's color (matching {@link GnomeballConfig}'s existing COLOR_TEAM_A/COLOR_TEAM_B, not
 * any particular real-NPC variant -- there's no confirmed usable cache ID for the actual blue/red
 * cheerleaders that appeared in the 2021 Realm of Memories event, so this recolors the regular
 * Cheerleader ourselves instead of depending on that). Reuses the exact same generic mark-tile/
 * unmark-tile sync mechanism {@link TileReducer} already provides for field/zone tiles, so this
 * needs no new server endpoint or event type beyond the two tile-type strings themselves: it's
 * purely cosmetic, and every client already receives these tiles the same way it receives
 * FIELD/ZONE_A/ZONE_B.
 *
 * Unlike TileOverlay, the model itself isn't drawn via Graphics2D at all -- {@link RuneLiteObject}
 * registers directly into the client's own 3D scene graph, and the client's normal renderer draws
 * it every frame automatically once positioned and activated. The occasional speech text above
 * each cheerleader's head *is* Graphics2D though (see {@link CheerleaderSpeechOverlay}), since
 * RuneLiteObject has no overhead-text capability of its own the way a real Actor does -- this
 * class only tracks *what* each one is currently saying and until when; the overlay reads that
 * state and draws it.
 *
 * Animation mimics the real NPC's own behavior, observed live via temporary debug logging: it
 * originally cycled through a pool of 8 distinct cheer animations found that way (211-218), later
 * narrowed down to just the 2 that looked best after visually inspecting all 8 in-game (see
 * {@link #CHEER_ANIMATION_IDS}). Deliberately does NOT reproduce the real NPC's brief idle pauses
 * between moves -- tried that two different ways (a
 * plain unassigned animation, and a clean {@code setActive(false)}) and both reliably read as the
 * whole model disappearing rather than it calmly standing still, since a RuneLiteObject has no
 * equivalent of a real actor's implicit idle stance to fall back on. Looping a single pick
 * continuously for each cheerleader's whole lifetime sidesteps that entirely; variety comes from
 * different cheerleaders independently rolling different picks instead. */
@Slf4j
public class CheerleaderRenderer
{
    private static final int NPC_ID_CHEERLEADER = 3158; // "Cheerleader" -- the real Gnome ball minigame NPC
    private static final int[] CHEER_ANIMATION_IDS = {211, 218}; // narrowed down from the full 211-218 pool by visual inspection

    private static final int RGB_TEAM_A = 0x3C78DC; // matches GnomeballPlugin.COLOR_TEAM_A
    private static final int RGB_TEAM_B = 0xC83C3C; // matches GnomeballPlugin.COLOR_TEAM_B

    // Idle chatter: how often a cheerleader spontaneously shouts "Go <team>!" with no game event
    // behind it, purely to look alive. Game-tick cadence (600ms) is plenty precise for deciding
    // "start talking now" -- unlike the animation-completion problem earlier, this isn't racing a
    // fast native completion signal, so there's no equivalent polling-rate issue here.
    private static final int CHATTER_INTERVAL_MIN_MS = 15000;
    private static final int CHATTER_INTERVAL_MAX_MS = 27000;
    private static final long CHATTER_DURATION_MS = 2500;

    // %s is the cheerleader's own team name; templates without one just ignore the extra arg
    // (String.format tolerates unused trailing args).
    private static final String[] CHATTER_TEMPLATES = {
        "Go %s!",
        "Let's go team!",
        "You've got this, %s!",
        "Bring it home!",
        "We believe in you!",
        "Woo, go %s!",
        "Score one for %s!",
        "Go go go!",
    };

    private final Client client;
    private final ClientThread clientThread;
    private final GnomeballPlugin plugin;
    private final Map<String, CheerInstance> active = new HashMap<>();
    private final Map<Integer, Animation> animationCache = new HashMap<>();

    private Model cachedModelA;
    private Model cachedModelB;
    private boolean modelLoadFailed;

    public CheerleaderRenderer(Client client, ClientThread clientThread, GnomeballPlugin plugin)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.plugin = plugin;
    }

    /** Must be called on the client thread (game-tick handlers already are). Reconciles the
     * active cheerleaders against whatever "CHEERLEADER_A"/"CHEERLEADER_B" tiles are currently
     * marked, called every tick rather than only on change -- a RuneLiteObject's LocalPoint is
     * relative to the currently loaded scene, and goes stale as the player walks across region
     * boundaries, so position needs re-asserting continuously the same way TileOverlay recomputes
     * its own LocalPoint conversions fresh on every render rather than caching them. Also rolls
     * each cheerleader's idle-chatter timer forward (see {@link #updateChatter}). */
    public void sync(List<TileReducer.TileEntry> allTiles)
    {
        Map<String, TileReducer.TileEntry> wanted = new HashMap<>();
        for (TileReducer.TileEntry entry : allTiles)
        {
            if ("CHEERLEADER_A".equals(entry.tileType) || "CHEERLEADER_B".equals(entry.tileType))
            {
                wanted.put(key(entry.point), entry);
            }
        }

        Iterator<Map.Entry<String, CheerInstance>> it = active.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, CheerInstance> e = it.next();
            if (!wanted.containsKey(e.getKey()))
            {
                e.getValue().obj.setActive(false);
                it.remove();
            }
        }

        if (!resolveModels()) return; // NPC composition/model not loaded yet -- retry next tick

        for (Map.Entry<String, TileReducer.TileEntry> e : wanted.entrySet())
        {
            TileReducer.TileEntry entry = e.getValue();
            String team = entry.tileType;
            Model model = "CHEERLEADER_A".equals(team) ? cachedModelA : cachedModelB;

            CheerInstance inst = active.computeIfAbsent(e.getKey(), k ->
            {
                RuneLiteObject o = client.createRuneLiteObject();
                o.setModel(model);
                return new CheerInstance(o, team);
            });
            inst.point = entry.point;

            // computeIfAbsent's lambda only ever runs once per key, so if the animation resource
            // happened not to be loaded yet on that first tick, retry here on every subsequent
            // tick until it actually sticks, rather than leaving this cheerleader permanently
            // unanimated. A no-op once assignment has actually succeeded, since a looping
            // animation never clears itself back to null.
            if (inst.obj.getAnimation() == null) assignLoopingCheer(inst);

            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), entry.point);
            if (lp == null)
            {
                inst.obj.setActive(false);
                continue;
            }
            inst.obj.setLocation(lp, entry.point.getPlane());
            inst.obj.setActive(true);

            updateChatter(inst);
        }
    }

    /** Spontaneous chatter with no game event behind it, just to look alive -- a random pick from
     * {@link #CHATTER_TEMPLATES} each time, so it's not the same line on repeat. Independent per
     * cheerleader -- each one rolls its own next-chatter time -- so a group of them don't all
     * shout in unison. */
    private void updateChatter(CheerInstance inst)
    {
        long now = System.currentTimeMillis();
        if (now < inst.nextChatterAtMs) return;

        String teamName = "CHEERLEADER_A".equals(inst.team) ? plugin.getTeamAName() : plugin.getTeamBName();
        String template = CHATTER_TEMPLATES[ThreadLocalRandom.current().nextInt(CHATTER_TEMPLATES.length)];
        inst.speechText = String.format(template, teamName); // extra %s-less templates just ignore the arg
        inst.speechExpiresAtMs = now + CHATTER_DURATION_MS;
        inst.nextChatterAtMs = now + CHATTER_INTERVAL_MIN_MS
            + ThreadLocalRandom.current().nextInt(CHATTER_INTERVAL_MAX_MS - CHATTER_INTERVAL_MIN_MS);
    }

    /** Makes every cheerleader on the given team say something for a few seconds, overriding
     * whatever idle chatter they were (or weren't) already doing -- e.g. a "GOAL!" shout the
     * instant that team scores. {@code team} is "TEAM_A"/"TEAM_B" (matching the game event
     * payloads), translated internally to the "CHEERLEADER_A"/"CHEERLEADER_B" tile-type team each
     * cheerleader was placed as. */
    public void shout(String team, String text, long durationMs)
    {
        String cheerleaderTeam = "TEAM_A".equals(team) ? "CHEERLEADER_A" : "CHEERLEADER_B";
        long expiresAt = System.currentTimeMillis() + durationMs;
        for (CheerInstance inst : active.values())
        {
            if (!cheerleaderTeam.equals(inst.team)) continue;
            inst.speechText = text;
            inst.speechExpiresAtMs = expiresAt;
        }
    }

    /** Read by {@link CheerleaderSpeechOverlay} every frame -- whatever's currently being said,
     * for every cheerleader saying something right now. */
    public List<SpeechBubble> getActiveSpeechBubbles()
    {
        long now = System.currentTimeMillis();
        List<SpeechBubble> bubbles = new ArrayList<>();
        for (CheerInstance inst : active.values())
        {
            if (inst.speechText != null && now < inst.speechExpiresAtMs)
            {
                bubbles.add(new SpeechBubble(inst.point, inst.speechText));
            }
        }
        return bubbles;
    }

    public static final class SpeechBubble
    {
        public final WorldPoint point;
        public final String text;

        SpeechBubble(WorldPoint point, String text)
        {
            this.point = point;
            this.text = text;
        }
    }

    /** Picks one random cheer animation and loops it continuously for this cheerleader's whole
     * lifetime. Different cheerleaders get visual variety since each one's pick is independently
     * randomized. */
    private void assignLoopingCheer(CheerInstance inst)
    {
        int animId = CHEER_ANIMATION_IDS[ThreadLocalRandom.current().nextInt(CHEER_ANIMATION_IDS.length)];
        Animation anim = resolveAnimation(animId);
        if (anim == null) return; // not loaded yet -- caller will just get a static model this tick

        inst.obj.setAnimation(anim);
        inst.obj.setShouldLoop(true);
    }

    /** Deactivates and forgets every currently-spawned cheerleader -- called on disconnect/leave/
     * shutdown so nothing lingers in the scene once there's no more game state driving it. */
    public void clear()
    {
        // Callers include Swing button handlers (Leave Game) as well as client-thread event
        // subscribers -- RuneLiteObject.setActive() asserts client-thread ownership, so this must
        // dispatch there itself rather than trust the caller's thread.
        clientThread.invoke(() ->
        {
            for (CheerInstance inst : active.values()) inst.obj.setActive(false);
            active.clear();
        });
    }

    /** Builds the two team-recolored models (once, cached thereafter). This NPC's composition
     * defines no official recolor mapping at all ({@code getColorToReplace()}/
     * {@code getColorToReplaceWith()} are both null -- confirmed by logging them directly), so
     * there's no swappable slot to redirect via the normal {@code client.loadModel(id,
     * colorToReplace, colorToReplaceWith)} idiom. Instead this hue-shifts the raw mesh by hand
     * (see {@link #hueShift}) before lighting it into a final renderable {@link Model}. */
    private boolean resolveModels()
    {
        if ((cachedModelA != null && cachedModelB != null) || modelLoadFailed) return cachedModelA != null;
        try
        {
            NPCComposition comp = client.getNpcDefinition(NPC_ID_CHEERLEADER);
            if (comp == null) return false;
            int[] modelIds = comp.getModels();
            if (modelIds == null || modelIds.length == 0)
            {
                modelLoadFailed = true;
                return false;
            }

            cachedModelA = buildHueShiftedModel(modelIds, RGB_TEAM_A);
            cachedModelB = buildHueShiftedModel(modelIds, RGB_TEAM_B);
        }
        catch (Exception ex)
        {
            log.warn("Failed to build cheerleader models: {}", ex.getMessage());
            modelLoadFailed = true;
            return false;
        }
        return true;
    }

    /** Loads each of the NPC's model parts as raw (pre-lit) {@link ModelData}, hue-shifts every
     * distinct color actually present in that mesh to the target team hue (keeping each face's
     * own saturation/luminance, so shading/shadow detail is preserved), merges the parts, then
     * bakes the result into a final lit {@link Model}. This recolors the *entire* model -- skin
     * included, not just the outfit -- since there's no way to isolate "just the outfit" without
     * a defined swap slot to tell them apart. */
    private Model buildHueShiftedModel(int[] modelIds, int targetRgb)
    {
        int targetHue = JagexColor.unpackHue(JagexColor.rgbToHSL(targetRgb, 1.0));

        ModelData[] parts = new ModelData[modelIds.length];
        for (int i = 0; i < modelIds.length; i++)
        {
            parts[i] = hueShift(client.loadModelData(modelIds[i]), targetHue);
        }

        ModelData merged = parts.length == 1 ? parts[0] : client.mergeModels(parts);
        return merged.light();
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

    private Animation resolveAnimation(int animationId)
    {
        return animationCache.computeIfAbsent(animationId, id ->
        {
            try { return client.loadAnimation(id); }
            catch (Exception ex) { return null; }
        });
    }

    private static String key(WorldPoint wp)
    {
        return wp.getX() + ":" + wp.getY() + ":" + wp.getPlane();
    }

    private static final class CheerInstance
    {
        final RuneLiteObject obj;
        final String team; // "CHEERLEADER_A" or "CHEERLEADER_B"
        WorldPoint point;
        String speechText;
        long speechExpiresAtMs;
        long nextChatterAtMs;

        CheerInstance(RuneLiteObject obj, String team)
        {
            this.obj = obj;
            this.team = team;
            // Staggers each cheerleader's first spontaneous line so a group placed at once
            // doesn't all shout together.
            this.nextChatterAtMs = System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(CHATTER_INTERVAL_MAX_MS);
        }
    }
}
