package io.github.escosis.vivothemehelper;

/** Immutable bindings for the supplied 24.2.8.0 build, with no legacy fallback. */
public final class ModuleInfo {
    private ModuleInfo() {}
    public static final String TARGET_PACKAGE = "com.bbk.theme";
    public static final String TARGET_VERSION = "24.2.8.0";
    public static final long TARGET_CODE = 100024280L;
    public static final String RELEASE = "24.2.8.0-r1";
    public static final String MODULE_PACKAGE = "io.github.escosis.vivothemehelper";
    public static final String LOG_TAG = "VivoTheme24";
    public static final String IMPORT_TAG = "io.github.escosis.vivothemehelper.import";
    public static final String EXPORT_TAG = "io.github.escosis.vivothemehelper.export";
    public static final String FRAGMENT_TAG = "io.github.escosis.vivothemehelper.session";
    public static final int PICK_REQUEST = 0x5641;
    public static final int EXPORT_REQUEST = 0x5642;
    public static final int ITEM_LAYOUT_ID = 0x7f0d01f0;
    public static final int ITEM_ICON_ID = 0x7f0a0601;
    public static final int ITEM_TITLE_ID = 0x7f0a0e11;
    public static final int ITEM_CLICK_ID = 0x7f0a07dc;
    public static final int ITEM_DOT_ID = 0x7f0a0fbf;
    public static final int IMPORT_ICON_ID = 0x7f08080e;
    public static final int EXPORT_ICON_ID = 0x7f080bf9;
    public static final int IMPORT_COLOR_ID = 0x7f060260;
    public static final int[] TYPE_VALUES = {1,16,2,9,13,4,18,5,20,7,14,12};
    public static final String[] TYPE_NAMES = {"主题","妙玩组件","动态壁纸","静态壁纸","行为壁纸","字体","图标","锁屏样式","控制中心","熄屏样式","视频铃声","输入法皮肤"};
    public static final String[] DEX_NAMES = {"classes.dex","classes2.dex","classes3.dex","classes4.dex","classes5.dex","classes6.dex","classes7.dex"};
    public static final long[] DEX_SIZES = {9337100L, 10278716L, 4215256L, 4177972L, 11816260L, 9939000L, 5223108L};
    public static final long[] DEX_CRCS = {484758677L, 1084943645L, 2400585763L, 2677933461L, 4241267642L, 1136313417L, 1883324817L};
    public static final String[] DEX_SHA256 = {
            "9c4544847d69ef2442e1af0a8e82f7bae5776db50644c67217e950914e1522ec",
            "03c7670245c87e2cbcbcd922371c3a9b88bd6b40de85012471795f25fe3f9747",
            "6b53f9216f9baa4bc39daecab410cd6df12d0701333c80a63950b7ec028649a9",
            "11e2e4981c9d2830a3d6d450cff69d7cec0967f1d54825abdd55a8afc298d417",
            "8707da527f7a0e490236601c9a9a0c81edbaa40e4b0fbfd102f38c038bcc0af6",
            "6952aeea60c41ef615111392e24e1ef530468f181113dc3751d485ef1ed5e64e",
            "7e6d2652076d2fe0280b01c8f515e657f0797e91617d950f675b608d6c1fc0b1"
    };
    public static boolean hasType(int type) { for (int value : TYPE_VALUES) if (value == type) return true; return false; }
    public static void requireType(int type) { if (!hasType(type)) throw new IllegalArgumentException("Unsupported resource type: " + type); }
}
