package shit.zen.utils.misc;

import java.util.HashMap;
import java.util.Map;
import shit.zen.modules.Module;
import shit.zen.modules.impl.render.ClickGuiModule;

/**
 * Small, allocation-free translation tables for the ClickGUI screens.
 *
 * <p>Everything is keyed by a normalised English name (lower case, no spaces),
 * so the same lookup works for module names such as {@code "Glow ESP"} and
 * config entries without spaces. The active language is the persisted
 * {@code Language} mode of {@link ClickGuiModule}; English is always the
 * fallback so a partially translated installation is still readable.</p>
 */
public final class GuiI18n {

    private static final Map<String, String> MODULE_NAMES = new HashMap<String, String>();
    private static final Map<String, String> CATEGORIES = new HashMap<String, String>();
    private static final Map<String, String> CATEGORY_DISPLAY = new HashMap<String, String>();
    private static final Map<String, String> SETTINGS = new HashMap<String, String>();
    private static final Map<String, String> DISPLAY = new HashMap<String, String>();
    private static final Map<String, String> DESCRIPTIONS_ZH = new HashMap<String, String>();
    private static final Map<String, String> DESCRIPTIONS_EN = new HashMap<String, String>();

    private GuiI18n() {
    }

    /** Language setting accessor, safe to call from any GUI class. */
    public static boolean isChinese() {
        return ClickGuiModule.isChinese();
    }

    /** Translates a registered module (or HUD) name into Chinese when enabled. */
    public static String translateModule(String englishName) {
        if (!isChinese() || englishName == null) {
            return englishName;
        }
        String translated = MODULE_NAMES.get(normalise(englishName));
        return translated == null ? englishName : translated;
    }

    /** Translates a category enum name or its legacy display name. */
    public static String translateCategory(String englishCategory) {
        if (!isChinese() || englishCategory == null) {
            return englishCategory;
        }
        String translated = CATEGORY_DISPLAY.get(englishCategory);
        if (translated != null) {
            return translated;
        }
        translated = CATEGORIES.get(normalise(englishCategory));
        return translated == null ? englishCategory : translated;
    }

    /** Translates a setting label (Mode, Speed, Radius, ...). */
    public static String translateSetting(String englishName) {
        if (!isChinese() || englishName == null) {
            return englishName;
        }
        String translated = SETTINGS.get(normalise(englishName));
        return translated == null ? englishName : translated;
    }

    /** Translates any other short GUI display string. */
    public static String translate(String text) {
        if (!isChinese() || text == null) {
            return text;
        }
        String key = normalise(text);
        String translated = DISPLAY.get(key);
        if (translated != null) {
            return translated;
        }
        translated = CATEGORIES.get(key);
        if (translated != null) {
            return translated;
        }
        translated = SETTINGS.get(key);
        if (translated != null) {
            return translated;
        }
        translated = MODULE_NAMES.get(key);
        return translated == null ? text : translated;
    }

    /** One-line tooltip text for a module, taken only from the central tables. */
    public static String description(Module module) {
        if (module == null) {
            return "";
        }
        Map<String, String> descriptions = isChinese() ? DESCRIPTIONS_ZH : DESCRIPTIONS_EN;
        String text = descriptions.get(normalise(module.getName()));
        return text == null ? "" : text;
    }

