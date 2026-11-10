import com.pbb.livetv.Camera;
import com.pbb.livetv.StreamFormat;
import com.pbb.livetv.core.GridNavigator;
import com.pbb.livetv.media.HlsPlaylist;
import com.pbb.livetv.ui.TileLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free verification of the app's pure logic.
 *
 * <p>Runs as a plain JVM program (no JUnit, no device) against the same sources that go into the
 * APK: CCTV D-pad navigation, tile aspect-ratio maths, HLS playlist parsing, camera/catalogue rules
 * and URL classification. `buildtools/test_core.sh` compiles and runs this.
 */
public final class VerifyCore {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        gridNavigation();
        gridPaging();
        tileLayout();
        hlsMasterPlaylist();
        hlsMediaPlaylist();
        cameraStates();
        sections();
        urlFormats();

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("CORE LOGIC OK");
    }

    // ------------------------------------------------------------------ grid navigation

    private static void gridNavigation() {
        section("2x2 D-pad navigation (4 cameras)");
        boolean[] four = GridNavigator.occupancy(4);
        eq("right from 0", 1, GridNavigator.next(0, GridNavigator.Move.RIGHT, four));
        eq("left from 1", 0, GridNavigator.next(1, GridNavigator.Move.LEFT, four));
        eq("down from 0", 2, GridNavigator.next(0, GridNavigator.Move.DOWN, four));
        eq("down from 1", 3, GridNavigator.next(1, GridNavigator.Move.DOWN, four));
        eq("up from 3", 1, GridNavigator.next(3, GridNavigator.Move.UP, four));
        eq("left edge from 0", GridNavigator.EDGE, GridNavigator.next(0, GridNavigator.Move.LEFT, four));
        eq("up edge from 0", GridNavigator.EDGE, GridNavigator.next(0, GridNavigator.Move.UP, four));
        eq("right edge from 3", GridNavigator.EDGE, GridNavigator.next(3, GridNavigator.Move.RIGHT, four));
        eq("down edge from 3", GridNavigator.EDGE, GridNavigator.next(3, GridNavigator.Move.DOWN, four));

        section("holes are skipped (3 cameras)");
        boolean[] three = GridNavigator.occupancy(3);
        eq("right from 2 (empty 3) -> edge", GridNavigator.EDGE,
                GridNavigator.next(2, GridNavigator.Move.RIGHT, three));
        eq("down from 1 (empty 3) -> edge", GridNavigator.EDGE,
                GridNavigator.next(1, GridNavigator.Move.DOWN, three));

        boolean[] awkward = new boolean[]{true, false, false, true};
        // Moves stay on their row/column, so a hole next to the tile means "edge", not "wrap".
        eq("right into hole then edge", GridNavigator.EDGE,
                GridNavigator.next(0, GridNavigator.Move.RIGHT, awkward));
        eq("up into hole then edge", GridNavigator.EDGE,
                GridNavigator.next(3, GridNavigator.Move.UP, awkward));
        boolean[] topRow = new boolean[]{true, true, false, false};
        eq("no wrap from last column", GridNavigator.EDGE,
                GridNavigator.next(1, GridNavigator.Move.RIGHT, topRow));
        eq("down into empty row -> edge", GridNavigator.EDGE,
                GridNavigator.next(0, GridNavigator.Move.DOWN, topRow));
    }

    private static void gridPaging() {
        section("grid paging");
        eq("0 cameras -> 1 page", 1, GridNavigator.pageCount(0));
        eq("4 cameras -> 1 page", 1, GridNavigator.pageCount(4));
        eq("5 cameras -> 2 pages", 2, GridNavigator.pageCount(5));
        eq("9 cameras -> 3 pages", 3, GridNavigator.pageCount(9));
        eq("clamp high", 1, GridNavigator.clampPage(7, 6));
        eq("clamp low", 0, GridNavigator.clampPage(-3, 6));
        eq("first occupied", 1, GridNavigator.firstOccupied(new boolean[]{false, true, true, true}));
    }

    // ------------------------------------------------------------------ layout

    private static void tileLayout() {
        section("16:9 fitting (no stretching, no overflow)");
        TileLayout.Box wide = TileLayout.fit16x9(1920, 500);
        eq("wide box keeps ratio", 889, wide.width);
        eq("wide box height fits", 500, wide.height);
        TileLayout.Box tall = TileLayout.fit16x9(600, 1080);
        eq("tall box width", 600, tall.width);
        eq("tall box height", 338, tall.height);
        eq("zero input", 0, TileLayout.fit16x9(0, 0).width);

        section("2x2 grid cells at 1920x1080 with 12 px gaps and 28 px gutters");
        int availableW = 1920 - 2 * 28;
        int availableH = 1080 - 66 - 60; // nav bar + header
        TileLayout.Box cell = TileLayout.cell(availableW, availableH, 2, 2, 12);
        check("cells are positive", cell.width > 0 && cell.height > 0);
        check("two columns + gap fit", cell.width * 2 + 12 <= availableW);
        check("two rows + gap fit", cell.height * 2 + 12 <= availableH);
        TileLayout.Box video = TileLayout.fit16x9(cell.width, cell.height);
        check("video fits in cell", video.width <= cell.width && video.height <= cell.height);
        check("video is 16:9", Math.abs(video.width / (double) video.height - 16d / 9d) < 0.02);
        eq("centred offset", 0, TileLayout.offset(200, 200));
        eq("centred offset smaller video", 25, TileLayout.offset(200, 150));
    }

    // ------------------------------------------------------------------ HLS

    private static void hlsMasterPlaylist() {
        section("HLS master playlist");
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080\nhigh/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720\nmid/index.m3u8\n";
        HlsPlaylist p = HlsPlaylist.parse(master, "https://cdn.example.com/live/master.m3u8");
        check("master detected", p.isMaster());
        eq("variant count", 3, p.variants.size());
        HlsPlaylist.Variant v = p.pickVariant(1080);
        eq("picks 1080p", 1080, v.height);
        eq("absolute url resolved", "https://cdn.example.com/live/high/index.m3u8", v.url);
        eq("720p cap respected", 720, p.pickVariant(720).height);
        eq("360p cap respected", 360, p.pickVariant(300).height);
        check("no encryption flag", !p.encrypted);

        HlsPlaylist encrypted = HlsPlaylist.parse(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\nsegs/a.ts\n", "https://e/x.m3u8");
        check("encryption detected", encrypted.encrypted);
    }

    private static void hlsMediaPlaylist() {
        section("HLS live media playlist");
        String media = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXTINF:5.5,\nseg042.ts\n"
                + "#EXTINF:6.0,\nseg043.ts\n"
                + "#EXTINF:5.8,\nseg044.ts\n";
        HlsPlaylist p = HlsPlaylist.parse(media, "https://cdn.example.com/live/index.m3u8");
        check("not a master", !p.isMaster());
        eq("segment count", 3, p.segments.size());
        eq("media sequence", 42L, p.mediaSequence);
        eq("first segment sequence", 42L, p.segments.get(0).sequence);
        eq("last segment sequence", 44L, p.segments.get(2).sequence);
        eq("segment url resolved", "https://cdn.example.com/live/seg043.ts", p.segments.get(1).url);
        check("duration parsed", Math.abs(p.segments.get(1).duration - 6.0d) < 0.001);
        eq("target duration", 6d, p.targetDuration);
        check("no endlist on live", !p.endList);

        HlsPlaylist ended = HlsPlaylist.parse("#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n", "https://e/a.m3u8");
        check("endlist detected", ended.endList);
    }

    // ------------------------------------------------------------------ camera rules

    private static void cameraStates() {
        section("camera states never fake a stream");
        Camera none = new Camera("a", "Dining", "", Camera.Section.DINING, "", "", "", "", 1);
        check("empty url -> not playable", !none.playable());
        eq("empty url status", "No stream", none.statusText());

        Camera dash = new Camera("b", "Main", "", Camera.Section.MAIN_LIVE,
                "https://x/manifest.mpd", "", "", "", 1);
        check("dash without keys -> awaiting key", dash.awaitingKey());
        check("dash without keys -> not playable", !dash.playable());
        eq("dash status", "Licence required", dash.statusText());

        Camera keyed = dash.withKeys("kid", "key");
        check("keys present -> playable", keyed.playable());
        check("keys present -> DRM path", keyed.drmsProtected());
        eq("keyed footer", "MPEG-DASH · ClearKey", keyed.footerText());

        Camera hls = new Camera("c", "Den", "", Camera.Section.EXTERNAL,
                "https://x/live.m3u8", "", "", "", 3);
        check("hls playable", hls.playable());
        eq("hls format guessed", StreamFormat.HLS, hls.format);
        eq("cam order label", 3, hls.order);
    }

    private static void sections() {
        section("sections");
        List<Camera> all = new ArrayList<>();
        all.add(new Camera("1", "Main", "", Camera.Section.MAIN_LIVE, "https://a/x.m3u8", "", "", "", 1));
        all.add(new Camera("2", "Dining", "", Camera.Section.DINING, "https://a/y.m3u8", "", "", "", 2));
        all.add(new Camera("3", "Den", "", Camera.Section.EXTERNAL, "https://a/z.m3u8", "", "", "", 3));
        eq("dining only", 1, Camera.ofSection(all, Camera.Section.DINING).size());
        eq("external only", 1, Camera.ofSection(all, Camera.Section.EXTERNAL).size());
        eq("main live only", 1, Camera.ofSection(all, Camera.Section.MAIN_LIVE).size());
        eq("first playable", "1", Camera.firstPlayable(all).id);
        eq("by id", "3", Camera.byId(all, "3").id);
        check("by id miss", Camera.byId(all, "nope") == null);
        eq("section indexes are the nav order", 3, Camera.Section.ALL.index);
        eq("section parse", Camera.Section.EXTERNAL, Camera.Section.fromName("external", Camera.Section.ALL));
        eq("section fallback", Camera.Section.MAIN_LIVE,
                Camera.Section.fromName("bogus", Camera.Section.MAIN_LIVE));
    }

    private static void urlFormats() {
        section("URL classification");
        eq("mpd", StreamFormat.DASH, StreamFormat.guess("https://a/b/manifest.mpd"));
        eq("dash path", StreamFormat.DASH, StreamFormat.guess("https://a/dash/live/x"));
        eq("m3u8", StreamFormat.HLS, StreamFormat.guess("https://a/b/live.m3u8?token=1"));
        eq("format=mpd", StreamFormat.DASH, StreamFormat.guess("https://a/play?format=mpd&id=2"));
        eq("mp4", StreamFormat.PROGRESSIVE, StreamFormat.guess("https://a/clip.mp4"));
        check("isDash", StreamFormat.isDash("https://a/x.mpd"));
        check("isHls", StreamFormat.isHls("https://a/x.m3u8"));
        eq("labels", "MPEG-DASH", StreamFormat.label(StreamFormat.DASH));
    }

    // ------------------------------------------------------------------ harness

    private static void section(String name) {
        System.out.println("\n== " + name);
    }

    private static void eq(String name, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            System.out.println("  ok   " + name + " = " + actual);
        } else {
            failures++;
            System.out.println("  FAIL " + name + ": expected " + expected + " but was " + actual);
        }
    }

    private static void check(String name, boolean condition) {
        checks++;
        if (condition) {
            System.out.println("  ok   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name);
        }
    }
}
