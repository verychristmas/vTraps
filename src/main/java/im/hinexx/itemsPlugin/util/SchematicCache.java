package im.hinexx.itemsPlugin.util;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class SchematicCache {

    public record BakedBlock(int dx, int dy, int dz, BaseBlock block) {
    }

    public record PasteResult(List<BlockVector3> placed, Map<BlockVector3, BlockState> originals) {
    }

    private final Logger logger;
    private final Map<String, Clipboard> clipboards = new ConcurrentHashMap<>();
    private final Map<String, Long> clipboardMtimes = new ConcurrentHashMap<>();
    private final Map<String, List<BakedBlock>> baked = new ConcurrentHashMap<>();

    public SchematicCache(Logger logger) {
        this.logger = logger;
    }

    public void clear() {
        clipboards.clear();
        clipboardMtimes.clear();
        baked.clear();
    }

    public Clipboard getClipboard(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String key = file.getAbsolutePath();
        long mtime = file.lastModified();
        Long cachedMtime = clipboardMtimes.get(key);
        Clipboard cached = clipboards.get(key);
        if (cached != null && cachedMtime != null && cachedMtime == mtime) {
            return cached;
        }

        Clipboard loaded = loadFromDisk(file);
        if (loaded != null) {
            clipboards.put(key, loaded);
            clipboardMtimes.put(key, mtime);
            // drop stale baked variants for this file
            baked.keySet().removeIf(k -> k.startsWith(key + "|"));
            int solids = countSolids(loaded);
            logger.info("Loaded schematic " + file.getName()
                    + " (" + solids + " solid blocks, origin=" + loaded.getOrigin() + ")");
            if (solids == 0) {
                logger.warning("Schematic " + file.getName() + " has 0 solid blocks — paste will do nothing. Re-save with //copy + //schem save");
            }
        }
        return loaded;
    }

    public List<BakedBlock> getBaked(File file, int rotationY) {
        if (file == null || !file.exists()) {
            return List.of();
        }
        // ensure clipboard (and mtime invalidation) loaded first
        if (getClipboard(file) == null) {
            return List.of();
        }
        int normalized = ((rotationY % 360) + 360) % 360;
        String key = file.getAbsolutePath() + "|" + normalized;
        return baked.computeIfAbsent(key, k -> bake(file, normalized));
    }

    public PasteResult paste(World world, Location center, File file, int rotationY, boolean ignoreAir) {
        // Prefer native WE paste for rotation 0 (correct origin). Rotated uses bake.
        if (rotationY % 360 == 0) {
            int changed = pasteNative(world, center, file, ignoreAir);
            return new PasteResult(List.of(), Map.of());
        }

        List<BakedBlock> blocks = getBaked(file, rotationY);
        if (blocks.isEmpty()) {
            logger.warning("Paste skipped — empty bake for " + (file == null ? "?" : file.getName()));
            return new PasteResult(List.of(), Map.of());
        }

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        List<BlockVector3> placed = new ArrayList<>(blocks.size());
        Map<BlockVector3, BlockState> originals = new HashMap<>();

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            for (BakedBlock bakedBlock : blocks) {
                if (ignoreAir && bakedBlock.block().getBlockType() == BlockTypes.AIR) {
                    continue;
                }
                BlockVector3 pos = BlockVector3.at(cx + bakedBlock.dx(), cy + bakedBlock.dy(), cz + bakedBlock.dz());
                if (ProtectionHook.shouldSkipBlock(world, pos)) {
                    continue;
                }
                originals.putIfAbsent(pos, editSession.getBlock(pos));

                if (bakedBlock.block().getBlockType() == BlockTypes.BARRIER) {
                    editSession.setBlock(pos, BlockTypes.AIR.getDefaultState());
                } else {
                    editSession.setBlock(pos, bakedBlock.block());
                    if (bakedBlock.block().getBlockType() != BlockTypes.AIR) {
                        placed.add(pos);
                    }
                }
            }
            logger.info("Pasted (baked) " + file.getName() + " @" + cx + "," + cy + "," + cz
                    + " solids=" + placed.size());
        } catch (WorldEditException e) {
            logger.severe("Schematic paste failed: " + e.getMessage());
        }

        return new PasteResult(placed, Map.copyOf(originals));
    }

    /**
     * WorldEdit-native paste — origin of schem goes to {@code center}.
     */
    public int pasteNative(World world, Location center, File file, boolean ignoreAir) {
        Clipboard clipboard = getClipboard(file);
        if (clipboard == null) {
            logger.warning("Cannot paste — file missing/unloadable: "
                    + (file == null ? "null" : file.getAbsolutePath()));
            return 0;
        }
        BlockVector3 to = BlockVector3.at(center.getBlockX(), center.getBlockY(), center.getBlockZ());
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            ClipboardHolder holder = new ClipboardHolder(clipboard);
            Operation operation = holder
                    .createPaste(editSession)
                    .to(to)
                    .ignoreAirBlocks(ignoreAir)
                    .copyEntities(false)
                    .copyBiomes(false)
                    .build();
            Operations.complete(operation);
            int changed = editSession.getBlockChangeCount();
            logger.info("Pasted " + file.getName() + " @" + to
                    + " changes=" + changed
                    + " solidsInSchem=" + countSolids(clipboard));
            if (changed == 0) {
                logger.warning("Paste of " + file.getName() + " changed 0 blocks. Check //copy origin and schem contents.");
            }
            return changed;
        } catch (WorldEditException e) {
            logger.severe("Schematic paste failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Paste trap schem into dig: center XZ on dig, top of schem under roof ({@code groundY - 1}).
     * BARRIER / LIGHT markers → air (legacy explode_trap.schem used barriers as voids).
     */
    public int pasteTrapAligned(World world, int digCx, int digCz, int groundY, File file) {
        Clipboard clipboard = getClipboard(file);
        if (clipboard == null) {
            logger.warning("Cannot paste trap schem — missing: "
                    + (file == null ? "null" : file.getAbsolutePath()));
            return 0;
        }

        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        int schemMidX = (min.x() + max.x()) / 2;
        int schemMidZ = (min.z() + max.z()) / 2;
        int schemTopY = max.y();
        int alignTopY = groundY - 1; // под камуфляжной крышей

        // worldPos = pasteOrigin + (blockPos - clipboardOrigin)
        int pasteOx = digCx - (schemMidX - origin.x());
        int pasteOz = digCz - (schemMidZ - origin.z());
        int pasteOy = alignTopY - (schemTopY - origin.y());

        int placed = 0;
        int skippedMarkers = 0;
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            for (BlockVector3 pos : clipboard.getRegion()) {
                BaseBlock block = clipboard.getFullBlock(pos);
                var type = block.getBlockType();
                if (type == BlockTypes.AIR) {
                    continue;
                }
                // Legacy markers in old explode_trap.schem
                if (type == BlockTypes.BARRIER
                        || type == BlockTypes.LIGHT
                        || type == BlockTypes.STRUCTURE_VOID) {
                    skippedMarkers++;
                    continue;
                }

                BlockVector3 relative = pos.subtract(origin);
                BlockVector3 at = BlockVector3.at(
                        pasteOx + relative.x(),
                        pasteOy + relative.y(),
                        pasteOz + relative.z()
                );
                if (ProtectionHook.shouldSkipBlock(world, at)) {
                    skippedMarkers++;
                    continue;
                }
                editSession.setBlock(at, block);
                placed++;
            }
        } catch (WorldEditException e) {
            logger.severe("Trap schem paste failed: " + e.getMessage());
            return 0;
        }

        logger.info("Pasted trap " + file.getName()
                + " digCenter=" + digCx + "," + groundY + "," + digCz
                + " pasteOrigin=" + pasteOx + "," + pasteOy + "," + pasteOz
                + " placed=" + placed
                + " skippedMarkers=" + skippedMarkers
                + " schemSize=" + (max.x() - min.x() + 1) + "x" + (max.y() - min.y() + 1) + "x" + (max.z() - min.z() + 1));
        if (placed == 0) {
            logger.warning("Trap schem " + file.getName() + " placed 0 blocks (only markers/air?). Re-save your build.");
        }
        return placed;
    }

    public void restore(World world, Map<BlockVector3, BlockState> originals) {
        if (originals == null || originals.isEmpty()) {
            return;
        }
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            for (Map.Entry<BlockVector3, BlockState> entry : originals.entrySet()) {
                if (ProtectionHook.shouldSkipBlock(world, entry.getKey())) {
                    continue;
                }
                editSession.setBlock(entry.getKey(), entry.getValue());
            }
        } catch (WorldEditException e) {
            logger.severe("Schematic restore failed: " + e.getMessage());
        }
    }

    public void pasteIgnoreAir(World world, Location center, File file) {
        pasteNative(world, center, file, true);
    }

    /**
     * Surface cage: center schem mid-XZ on player, bottom on {@code groundY}.
     * Clears full AABB, registers each block in {@link TrapBlockLedger}, returns solids to animate.
     */
    public CagePrep prepareCenteredCage(World world, int playerX, int groundY, int playerZ, File file, String trapId) {
        Clipboard clipboard = getClipboard(file);
        if (clipboard == null || world == null || trapId == null) {
            return CagePrep.empty();
        }

        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        int schemMidX = (min.x() + max.x()) / 2;
        int schemMidZ = (min.z() + max.z()) / 2;
        int schemMinY = min.y();

        int pasteOx = playerX - (schemMidX - origin.x());
        int pasteOz = playerZ - (schemMidZ - origin.z());
        int pasteOy = groundY - (schemMinY - origin.y());

        int wMinX = pasteOx + (min.x() - origin.x());
        int wMinY = pasteOy + (min.y() - origin.y());
        int wMinZ = pasteOz + (min.z() - origin.z());
        int wMaxX = pasteOx + (max.x() - origin.x());
        int wMaxY = pasteOy + (max.y() - origin.y());
        int wMaxZ = pasteOz + (max.z() - origin.z());

        Map<BlockVector3, BlockState> originals = new HashMap<>();
        List<AnimBlock> solids = new ArrayList<>();
        var air = BlockTypes.AIR.getDefaultState();

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);

            for (int x = wMinX; x <= wMaxX; x++) {
                for (int y = wMinY; y <= wMaxY; y++) {
                    for (int z = wMinZ; z <= wMaxZ; z++) {
                        BlockVector3 at = BlockVector3.at(x, y, z);
                        if (ProtectionHook.shouldSkipBlock(world, at)) {
                            continue;
                        }
                        BlockState before = editSession.getBlock(at);
                        originals.putIfAbsent(at, before);
                        TrapBlockLedger.push(world, at, trapId, before);
                        editSession.setBlock(at, air);
                    }
                }
            }

            for (BlockVector3 pos : clipboard.getRegion()) {
                BaseBlock block = clipboard.getFullBlock(pos);
                var type = block.getBlockType();
                if (type == BlockTypes.AIR
                        || type == BlockTypes.BARRIER
                        || type == BlockTypes.LIGHT
                        || type == BlockTypes.STRUCTURE_VOID) {
                    continue;
                }
                BlockVector3 relative = pos.subtract(origin);
                BlockVector3 at = BlockVector3.at(
                        pasteOx + relative.x(),
                        pasteOy + relative.y(),
                        pasteOz + relative.z()
                );
                if (ProtectionHook.shouldSkipBlock(world, at)) {
                    continue;
                }
                solids.add(new AnimBlock(at, block));
            }
        } catch (WorldEditException e) {
            logger.severe("Centered cage prep failed: " + e.getMessage());
            return CagePrep.empty();
        }

        solids.sort((a, b) -> {
            int y = Integer.compare(a.at().y(), b.at().y());
            if (y != 0) {
                return y;
            }
            long da = dist2(a.at(), playerX, playerZ);
            long db = dist2(b.at(), playerX, playerZ);
            int d = Long.compare(da, db);
            if (d != 0) {
                return d;
            }
            int x = Integer.compare(a.at().x(), b.at().x());
            return x != 0 ? x : Integer.compare(a.at().z(), b.at().z());
        });

        logger.info("Default cage " + file.getName()
                + " center=" + playerX + "," + groundY + "," + playerZ
                + " pasteOrigin=" + pasteOx + "," + pasteOy + "," + pasteOz
                + " volume=" + (wMaxX - wMinX + 1) + "x" + (wMaxY - wMinY + 1) + "x" + (wMaxZ - wMinZ + 1)
                + " solids=" + solids.size());

        return new CagePrep(solids, originals, playerX, groundY + 1, playerZ);
    }

    public record CagePrep(
            List<AnimBlock> solids,
            Map<BlockVector3, BlockState> originals,
            int standX,
            int standY,
            int standZ
    ) {
        static CagePrep empty() {
            return new CagePrep(List.of(), Map.of(), 0, 0, 0);
        }

        public boolean isEmpty() {
            return solids == null || solids.isEmpty();
        }
    }

    /**
     * Blocks for origin-at-{@code center} animated paste (bottom→top, center-out).
     * Fills {@code originals} with world state before any change.
     */
    public List<AnimBlock> prepareAnimatedPaste(World world, Location center, File file,
                                                Map<BlockVector3, BlockState> originals) {
        Clipboard clipboard = getClipboard(file);
        if (clipboard == null || world == null || center == null) {
            return List.of();
        }
        BlockVector3 origin = clipboard.getOrigin();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        List<AnimBlock> list = new ArrayList<>();

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            for (BlockVector3 pos : clipboard.getRegion()) {
                BaseBlock block = clipboard.getFullBlock(pos);
                var type = block.getBlockType();
                if (type == BlockTypes.AIR
                        || type == BlockTypes.BARRIER
                        || type == BlockTypes.LIGHT
                        || type == BlockTypes.STRUCTURE_VOID) {
                    continue;
                }
                BlockVector3 relative = pos.subtract(origin);
                BlockVector3 at = BlockVector3.at(cx + relative.x(), cy + relative.y(), cz + relative.z());
                originals.putIfAbsent(at, editSession.getBlock(at));
                list.add(new AnimBlock(at, block));
            }
        }

        list.sort((a, b) -> {
            int y = Integer.compare(a.at().y(), b.at().y());
            if (y != 0) {
                return y;
            }
            long da = dist2(a.at(), cx, cz);
            long db = dist2(b.at(), cx, cz);
            int d = Long.compare(da, db);
            if (d != 0) {
                return d;
            }
            int x = Integer.compare(a.at().x(), b.at().x());
            return x != 0 ? x : Integer.compare(a.at().z(), b.at().z());
        });
        return list;
    }

    /** Place up to {@code limit} blocks starting at {@code fromIndex}; returns next index. */
    public int placeAnimBatch(World world, List<AnimBlock> blocks, int fromIndex, int limit) {
        if (world == null || blocks == null || fromIndex >= blocks.size() || limit <= 0) {
            return fromIndex;
        }
        int end = Math.min(blocks.size(), fromIndex + limit);
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            editSession.setFastMode(true);
            for (int i = fromIndex; i < end; i++) {
                AnimBlock b = blocks.get(i);
                if (ProtectionHook.shouldSkipBlock(world, b.at())) {
                    continue;
                }
                editSession.setBlock(b.at(), b.block());
            }
        } catch (WorldEditException e) {
            logger.severe("Animated paste batch failed: " + e.getMessage());
        }
        return end;
    }

    private static long dist2(BlockVector3 at, int cx, int cz) {
        long dx = at.x() - cx;
        long dz = at.z() - cz;
        return dx * dx + dz * dz;
    }

    public record AnimBlock(BlockVector3 at, BaseBlock block) {
    }

    private List<BakedBlock> bake(File file, int rotationY) {
        Clipboard clipboard = getClipboard(file);
        if (clipboard == null) {
            return List.of();
        }

        BlockVector3 origin = clipboard.getOrigin();
        boolean rotate = rotationY != 0;
        AffineTransform transform = rotate ? new AffineTransform().rotateY(rotationY) : null;
        List<BakedBlock> list = new ArrayList<>();

        for (BlockVector3 pos : clipboard.getRegion()) {
            BaseBlock block = clipboard.getFullBlock(pos);
            if (block.getBlockType() == BlockTypes.AIR) {
                continue;
            }
            BlockVector3 relative = pos.subtract(origin);
            if (rotate) {
                relative = transform.apply(relative.toVector3()).toBlockPoint();
            }
            list.add(new BakedBlock(relative.x(), relative.y(), relative.z(), block));
        }
        return List.copyOf(list);
    }

    private static int countSolids(Clipboard clipboard) {
        int n = 0;
        for (BlockVector3 pos : clipboard.getRegion()) {
            if (clipboard.getBlock(pos).getBlockType() != BlockTypes.AIR) {
                n++;
            }
        }
        return n;
    }

    private Clipboard loadFromDisk(File file) {
        try {
            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                logger.warning("Unsupported schematic: " + file.getName());
                return null;
            }
            try (ClipboardReader reader = format.getReader(new BufferedInputStream(new FileInputStream(file)))) {
                return reader.read();
            }
        } catch (IOException e) {
            logger.warning("Failed to load schematic " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