    private static String normalise(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ++i) {
            char character = value.charAt(i);
            if (character == ' ' || character == '_' || character == '-' || character == '\t') {
                continue;
            }
            builder.append(Character.toLowerCase(character));
        }
        return builder.toString();
    }

    private static void put(Map<String, String> map, String english, String chinese) {
        map.put(normalise(english), chinese);
    }

    private static void module(String english, String chinese, String chineseDescription, String englishDescription) {
        String key = normalise(english);
        MODULE_NAMES.put(key, chinese);
        DESCRIPTIONS_ZH.put(key, chineseDescription);
        DESCRIPTIONS_EN.put(key, englishDescription);
    }

    static {
        // Categories (enum names and the legacy display names).
        put(CATEGORIES, "COMBAT", "战斗");
        put(CATEGORIES, "EXPLOIT", "漏洞利用");
        put(CATEGORIES, "MISC", "杂项");
        put(CATEGORIES, "MOVEMENT", "移动");
        put(CATEGORIES, "PLAYER", "玩家");
        put(CATEGORIES, "RENDER", "渲染");
        put(CATEGORIES, "CLIENT", "客户端");
        put(CATEGORIES, "WORLD", "世界");
        put(CATEGORIES, "Ghost", "杂项"); // legacy MISC display name
        // Exact legacy display strings; EXPLOIT/WORLD/MISC display names are
        // intentionally shifted, so these cannot share the normalised map.
        CATEGORY_DISPLAY.put("Combat", "战斗");
        CATEGORY_DISPLAY.put("Movement", "移动");
        CATEGORY_DISPLAY.put("Player", "玩家");
        CATEGORY_DISPLAY.put("Render", "渲染");
        CATEGORY_DISPLAY.put("World", "漏洞利用"); // EXPLOIT.displayName
        CATEGORY_DISPLAY.put("Misc", "世界");      // WORLD.displayName
        CATEGORY_DISPLAY.put("Ghost", "杂项");     // MISC.displayName
        CATEGORY_DISPLAY.put("Client", "客户端");
        put(DISPLAY, "Combat", "战斗");
        put(DISPLAY, "Exploit", "漏洞利用");

        // Generic ClickGUI labels.
        put(DISPLAY, "Enabled", "已开启");
        put(DISPLAY, "Disabled", "已关闭");
        put(DISPLAY, "Search", "搜索");
        put(DISPLAY, "Search modules...", "搜索模块...");
        put(DISPLAY, "No modules found", "未找到模块");
        put(DISPLAY, "Theme", "主题");
        put(DISPLAY, "Modules", "模块");
        put(DISPLAY, "Settings", "设置");
        put(DISPLAY, "Rainbow", "彩虹");
        put(DISPLAY, "Hue", "色相");
        put(DISPLAY, "Saturation", "饱和度");
        put(DISPLAY, "Brightness", "亮度");
        put(DISPLAY, "Reset", "重置");
        put(DISPLAY, "On", "开");
        put(DISPLAY, "Off", "关");
        put(DISPLAY, "Results", "个结果");
        put(DISPLAY, "Sorting: A-Z", "排序：A-Z");
        put(DISPLAY, "Click GUI", "点击界面");
        put(DISPLAY, "New Click GUI", "新版点击界面");

        // Commonly used setting labels.
        put(SETTINGS, "Mode", "模式");
        put(SETTINGS, "Color", "颜色");
        put(SETTINGS, "Colors", "颜色");
        put(SETTINGS, "Red", "红");
        put(SETTINGS, "Green", "绿");
        put(SETTINGS, "Blue", "蓝");
        put(SETTINGS, "Alpha", "透明度");
        put(SETTINGS, "Radius", "半径");
        put(SETTINGS, "Speed", "速度");
        put(SETTINGS, "Scale", "缩放");
        put(SETTINGS, "Size", "大小");
        put(SETTINGS, "Toggle", "开关");
        put(SETTINGS, "Key", "按键");
        put(SETTINGS, "Theme", "主题");
        put(SETTINGS, "Style", "风格");
        put(SETTINGS, "Opacity", "不透明度");
        put(SETTINGS, "Glow", "发光");
        put(SETTINGS, "Delay", "延迟");
        put(SETTINGS, "Range", "范围");
        put(SETTINGS, "Distance", "距离");
        put(SETTINGS, "Duration", "持续时间");
        put(SETTINGS, "Length", "长度");
        put(SETTINGS, "Height", "高度");
        put(SETTINGS, "Offset", "偏移");
        put(SETTINGS, "Tilt", "倾斜");
        put(SETTINGS, "Spin Speed", "旋转速度");
        put(SETTINGS, "Color Speed", "颜色速度");
        put(SETTINGS, "Through Walls", "穿墙");
        put(SETTINGS, "Render self", "渲染自己");
        put(SETTINGS, "Players", "玩家");
        put(SETTINGS, "Mobs", "生物");
        put(SETTINGS, "Animals", "动物");
        put(SETTINGS, "Items", "物品");
        put(SETTINGS, "Volume", "音量");
        put(SETTINGS, "Loop", "循环");
        put(SETTINGS, "Server", "服务器");
        put(SETTINGS, "API", "接口");
        put(SETTINGS, "Start", "起点");
        put(SETTINGS, "ID", "标识");
        put(SETTINGS, "Show Time", "显示时间");
        put(SETTINGS, "Follow theme", "跟随主题");
        put(SETTINGS, "First person", "第一人称");
        put(SETTINGS, "Fade in", "淡入");
        put(SETTINGS, "Render", "渲染");
        put(SETTINGS, "Debug", "调试");
        put(SETTINGS, "Silent", "静默");
        put(SETTINGS, "Enabled", "已开启");
        put(SETTINGS, "Disabled", "已关闭");

        // XRay 与内置自动挖矿。
        put(SETTINGS, "Block Type", "方块类型");
        put(SETTINGS, "Scan Delay", "扫描间隔");
        put(SETTINGS, "Tracers", "追踪线");
        put(SETTINGS, "MaxBlocks", "最大方块数");
        put(SETTINGS, "Anti-Fake Ore", "防假矿");
        put(SETTINGS, "Min Auth Score", "最低可信分");
        put(SETTINGS, "Pattern Analysis", "分布分析");
        put(SETTINGS, "Strict Host Rock Check", "严格母岩检测");
        put(SETTINGS, "Enable Alternative Start", "启用备用起点");
        put(SETTINGS, "Min Air Blocks Required", "最少空气方块");
        put(SETTINGS, "Alternative Search Radius", "备用搜索半径");
        put(SETTINGS, "Blind Scan", "盲区扫描");
        put(SETTINGS, "Blind Render Range", "盲区渲染范围");
        put(SETTINGS, "Protocol Probe", "协议探针");
        put(SETTINGS, "Probe Radius", "探针半径");
        put(SETTINGS, "Probe Batch", "探针批量");
        put(SETTINGS, "Probe Interval", "探针间隔");
        put(SETTINGS, "Minimum Vein Size", "最小矿脉大小");
        put(SETTINGS, "Diamond", "钻石");
        put(SETTINGS, "Emerald", "绿宝石");
        put(SETTINGS, "Redstone", "红石");
        put(SETTINGS, "Iron", "铁");
        put(SETTINGS, "Gold", "金");
        put(SETTINGS, "Copper", "铜");
        put(SETTINGS, "Lapis", "青金石");
        put(SETTINGS, "Coal", "煤炭");
        put(SETTINGS, "Quartz", "石英");
        put(SETTINGS, "Ancient Debris", "远古残骸");
        put(SETTINGS, "Spawner", "刷怪笼");
        put(SETTINGS, "EndPortalFrame", "末地传送门");
        put(SETTINGS, "Auto Mine", "自动挖矿");
        put(SETTINGS, "Auto Mine Range", "挖矿范围");
        put(SETTINGS, "Auto Mine Delay", "挖矿间隔");
        put(SETTINGS, "Auto Mine Timeout", "单块超时");
        put(SETTINGS, "Auto Mine Priority", "目标优先级");
        put(SETTINGS, "Auto Mine Switch Tool", "自动切换工具");
        put(SETTINGS, "Auto Mine Exposed Only", "仅挖暴露矿物");
        put(SETTINGS, "Auto Mine Confirmed Only", "仅挖已确认矿物");
        put(SETTINGS, "Auto Mine Highlight", "目标高亮");
        put(SETTINGS, "Nearest", "最近优先");
        put(SETTINGS, "Valuable", "价值优先");

        // Combat.
        module("AntiBots", "反机器人", "识别服务器中的机器人玩家，供其他功能过滤。", "Identifies bot players so other features can filter them.");
        module("AntiFireball", "反火焰弹", "拦截并抵消飞向自己的火焰弹。", "Blocks and counters incoming fireballs.");
        module("AntiKB", "反击退", "减少或取消受到的击退效果。", "Reduces or cancels knockback taken from attacks.");
        module("AutoOffHand", "自动副手", "自动将金苹果等物品切换到副手。", "Automatically moves items such as golden apples to the offhand.");
        module("AutoSoup", "自动喝汤", "生命值低时自动食用汤类物品回血。", "Automatically eats soup for healing when health is low.");
        module("AutoThrow", "自动投掷", "自动投掷珍珠或雪球等物品。", "Automatically throws items such as pearls or snowballs.");
        module("Backtrack", "回溯", "延迟敌人位置更新以延长可攻击时间。", "Delays enemy position updates to extend the hit window.");
        module("Critical", "暴击", "确保近战攻击始终触发暴击。", "Forces melee attacks to land as critical hits.");
        module("CrystalAura", "水晶光环", "自动放置并破坏末影水晶攻击敌人。", "Automatically places and breaks end crystals to attack enemies.");
        module("FakeLag", "假延迟", "缓存并延迟发送移动包以模拟高延迟。", "Buffers and delays movement packets to emulate high latency.");
        module("KillAura", "杀戮光环", "自动锁定并攻击范围内的敌人。", "Automatically targets and attacks nearby enemies.");

        // Exploit.
        module("Disabler", "反作弊绕过", "发送异常数据包以绕过部分反作弊检测。", "Sends crafted packets to bypass certain anti-cheat checks.");
        module("FastPlace", "快速放置", "取消放置方块后的冷却时间。", "Removes the cooldown between block placements.");

        // Misc.
        module("AimAssist", "辅助瞄准", "轻微修正视角以帮助瞄准目标。", "Gently adjusts aim toward nearby targets.");
        module("AutoClicker", "自动点击", "自动模拟鼠标点击。", "Automatically simulates mouse clicks.");
        module("AutoRod", "自动钓竿", "自动抛出并收回钓竿。", "Automatically casts and reels the fishing rod.");
        module("SafeWalk", "安全行走", "防止在方块边缘意外掉落。", "Prevents walking off block edges accidentally.");

        // Movement.
        module("CollisionSpeed", "碰撞加速", "靠近实体时获得额外移动速度。", "Gains extra speed when colliding with nearby entities.");
        module("NoSlow", "无减速", "取消灵魂沙、蛛网和使用物品时的减速。", "Cancels slowdown from soul sand, cobwebs and item use.");
        module("FastWeb", "快速蛛网", "在蛛网中保持正常移动速度。", "Maintains normal movement speed inside cobwebs.");
        module("FireballBlink", "火球闪现", "利用火焰弹爆炸实现瞬间位移。", "Uses fireball blasts for instant repositioning.");
        module("Fly", "飞行", "允许玩家在空中自由飞行。", "Allows the player to fly freely.");
        module("GuiMove", "界面移动", "打开界面时仍可移动角色。", "Lets the player move while a GUI screen is open.");
        module("HighJump", "高跳", "提高跳跃高度。", "Increases jump height.");
        module("NoDelay", "无延迟", "取消跳跃与移动之间的动作延迟。", "Removes delay between jumping and other actions.");
        module("NoPush", "无推挤", "阻止其他玩家推动自己。", "Prevents other players from pushing you.");
        module("Scaffold", "搭桥", "行走时自动在脚下放置方块。", "Automatically places blocks under your feet while walking.");
        module("Sprint", "疾跑", "自动保持疾跑状态。", "Automatically keeps the player sprinting.");
        module("TargetStrafe", "目标环绕", "围绕当前目标进行横向移动。", "Strafes around the currently targeted entity.");

        // Player.
        module("AntiTNT", "反TNT", "推迟或阻止TNT爆炸造成的伤害。", "Delays or blocks damage from TNT explosions.");
        module("AntiVoid", "防虚空", "掉入虚空时自动传送回安全位置。", "Teleports you to safety when falling into the void.");
        module("AntiWeb", "反蛛网", "自动清除或绕过身上的蛛网。", "Automatically clears or bypasses cobwebs around you.");
        module("AutoMLG", "自动落地", "落地前自动放置水桶缓冲坠落。", "Automatically places water to cushion a fall before landing.");
        module("AutoWebPlace", "自动放网", "自动向附近敌人放置蛛网。", "Automatically places cobwebs around nearby enemies.");
        module("ChestStealer", "自动搜箱", "自动快速拾取容器中的物品。", "Quickly loots items from containers automatically.");
        module("GhostHand", "穿透手", "隔着方块与容器交互。", "Interacts with containers through blocks.");
        module("Helper", "辅助助手", "自动灭火、阻挡熔岩与水流。", "Automatically extinguishes fire and blocks lava or water.");
        module("InventoryManager", "背包管理", "自动整理装备、投掷杂物并管理副手。", "Automatically manages armor, junk items and offhand equipment.");
        module("MidPearl", "中键珍珠", "用鼠标中键快速投掷末影珍珠。", "Throws ender pearls quickly with the middle mouse button.");
        module("NoFall", "无摔落", "免疫或抵消摔落伤害。", "Cancels fall damage.");
        module("Stuck", "卡位", "通过延迟或数据包保持角色位置不被推动。", "Keeps your position from being moved using delays or packets.");

        // Render.
        module("AspectRatio", "画面比例", "自定义游戏画面的宽高比。", "Sets a custom aspect ratio for the game viewport.");
        module("BedESP", "床透视", "高亮显示周围的床方块。", "Highlights nearby bed blocks through walls.");
        module("Better Chat", "增强聊天", "美化并增强聊天栏的显示。", "Enhances and beautifies the chat display.");
        module("BlockHighlight", "方块高亮", "自定义选中方块的轮廓高亮。", "Customizes the outline highlight of the targeted block.");
        module("BlockOverlay", "方块覆盖层", "自定义准星所指方块的覆盖高亮。", "Customizes the highlighted overlay on the block you target.");
        module("BreakProgress", "挖掘进度", "在准星附近显示方块挖掘进度。", "Shows block-breaking progress near the crosshair.");
        module("Chams", "模型高亮", "用彩色模型轮廓显示玩家。", "Renders players with colored model outlines.");
        module("ChestESP", "箱子透视", "透视显示附近的箱子与容器。", "Highlights nearby chests and containers through walls.");
        module("ChinaHat", "斗笠", "在角色头顶绘制旋转的斗笠光环。", "Draws a spinning decorative hat above the player.");
        module("ClickGui", "点击界面", "打开 ClickGUI 设置菜单。", "Opens the ClickGUI settings menu.");
        module("Compass", "指南针", "在屏幕上指示出生点方向。", "Points toward the world spawn on screen.");
        module("DamageGlow", "受伤发光", "让受伤的实体短暂发光。", "Makes damaged entities glow briefly.");
        module("Damage Tags", "伤害标签", "在实体上方显示近期受到的伤害数值。", "Displays recent damage numbers above entities.");
        module("ESP", "人物透视", "高亮显示周围的玩家与生物。", "Highlights nearby players and mobs through walls.");
        module("FullBright", "全亮", "将游戏亮度提升到最大。", "Raises game brightness to the maximum.");
        module("Glow ESP", "发光透视", "以发光轮廓显示实体。", "Draws glowing outlines around entities.");
        module("Halo", "光环", "在角色头顶绘制漂浮光环。", "Draws a floating decorative halo above the player.");
        module("Hit Afterimage", "命中残影", "命中目标时显示发光残影特效。", "Shows glowing afterimages when hitting entities.");
        module("Indicators", "目标指示器", "在准星周围显示威胁与投掷物方向。", "Shows arrows around the crosshair pointing at threats and projectiles.");
        module("Interface", "界面增强", "启用额外的客户端界面装饰。", "Enables additional client-side interface decorations.");
        module("ItemESP", "物品透视", "高亮显示地上的掉落物。", "Highlights dropped items on the ground.");
        module("ItemTags", "物品标签", "在物品上方显示名称与数量标签。", "Shows name and count labels above dropped items.");
        module("JumpCircles", "跳跃光圈", "跳跃或落地时播放扩散光圈特效。", "Plays expanding ring effects on jump and landing.");
        module("Keystrokes", "按键显示", "在屏幕上显示按键按下状态。", "Displays pressed keys on screen.");
        module("Low Health", "低血量警告", "生命值过低时显示醒目警告。", "Shows a prominent warning when health drops low.");
        module("Meteors", "陨石", "在世界中绘制下落的陨石粒子。", "Draws falling meteor particles in the world.");
        module("NameProtect", "名字保护", "替换屏幕上出现的指定昵称。", "Replaces chosen player names wherever they appear.");
        module("NameTags", "名字标签", "在玩家头顶显示自定义名字标签。", "Shows custom name tags above players.");
        module("NoHurtCam", "无受伤晃动", "取消受伤时的镜头晃动。", "Removes the camera shake when taking damage.");
        module("Notifications", "通知提示", "以卡片形式显示模块与事件通知。", "Shows module and event notifications as toast cards.");
        module("OldHitting", "旧版挥手", "复刻旧版攻击手臂动画。", "Recreates the legacy attack-swing animation.");
        module("Projectiles", "投掷物显示", "显示飞行中投掷物的轨迹与信息。", "Shows flight paths and details of airborne projectiles.");
        module("Radar", "雷达", "在屏幕上显示周围玩家的雷达图。", "Displays a radar map of nearby players.");
        module("Strength Timer", "力量计时", "显示力量效果剩余时间的环形计时器。", "Shows a ring timer for the remaining strength effect.");
        module("TargetEsp", "目标高亮", "为当前攻击目标绘制特殊标记。", "Draws a distinctive marker on your current target.");
        module("Tracers", "准星轨迹", "从准星绘制指向实体的连线。", "Draws lines from the crosshair toward visible entities.");
        module("Trails", "移动轨迹", "在玩家移动路径上绘制拖尾。", "Draws a trail along the player's movement path.");
        module("Trajectories", "弹道预测", "预测并显示弓箭与投掷物的飞行轨迹。", "Predicts and draws the flight path of arrows and throwables.");
        module("Watermark", "水印", "显示客户端水印或动态岛。", "Shows the client watermark or dynamic island.");
        module("WindowTitle", "窗口标题", "动态修改游戏窗口的标题文字。", "Animates the game window title text.");
        module("Wings", "翼", "在角色背后绘制装饰性翅膀。", "Draws decorative wings behind the player.");
        module("XRay", "方块透视", "让指定方块变得透明可见，并可自动探测与挖掘周围矿物。", "Makes selected blocks transparent and can automatically detect and mine nearby ores.");
        module("Zoom", "缩放", "降低视野以便观察远处。", "Lowers the field of view to zoom in.");

        // Client.
        module("ToggleSounds", "切换音效", "开关模块时播放提示音。", "Plays a sound when modules are toggled.");
        module("MusicPlayer", "音乐播放器", "内置音乐播放器，可在线播放歌单。", "Built-in music player that streams online playlists.");
        module("FreeLook", "自由视角", "锁定身体朝向的同时自由环顾四周。", "Looks around freely while keeping body rotation locked.");

        // World.
        module("AntiStaff", "反管理员", "检测并提示服务器管理组成员上线。", "Detects and alerts when staff members join the server.");
        module("AutoPlay", "自动重连", "断开连接后自动重新加入服务器。", "Automatically reconnects to the server after a disconnect.");
        module("AutoTools", "自动工具", "根据目标方块自动切换到合适的工具。", "Automatically switches to the right tool for the target block.");
        module("Debugger", "调试器", "断开连接时输出线程与类诊断信息。", "Dumps thread and class diagnostics on disconnect.");
        module("Teams", "队伍识别", "根据颜色或计分板识别同队玩家。", "Detects teammates by color or scoreboard conditions.");
        module("WebUI", "网页控制台", "通过本地网页控制客户端设置。", "Controls client settings through a local web interface.");

        // HUD elements.
        module("TargetHUD", "目标HUD", "在屏幕上显示当前目标的血量信息。", "Shows the current target's health on screen.");
        module("AttackTargetHUD", "攻击目标信息", "显示最近攻击目标的详细信息。", "Shows detailed information about the last attacked entity.");
        module("MusicHUD", "音乐HUD", "显示当前播放的歌曲信息。", "Displays information about the currently playing track.");
        module("KeyBinds", "按键绑定", "在屏幕上显示已绑定的按键列表。", "Shows a list of bound keys on screen.");
        module("ModuleList", "模块列表", "列出当前开启的所有模块。", "Lists all currently enabled modules.");
        module("PlayerList", "玩家列表", "显示服务器中的在线玩家列表。", "Shows the server's online player list.");
        module("Effects", "状态效果", "显示玩家当前的状态效果。", "Displays the player's active status effects.");
        module("LieDetector", "谎言检测", "检测并显示玩家声称与实际的差异。", "Detects and displays inconsistencies in players' claims.");

        // Names kept for compatibility with older configuration files.
        module("Target Hud Panel", "目标HUD面板", "显示旧版目标信息面板。", "Displays the legacy target information panel.");
        module("PotionHUD", "药水HUD", "以 HUD 形式显示当前药水效果。", "Displays active potion effects as a HUD.");
        module("InfoHUD", "信息HUD", "在屏幕上显示客户端基本信息。", "Shows general client information on screen.");
        module("Clock", "时钟", "在游戏内显示当前时间。", "Displays the in-game time as a clock.");
    }
}
