package adentika.torch.mc;

import adentika.torch.core.Dir;
import adentika.torch.core.GridSnapshot;
import adentika.torch.core.Planner;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.Map;

/** Shared drawing of analysis results: dark spots, chunk outlines, planned torches (main/render thread). */
public final class PlanRenderer {
    private static final Color TMP = new Color();

    private PlanRenderer() {
    }

    /** Dark spots as flat squares on the floor; only within {@code maxDistance} of the camera position given. */
    public static void spots(Render3DEvent event, Analysis.Result r, Color color, double px, double py, double pz,
                             double maxDistance, int maxCount) {
        GridSnapshot g = r.grid();
        double maxSq = maxDistance * maxDistance;
        int drawn = 0;
        for (int cell : r.dangerCells()) {
            if (drawn >= maxCount) break;
            int x = g.x(cell), y = g.y(cell), z = g.z(cell);
            double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;
            event.renderer.sideHorizontal(x + 0.1, y + 0.02, z + 0.1, x + 0.9, z + 0.9, color, color, ShapeMode.Sides);
            drawn++;
        }
    }

    /** Outline per chunk at the average height of its dark spots; more spots -> more opaque line. */
    public static void chunks(Render3DEvent event, Analysis.Result r, Color base) {
        for (Map.Entry<Long, Analysis.ChunkInfo> e : r.chunkDanger().entrySet()) {
            int cx = (int) (e.getKey() & 0xFFFFFFFFL);
            int cz = (int) (e.getKey() >>> 32);
            float k = Math.min(1f, e.getValue().count() / 64f);
            TMP.set(base.r, base.g, base.b, Math.min(255, (int) (80 + 175 * k)));
            int x = cx << 4, z = cz << 4;
            double y = Math.floor(e.getValue().averageY()) + 0.05;
            event.renderer.sideHorizontal(x, y, z, x + 16, z + 16, TMP, TMP, ShapeMode.Lines);
        }
    }

    public static void torches(Render3DEvent event, Analysis.Result r, Color color) {
        for (Planner.Torch t : r.torches()) torch(event, r.grid(), t, color);
    }

    /** A torch-sized box; wall torches lean against the supporting block in {@code attachDir}. */
    public static void torch(Render3DEvent event, GridSnapshot g, Planner.Torch t, Color color) {
        double x = g.x(t.cell()) + 0.5, y = g.y(t.cell()), z = g.z(t.cell()) + 0.5;
        double ox = 0, oz = 0, oy = 0;
        if (t.isWall()) {
            ox = t.attachDir() == Dir.WEST ? -0.3 : t.attachDir() == Dir.EAST ? 0.3 : 0;
            oz = t.attachDir() == Dir.NORTH ? -0.3 : t.attachDir() == Dir.SOUTH ? 0.3 : 0;
            oy = 0.2;
        }
        event.renderer.box(x + ox - 0.1, y + oy, z + oz - 0.1, x + ox + 0.1, y + oy + 0.6, z + oz + 0.1,
            color, color, ShapeMode.Both, 0);
    }
}
