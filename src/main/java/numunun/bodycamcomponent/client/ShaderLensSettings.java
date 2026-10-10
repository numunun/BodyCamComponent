package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

// Oculus와 Body Camera Shader 설정 파일에서 렌즈 값을 읽어옴
final class ShaderLensSettings {
    private static final long REFRESH_MS = 5000;

    private static long lastLoad = 0;
    private static boolean bodycam = false;
    private static float zoom = 0.80f;
    private static float distStrength = 1.00f;
    private static boolean force43 = true;

    private ShaderLensSettings() {}

    static boolean isBodycam() { return bodycam; }
    static float zoom() { return zoom; }
    static float distStrength() { return distStrength; }
    static boolean force43() { return force43; }

    static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastLoad < REFRESH_MS) return;
        lastLoad = now;

        File dir = Minecraft.getInstance().gameDirectory;
        Properties oculus = load(new File(dir, "config/oculus.properties"));
        String pack = oculus.getProperty("shaderPack", "");
        boolean enabled = !"false".equalsIgnoreCase(oculus.getProperty("enableShaders", "true"));
        String lower = pack.toLowerCase(Locale.ROOT);
        bodycam = enabled && lower.contains("body") && lower.contains("camera");

        zoom = 0.80f;
        distStrength = 1.00f;
        force43 = true;
        if (!bodycam) return;

        Properties s = load(new File(dir, "shaderpacks/" + pack + ".txt"));
        zoom = parse(s.getProperty("ZOOM"), 0.80f);
        distStrength = parse(s.getProperty("DIST_STRENGTH"), 1.00f);
        String f = s.getProperty("FORCE_4_3_RATIO");
        if (f != null) force43 = !(f.trim().equals("0") || f.trim().equalsIgnoreCase("false"));
    }

    private static Properties load(File file) {
        Properties p = new Properties();
        if (!file.isFile()) return p;
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Exception ignored) {
        }
        return p;
    }

    private static float parse(String v, float fallback) {
        if (v == null) return fallback;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}