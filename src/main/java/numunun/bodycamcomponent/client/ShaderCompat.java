package numunun.bodycamcomponent.client;

import java.lang.reflect.Method;

// Oculus(Iris) 쉐이더 사용 여부 확인 (Oculus 없어도 동작)
final class ShaderCompat {
    private static boolean initialized = false;
    private static boolean failed = false;
    private static Object api;
    private static Method isInUse;

    private ShaderCompat() {}

    static boolean isShaderActive() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                isInUse = c.getMethod("isShaderPackInUse");
            } catch (Throwable t) {
                failed = true;
            }
        }
        if (failed) return false;
        try {
            return (boolean) isInUse.invoke(api);
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }
}