package adentika.torch.core;

/**
 * The six block directions, numbered like Minecraft's {@code Direction} ids so adapters can map 1:1:
 * DOWN, UP, NORTH (-z), SOUTH (+z), WEST (-x), EAST (+x). {@code opposite(d) == d ^ 1}.
 */
public final class Dir {
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public static final int COUNT = 6;

    static final int[] DX = {0, 0, 0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0, 0, 0};
    static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private Dir() {
    }

    public static int opposite(int dir) {
        return dir ^ 1;
    }

    public static boolean isHorizontal(int dir) {
        return dir >= NORTH;
    }

    public static int bit(int dir) {
        return 1 << dir;
    }
}
