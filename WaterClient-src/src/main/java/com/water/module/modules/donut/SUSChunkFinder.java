package com.water.module.modules.donut;

import com.water.module.Category;
import com.water.module.Module;
import com.water.module.setting.Setting;
import com.water.render.FontRenderer;
import com.water.render.ProjectedPoint;
import com.water.render.Render3D;
import com.water.render.RenderUtils;
import com.water.render.ShapeBatch;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.opengl.GL11;

/**
 * Combined Sus Chunk Finder (Amethyst + Chest/Base).
 * Optimized for low lag: progressive scan, section.hasAny early-out, session cache.
 */
public final class SUSChunkFinder extends Module {
   public static SUSChunkFinder INSTANCE;

   public final Setting<Integer> scanRadius = new Setting<>("Scan Radius", 2, 1, 6);
   public final Setting<Boolean> detectAmethyst = new Setting<>("Amethyst", true);
   public final Setting<Boolean> detectChest = new Setting<>("Chest / Base", true);
   public final Setting<Integer> minAmethyst = new Setting<>("Min Amethyst", 5, 1, 40);
   public final Setting<Integer> minChestScore = new Setting<>("Min Chest Score", 8, 1, 50);
   public final Setting<Integer> chestMaxY = new Setting<>("Chest Max Y", -5, -64, 64);
   public final Setting<Integer> displayY = new Setting<>("Display Y", 64, -64, 320);
   public final Setting<Integer> maxShow = new Setting<>("Max Show", 12, 1, 30);
   public final Setting<Boolean> sessionCache = new Setting<>("Session Cache", true);
   public final Setting<Boolean> playerBoost = new Setting<>("Player Boost", true);
   public final Setting<Color> amethystColor = new Setting<>("Amethyst Color", new Color(160, 60, 220, 55));
   public final Setting<Color> chestColor = new Setting<>("Chest Color", new Color(255, 80, 40, 55));
   public final Setting<Integer> fillAlpha = new Setting<>("Alpha", 55, 0, 255);
   public final Setting<Boolean> showInfo = new Setting<>("Show List", true);
   public final Setting<Boolean> showOutline = new Setting<>("Outline", true);
   public final Setting<Boolean> clearCache = new Setting<>("Clear Cache", false);

   public static final class AmHit {
      public final int count, percent;
      public AmHit(int count) {
         this.count = count;
         this.percent = Math.min(100, Math.max(0, Math.round(count * 1.25f)));
      }
   }

   public volatile Map<ChunkPos, AmHit> amethystHits = Collections.emptyMap();
   public volatile Map<ChunkPos, SusCache.ChestEntry> chestHits = Collections.emptyMap();

   /** Last stable screen positions for 3D labels — prevents flicker when projection fails a frame */
   public static final class LabelPos {
      public float x, y;
      public String line1, line2;
      public int color;
      public long lastOkMs;
   }
   public final Map<ChunkPos, LabelPos> labelCache = new java.util.concurrent.ConcurrentHashMap<>();
   private final ProjectedPoint projected = new ProjectedPoint();

   private ExecutorService scanExec;
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private int tickCount = 0;
   private int scanCursor = 0;
   private List<ChunkPos> lastPositions = Collections.emptyList();
   private List<WorldChunk> lastChunks = Collections.emptyList();

