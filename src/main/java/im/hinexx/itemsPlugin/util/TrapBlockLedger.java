package im.hinexx.itemsPlugin.util;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Per-block stack of trap layers so nested default cages restore correctly.
 * <p>
 * Push before changing a block. Pop on trap expire:
 * <ul>
 *   <li>If this trap is on top → restore its saved state and pop</li>
 *   <li>If an inner trap is still on top → remove this layer and chain its
 *       restore target into the inner layer (so terrain still returns later)</li>
 * </ul>
 */
public final class TrapBlockLedger {

    private static final Map<String, ArrayDeque<Layer>> LAYERS = new ConcurrentHashMap<>();
    private static Logger logger;

    private TrapBlockLedger() {
    }

    public static void setLogger(Logger log) {
        logger = log;
    }

    public static void clearAll() {
        LAYERS.clear();
    }

    public static void push(World world, BlockVector3 pos, String trapId, BlockState beforeChange) {
        if (world == null || pos == null || trapId == null || beforeChange == null) {
            return;
        }
        LAYERS.computeIfAbsent(key(world, pos), k -> new ArrayDeque<>())
                .push(new Layer(trapId, beforeChange));
    }

    public static void popTrap(World world, String trapId, Collection<BlockVector3> positions) {
        if (world == null || trapId == null || positions == null || positions.isEmpty()) {
            return;
        }
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            for (BlockVector3 pos : positions) {
                applyPop(editSession, world, pos, trapId);
            }
        } catch (WorldEditException e) {
            if (logger != null) {
                logger.severe("TrapBlockLedger restore failed: " + e.getMessage());
            }
        }
    }

    private static void applyPop(EditSession editSession, World world, BlockVector3 pos, String trapId)
            throws WorldEditException {
        String k = key(world, pos);
        ArrayDeque<Layer> stack = LAYERS.get(k);
        if (stack == null || stack.isEmpty()) {
            return;
        }

        if (trapId.equals(stack.peek().trapId())) {
            Layer layer = stack.pop();
            if (!ProtectionHook.shouldSkipBlock(world, pos)) {
                editSession.setBlock(pos, layer.restoreTo());
            }
            if (stack.isEmpty()) {
                LAYERS.remove(k);
            }
            return;
        }

        // Outer trap expired while a newer (inner) layer still owns the block.
        // Remove our layer and make the layer above us restore to our target.
        List<Layer> list = new ArrayList<>(stack);
        // iterator/list order: index 0 = top (peek)
        int oursIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (trapId.equals(list.get(i).trapId())) {
                oursIdx = i;
                break;
            }
        }
        if (oursIdx < 0) {
            return;
        }

        Layer ours = list.get(oursIdx);
        if (oursIdx > 0) {
            Layer above = list.get(oursIdx - 1);
            list.set(oursIdx - 1, new Layer(above.trapId(), ours.restoreTo()));
        }
        list.remove(oursIdx);

        stack.clear();
        for (int i = list.size() - 1; i >= 0; i--) {
            stack.push(list.get(i));
        }
        if (stack.isEmpty()) {
            LAYERS.remove(k);
        }
    }

    private static String key(World world, BlockVector3 pos) {
        return world.getUID() + ":" + pos.x() + ":" + pos.y() + ":" + pos.z();
    }

    private record Layer(String trapId, BlockState restoreTo) {
    }
}