   public SUSChunkFinder() {
      super("Sus Chunk Finder", Category.DONUT);
      INSTANCE = this;
      this.addSetting(this.scanRadius);
      this.addSetting(this.detectAmethyst);
      this.addSetting(this.detectChest);
      this.addSetting(this.minAmethyst);
      this.addSetting(this.minChestScore);
      this.addSetting(this.chestMaxY);
      this.addSetting(this.displayY);
      this.addSetting(this.maxShow);
      this.addSetting(this.sessionCache);
      this.addSetting(this.playerBoost);
      this.addSetting(this.amethystColor);
      this.addSetting(this.chestColor);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.showInfo);
      this.addSetting(this.showOutline);
      this.addSetting(this.clearCache);
   }

   @Override
   public void onEnable() {
      this.tickCount = 0;
      this.scanCursor = 0;
   }

   @Override
   public void onDisable() {
      this.scanning.set(false);
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
         this.scanExec = null;
      }
   }

   @Override
   public void onTick() {
      if (mc.world == null || mc.player == null) return;

      if (this.clearCache.getValue()) {
         SusCache.CHESTS.clear();
         this.chestHits = Collections.emptyMap();
         this.clearCache.setValue(false);
      }

      // Player boost into chest cache
      if (this.playerBoost.getValue() && this.detectChest.getValue()
            && PlayerDebug.INSTANCE != null && PlayerDebug.INSTANCE.isEnabled()) {
         for (Map.Entry<ChunkPos, PlayerDebug.Hit> e : PlayerDebug.INSTANCE.stableHits.entrySet()) {
            SusCache.ChestEntry ce = SusCache.CHESTS.computeIfAbsent(e.getKey(), k -> new SusCache.ChestEntry());
            if (ce.percent < 15) {
               ce.percent = 15;
               ce.tag = "P:" + e.getValue().name;
               ce.lastSeenMs = System.currentTimeMillis();
               ce.live = true;
            }
         }
      }

      // Rebuild chestHits view from cache every tick (cheap)
      if (this.detectChest.getValue()) {
         this.chestHits = filterChestView();
      } else {
         this.chestHits = Collections.emptyMap();
      }

      if (++this.tickCount % 5 != 0) return; // progressive every 5 ticks
      if (!this.detectAmethyst.getValue() && !this.detectChest.getValue()) return;
      if (!this.scanning.compareAndSet(false, true)) return;

      if (this.scanExec == null || this.scanExec.isShutdown()) {
         this.scanExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sus-chunk-scan");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
         });
      }

      final ChunkPos origin = mc.player.getChunkPos();
      final int radius = this.scanRadius.getValue() * 4; // softer than *5
      final boolean doAm = this.detectAmethyst.getValue();
      final boolean doChest = this.detectChest.getValue();
      final int minAm = this.minAmethyst.getValue();
      final int minScore = this.minChestScore.getValue();
      final int maxY = this.chestMaxY.getValue();
      final int maxShow = this.maxShow.getValue();

      // Snapshot chunk list on main thread
      final List<ChunkPos> positions = new ArrayList<>();
      final List<WorldChunk> chunks = new ArrayList<>();
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            ChunkPos cp = new ChunkPos(origin.x + dx, origin.z + dz);
            WorldChunk wc = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
            if (wc != null && !wc.isEmpty()) {
               positions.add(cp);
               chunks.add(wc);
            }
         }
      }

      // Progressive: only process up to N chunks per job to avoid lag spikes
      final int batchSize = 12;
      final int start = this.scanCursor % Math.max(1, positions.size());
      this.scanCursor = start + batchSize;

      this.scanExec.submit(() -> {
         try {
            Map<ChunkPos, AmHit> amMap = doAm ? new HashMap<>(this.amethystHits) : Collections.emptyMap();
            Set<ChunkPos> liveChest = new HashSet<>();

            int end = Math.min(positions.size(), start + batchSize);
            // if wrapped, also process from 0
            List<Integer> indices = new ArrayList<>();
            for (int i = start; i < end; i++) indices.add(i);
            if (end > positions.size()) {
               // shouldn't happen with min
            }
            if (start + batchSize > positions.size()) {
               for (int i = 0; i < Math.min(batchSize - (positions.size() - start), positions.size()); i++) {
                  indices.add(i);
               }
            }

            for (int idx : indices) {
               if (idx < 0 || idx >= positions.size()) continue;
               ChunkPos cp = positions.get(idx);
               WorldChunk wc = chunks.get(idx);

               if (doAm) {
                  int c = countAmethystFast(wc);
                  if (c >= minAm) amMap.put(cp, new AmHit(c));
                  else amMap.remove(cp);
               }

               if (doChest) {
                  int[] r = scanChestFast(wc, maxY);
                  int score = r[0] * 12 + r[1] * 2 + r[2] * 30;
                  if (score >= minScore) {
                     SusCache.putChest(cp, r[0], r[1], r[2]);
                     liveChest.add(cp);
                  }
               }
            }

            if (doAm) {
               // prune far / keep top
               amMap.entrySet().removeIf(e ->
                  Math.abs(e.getKey().x - origin.x) > radius + 2
                  || Math.abs(e.getKey().z - origin.z) > radius + 2);
               this.amethystHits = trimTopAm(amMap, origin, maxShow);
            }
            if (doChest) {
               SusCache.markChestStaleOutside(liveChest);
            }
         } catch (Throwable ignored) {
         } finally {
            this.scanning.set(false);
         }
      });
   }

   private Map<ChunkPos, SusCache.ChestEntry> filterChestView() {
      Map<ChunkPos, SusCache.ChestEntry> out = new HashMap<>();
      int threshold = this.minChestScore.getValue();
      boolean cache = this.sessionCache.getValue();
      ChunkPos origin = mc.player != null ? mc.player.getChunkPos() : new ChunkPos(0, 0);
      int radius = this.scanRadius.getValue() * 4 + 4;
      for (Map.Entry<ChunkPos, SusCache.ChestEntry> e : SusCache.CHESTS.entrySet()) {
         SusCache.ChestEntry ce = e.getValue();
         if (ce.percent < threshold) continue;
         if (!cache && !ce.live) continue;
         ChunkPos cp = e.getKey();
         if (Math.abs(cp.x - origin.x) > radius || Math.abs(cp.z - origin.z) > radius) continue;
         out.put(cp, ce);
      }
      return out;
   }

   private static Map<ChunkPos, AmHit> trimTopAm(Map<ChunkPos, AmHit> map, ChunkPos origin, int limit) {
      ArrayList<Map.Entry<ChunkPos, AmHit>> list = new ArrayList<>(map.entrySet());
      list.sort((a, b) -> {
         int c = Integer.compare(b.getValue().count, a.getValue().count);
         if (c != 0) return c;
         int da = (a.getKey().x - origin.x) * (a.getKey().x - origin.x)
                + (a.getKey().z - origin.z) * (a.getKey().z - origin.z);
         int db = (b.getKey().x - origin.x) * (b.getKey().x - origin.x)
                + (b.getKey().z - origin.z) * (b.getKey().z - origin.z);
         return Integer.compare(da, db);
      });
      Map<ChunkPos, AmHit> out = new HashMap<>();
      for (int i = 0; i < list.size() && i < limit; i++) {
         out.put(list.get(i).getKey(), list.get(i).getValue());
      }
      return Collections.unmodifiableMap(out);
   }

   /** Fast amethyst count — skip empty sections, early hasAny */
   private static int countAmethystFast(WorldChunk chunk) {
      int count = 0;
      ChunkSection[] sections = chunk.getSectionArray();
      int bottom = chunk.getBottomY();
      for (int s = 0; s < sections.length; s++) {
         if (bottom + s * 16 > 32) break;
         ChunkSection section = sections[s];
         if (section == null || section.isEmpty()) continue;
         if (!section.hasAny(SUSChunkFinder::isAmethyst)) continue;
         for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
               for (int z = 0; z < 16; z++)
                  if (isAmethyst(section.getBlockState(x, y, z))) count++;
      }
      return count;
   }

   private static boolean isAmethyst(BlockState state) {
      return state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.AMETHYST_BLOCK)
         || state.isOf(Blocks.BUDDING_AMETHYST)
         || state.isOf(Blocks.SMALL_AMETHYST_BUD)
         || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
         || state.isOf(Blocks.LARGE_AMETHYST_BUD);
   }

   private static int[] scanChestFast(WorldChunk chunk, int maxY) {
      int chests = 0, redstone = 0, spawners = 0;
      try {
         for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            if (e.getKey().getY() > maxY) continue;
            BlockEntity be = e.getValue();
            if (be instanceof MobSpawnerBlockEntity) spawners++;
            else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                  || be instanceof HopperBlockEntity || be instanceof ShulkerBoxBlockEntity
                  || be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity) {
               chests++;
            }
         }
      } catch (Throwable ignored) {}

      // Light block scan only if BE found nothing and sections exist below maxY
      if (chests + spawners == 0) {
         try {
            ChunkSection[] sections = chunk.getSectionArray();
            int bottom = chunk.getBottomY();
            for (int s = 0; s < sections.length; s++) {
               int sectionY = bottom + s * 16;
               if (sectionY > maxY) break;
               ChunkSection section = sections[s];
               if (section == null || section.isEmpty()) continue;
               if (!section.hasAny(st -> isStorage(st) || st.isOf(Blocks.SPAWNER) || isRedstone(st))) continue;
               int localMax = Math.min(15, maxY - sectionY);
               if (localMax < 0) continue;
               for (int x = 0; x < 16; x++)
                  for (int z = 0; z < 16; z++)
                     for (int y = 0; y <= localMax; y++) {
                        BlockState st = section.getBlockState(x, y, z);
                        if (st.isOf(Blocks.SPAWNER)) spawners++;
                        else if (isStorage(st)) chests++;
                        else if (isRedstone(st)) redstone++;
                     }
            }
         } catch (Throwable ignored) {}
      }
      return new int[]{chests, redstone, spawners};
   }

   private static boolean isStorage(BlockState st) {
      return st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST)
         || st.isOf(Blocks.BARREL) || st.isOf(Blocks.HOPPER)
         || st.isOf(Blocks.DROPPER) || st.isOf(Blocks.DISPENSER)
         || st.getBlock() instanceof ShulkerBoxBlock;
   }

   private static boolean isRedstone(BlockState st) {
      return st.isOf(Blocks.REDSTONE_WIRE) || st.isOf(Blocks.REPEATER) || st.isOf(Blocks.COMPARATOR)
         || st.isOf(Blocks.REDSTONE_TORCH) || st.isOf(Blocks.REDSTONE_WALL_TORCH) || st.isOf(Blocks.REDSTONE_BLOCK)
         || st.isOf(Blocks.PISTON) || st.isOf(Blocks.STICKY_PISTON) || st.isOf(Blocks.OBSERVER);
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world == null || mc.player == null) return;

      Map<ChunkPos, AmHit> am = this.detectAmethyst.getValue() ? this.amethystHits : Collections.emptyMap();
      Map<ChunkPos, SusCache.ChestEntry> ch = this.detectChest.getValue() ? this.chestHits : Collections.emptyMap();
      if (am.isEmpty() && ch.isEmpty()) return;

      Camera camera = RenderUtils.getCamera();
      if (camera == null) return;
      Vec3d cam = RenderUtils.getCameraPos(camera);

      double y = this.displayY.getValue() - cam.y;
      double y2 = y + 0.25;
      int alpha = this.fillAlpha.getValue();

      Color aBase = this.amethystColor.getValue();
      Color cBase = this.chestColor.getValue();
      Color aFill = new Color(aBase.getRed(), aBase.getGreen(), aBase.getBlue(), alpha);
      Color cFill = new Color(cBase.getRed(), cBase.getGreen(), cBase.getBlue(), alpha);
      Color aOut = new Color(aBase.getRed(), aBase.getGreen(), aBase.getBlue(), Math.min(255, alpha + 100));
      Color cOut = new Color(cBase.getRed(), cBase.getGreen(), cBase.getBlue(), Math.min(255, alpha + 100));

      matrices.push();
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glEnable(GL11.GL_BLEND);
      ShapeBatch batch = RenderUtils.beginShapeBatch(matrices);

      for (ChunkPos cp : am.keySet()) {
         double x1 = (cp.x << 4) - cam.x;
         double z1 = (cp.z << 4) - cam.z;
         batch.renderFilledBox(x1, y, z1, x1 + 16.0, y2, z1 + 16.0, aFill);
         if (this.showOutline.getValue()) batch.renderOutlineBox(x1, y, z1, x1 + 16.0, y2, z1 + 16.0, aOut);
      }
      for (Map.Entry<ChunkPos, SusCache.ChestEntry> e : ch.entrySet()) {
         ChunkPos cp = e.getKey();
         if (am.containsKey(cp)) continue; // already drawn as amethyst
         double x1 = (cp.x << 4) - cam.x;
         double z1 = (cp.z << 4) - cam.z;
         Color fill = e.getValue().live ? cFill : new Color(cBase.getRed(), cBase.getGreen(), cBase.getBlue(), Math.max(25, alpha - 20));
         batch.renderFilledBox(x1, y, z1, x1 + 16.0, y2, z1 + 16.0, fill);
         if (this.showOutline.getValue()) batch.renderOutlineBox(x1, y, z1, x1 + 16.0, y2, z1 + 16.0, cOut);
      }

      batch.flush();
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      matrices.pop();
   }

   public static void renderHud(DrawContext context, float tickDelta) {
      SUSChunkFinder self = INSTANCE;
      if (self == null || !self.isEnabled() || !self.showInfo.getValue()) return;

      // Snapshot so concurrent scan cannot clear mid-draw
      Map<ChunkPos, AmHit> am = self.detectAmethyst.getValue()
         ? self.amethystHits : Collections.emptyMap();
      Map<ChunkPos, SusCache.ChestEntry> ch = self.detectChest.getValue()
         ? self.chestHits : Collections.emptyMap();
      if (am.isEmpty() && ch.isEmpty()) {
         self.labelCache.clear();
         return;
      }

      double textY = self.displayY.getValue() + 1.5;
      long now = System.currentTimeMillis();
      Set<ChunkPos> keep = new HashSet<>();

      // --- Amethyst labels ---
      for (Map.Entry<ChunkPos, AmHit> e : am.entrySet()) {
         ChunkPos cp = e.getKey();
         AmHit h = e.getValue();
         keep.add(cp);
         double wx = (cp.x << 4) + 8.0;
         double wz = (cp.z << 4) + 8.0;

         String line1 = h.percent + "%";
         String line2 = "Am " + h.count;
         int color = h.percent >= 80 ? 0xFFFF5555 : (h.percent >= 50 ? 0xFFFFAA00 : 0xFFCC88FF);

         LabelPos lp = self.labelCache.computeIfAbsent(cp, k -> new LabelPos());
         lp.line1 = line1;
         lp.line2 = line2;
         lp.color = color;

         boolean ok = Render3D.projectToPoint(
            Render3D.modelViewMatrix, Render3D.projectionMatrix, wx, textY, wz, self.projected);
         if (ok && self.projected.visible) {
            lp.x = (float) self.projected.x;
            lp.y = (float) self.projected.y;
            lp.lastOkMs = now;
         }

         // Draw if we have a recent good projection (hold up to 500ms to kill flicker)
         if (now - lp.lastOkMs < 500L && lp.lastOkMs > 0) {
            drawLabel(context, lp);
         }
      }

      // --- Chest labels ---
      for (Map.Entry<ChunkPos, SusCache.ChestEntry> e : ch.entrySet()) {
         ChunkPos cp = e.getKey();
         if (am.containsKey(cp)) continue;
         SusCache.ChestEntry h = e.getValue();
         keep.add(cp);
         double wx = (cp.x << 4) + 8.0;
         double wz = (cp.z << 4) + 8.0;

         String line1 = h.percent + "%";
         String line2 = (h.tag == null || h.tag.isEmpty() ? "BASE" : h.tag) + (h.live ? "" : "*");
         int color = h.percent >= 70 ? 0xFFFF5555 : (h.percent >= 40 ? 0xFFFFAA00 : 0xFF55FF55);

         LabelPos lp = self.labelCache.computeIfAbsent(cp, k -> new LabelPos());
         lp.line1 = line1;
         lp.line2 = line2;
         lp.color = color;

         boolean ok = Render3D.projectToPoint(
            Render3D.modelViewMatrix, Render3D.projectionMatrix, wx, textY, wz, self.projected);
         if (ok && self.projected.visible) {
            lp.x = (float) self.projected.x;
            lp.y = (float) self.projected.y;
            lp.lastOkMs = now;
         }

         if (now - lp.lastOkMs < 500L && lp.lastOkMs > 0) {
            drawLabel(context, lp);
         }
      }

      // Drop labels for chunks no longer in results
      self.labelCache.keySet().removeIf(cp -> !keep.contains(cp));
   }

   private static void drawLabel(DrawContext context, LabelPos lp) {
      int w1 = FontRenderer.INSTANCE.getWidth(lp.line1);
      int w2 = FontRenderer.INSTANCE.getWidth(lp.line2);
      float x = lp.x;
      float y = lp.y;
      // shadow + text — fixed alpha, no animation
      FontRenderer.INSTANCE.drawString(context, lp.line1, x - w1 / 2f + 1, y + 1, 0xFF000000);
      FontRenderer.INSTANCE.drawString(context, lp.line1, x - w1 / 2f, y, lp.color);
      FontRenderer.INSTANCE.drawString(context, lp.line2, x - w2 / 2f + 1, y + 11, 0xFF000000);
      FontRenderer.INSTANCE.drawString(context, lp.line2, x - w2 / 2f, y + 10, 0xFFFFFFFF);
   }

}
