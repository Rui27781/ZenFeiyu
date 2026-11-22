package shit.zen.manager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.event.impl.KeyEvent;
import shit.zen.exception.ModuleNotFoundException;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.AntiBots;
import shit.zen.modules.impl.combat.AntiFireball;
import shit.zen.modules.impl.combat.AntiKB;
import shit.zen.modules.impl.combat.AutoOffHand;
import shit.zen.modules.impl.combat.AutoSoup;
import shit.zen.modules.impl.combat.AutoThrow;
import shit.zen.modules.impl.combat.Backtrack;
import shit.zen.modules.impl.combat.Critical;
import shit.zen.modules.impl.combat.CrystalAura;
import shit.zen.modules.impl.combat.FakeLag;
import shit.zen.modules.impl.combat.KillAura;
import shit.zen.modules.impl.client.FreeLook;
import shit.zen.modules.impl.client.MusicPlayer;
import shit.zen.modules.impl.client.ToggleSounds;
import shit.zen.modules.impl.exploit.Disabler;
import shit.zen.modules.impl.exploit.FastPlace;
import shit.zen.modules.impl.misc.AimAssist;
import shit.zen.modules.impl.misc.AutoClicker;
import shit.zen.modules.impl.misc.AutoRod;
import shit.zen.modules.impl.misc.SafeWalk;
import shit.zen.modules.impl.movement.CollisionSpeed;
import shit.zen.modules.impl.movement.NoSlow;
import shit.zen.modules.impl.movement.FastWeb;
import shit.zen.modules.impl.movement.FireballBlink;
import shit.zen.modules.impl.movement.Fly;
import shit.zen.modules.impl.movement.GuiMove;
import shit.zen.modules.impl.movement.HighJump;
import shit.zen.modules.impl.movement.NoDelay;
import shit.zen.modules.impl.movement.NoPush;
import shit.zen.modules.impl.movement.Scaffold;
import shit.zen.modules.impl.movement.Sprint;
import shit.zen.modules.impl.movement.TargetStrafe;
import shit.zen.modules.impl.player.AntiTNT;
import shit.zen.modules.impl.player.AntiVoid;
import shit.zen.modules.impl.player.AntiWeb;
import shit.zen.modules.impl.player.AutoMLG;
import shit.zen.modules.impl.player.AutoWebPlace;
import shit.zen.modules.impl.player.ChestStealer;
import shit.zen.modules.impl.player.GhostHand;
import shit.zen.modules.impl.player.Helper;
import shit.zen.modules.impl.player.InventoryManager;
import shit.zen.modules.impl.player.MidPearl;
import shit.zen.modules.impl.player.NoFall;
import shit.zen.modules.impl.player.Stuck;
import shit.zen.modules.impl.render.AspectRatio;
import shit.zen.modules.impl.render.BedESP;
import shit.zen.modules.impl.render.BetterChat;
import shit.zen.modules.impl.render.BlockOverlay;
import shit.zen.modules.impl.render.BreakProgress;
import shit.zen.modules.impl.render.Chams;
import shit.zen.modules.impl.render.ChestESP;
import shit.zen.modules.impl.render.ChinaHat;
import shit.zen.modules.impl.render.ClickGuiModule;
import shit.zen.modules.impl.render.Clock;
import shit.zen.modules.impl.render.Compass;
import shit.zen.modules.impl.render.Halo;
import shit.zen.modules.impl.render.DamageGlow;
import shit.zen.modules.impl.render.DamageTags;
import shit.zen.modules.impl.render.ESP;
import shit.zen.modules.impl.render.FullBright;
import shit.zen.modules.impl.render.GlowESP;
import shit.zen.modules.impl.render.HitAfterimage;
import shit.zen.modules.impl.render.Indicators;
import shit.zen.modules.impl.render.InfoHud;
import shit.zen.modules.impl.render.Interface;
import shit.zen.modules.impl.render.ItemESP;
import shit.zen.modules.impl.render.ItemTags;
import shit.zen.modules.impl.render.JumpCircles;
import shit.zen.modules.impl.render.Keybinds;
import shit.zen.modules.impl.render.Keystrokes;
import shit.zen.modules.impl.render.LowHealth;
import shit.zen.modules.impl.render.Meteors;
import shit.zen.modules.impl.render.NameProtect;
import shit.zen.modules.impl.render.NameTags;
import shit.zen.modules.impl.render.NoHurtCam;
import shit.zen.modules.impl.render.Notifications;
import shit.zen.modules.impl.render.OldHitting;
import shit.zen.modules.impl.render.PotionHud;
import shit.zen.modules.impl.render.Projectiles;
import shit.zen.modules.impl.render.Radar;
import shit.zen.modules.impl.render.BlockHighlight;
import shit.zen.modules.impl.render.StrengthTimer;
import shit.zen.modules.impl.render.TargetEsp;
import shit.zen.modules.impl.render.TargetHudPanel;
import shit.zen.modules.impl.render.Tracers;
import shit.zen.modules.impl.render.Trajectories;
import shit.zen.modules.impl.render.Trails;
import shit.zen.modules.impl.render.Watermark;
import shit.zen.modules.impl.render.WindowTitle;
import shit.zen.modules.impl.render.Wings;
import shit.zen.modules.impl.render.XRay;
import shit.zen.modules.impl.render.Zoom;
import shit.zen.modules.impl.world.AntiStaff;
import shit.zen.modules.impl.world.AutoPlay;
import shit.zen.modules.impl.world.AutoTools;
import shit.zen.modules.impl.world.Debugger;
import shit.zen.modules.impl.world.Teams;
import shit.zen.modules.impl.world.WebUI;
import shit.zen.event.EventTarget;

public class ModuleManager extends ClientBase {
    private final Map<String, Module> moduleMap = new ConcurrentHashMap<>();

    public ModuleManager() {
        ZenClient.getInstance().getEventBus().register(this);
    }

    public void initModules() {
        this.register(new AntiBots());
        this.register(new AntiFireball());
        this.register(new AntiKB());
        this.register(new AutoOffHand());
        this.register(new AutoSoup());
        this.register(new AutoThrow());
        this.register(new Backtrack());
        this.register(new Critical());
        this.register(new CrystalAura());
        this.register(new FakeLag());
        this.register(new KillAura());

        this.register(new Disabler());
        this.register(new FastPlace());

        this.register(new AimAssist());
        this.register(new AutoClicker());
        this.register(new AutoRod());
        this.register(new SafeWalk());

        this.register(new CollisionSpeed());
        this.register(new NoSlow());
        this.register(new FastWeb());
        this.register(new FireballBlink());
        this.register(new Fly());
        this.register(new GuiMove());
        this.register(new HighJump());
        this.register(new NoDelay());
        this.register(new NoPush());
        this.register(new Scaffold());
        this.register(new Sprint());
        this.register(new TargetStrafe());

        this.register(new AntiTNT());
        this.register(new AntiVoid());
        this.register(new AntiWeb());
        this.register(new AutoMLG());
        this.register(new AutoWebPlace());
        this.register(new ChestStealer());
        this.register(new GhostHand());
        this.register(new Helper());
        this.register(new InventoryManager());
        this.register(new MidPearl());
        this.register(new NoFall());
        this.register(new Stuck());

        this.register(new AspectRatio());
        this.register(new BedESP());
        this.register(new BetterChat());
        this.register(new BlockOverlay());
        this.register(new BreakProgress());
        this.register(new Chams());
        this.register(new ChestESP());
        this.register(new ClickGuiModule());
        this.register(new Clock());
        this.register(new Compass());
        this.register(new DamageGlow());
        this.register(new DamageTags());
        this.register(new ESP());
        this.register(new FullBright());
        this.register(new GlowESP());
        this.register(new Halo());
        this.register(new HitAfterimage());
        this.register(new Indicators());
        this.register(new InfoHud());
        this.register(new Interface());
        this.register(new ItemESP());
        this.register(new ItemTags());
        this.register(new JumpCircles());
        this.register(new Keybinds());
        this.register(new Keystrokes());
        this.register(new LowHealth());
        this.register(new Meteors());
        this.register(new NameProtect());
        this.register(new NameTags());
        this.register(new NoHurtCam());
        this.register(new Notifications());
        this.register(new OldHitting());
        this.register(new PotionHud());
        this.register(new Projectiles());
        this.register(new Radar());
        this.register(new BlockHighlight());
        this.register(new StrengthTimer());
        this.register(new TargetEsp());
        this.register(new TargetHudPanel());
        this.register(new Watermark());
        this.register(new XRay());
        this.register(new ChinaHat());
        this.register(new Tracers());
        this.register(new Trajectories());
        this.register(new Trails());
        this.register(new Wings());
        this.register(new WindowTitle());
        this.register(new Zoom());
        this.register(new ToggleSounds());

        this.register(new AntiStaff());
        this.register(new AutoPlay());
        this.register(new AutoTools());
        this.register(new Debugger());
        this.register(new Teams());
        this.register(new WebUI());
        this.register(new MusicPlayer());
        this.register(new FreeLook());
    }

    public void register(Module module) {
        this.moduleMap.put(module.getClass().getSimpleName(), module);
        module.registerSettings();
        // Cache the snapshot: getModules() is polled from the HUD every frame,
        // and rebuilding a stream-backed list there just created garbage.
        this.moduleSnapshot = List.copyOf(this.moduleMap.values());
    }

    private volatile List<Module> moduleSnapshot = List.of();

    public Module getModule(String string) {
        Module module = null;
        for (Module module2 : this.moduleMap.values()) {
            if (!StringUtils.replace(module2.getName(), " ", "").equalsIgnoreCase(string)) continue;
            module = module2;
        }
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return module;
    }

    public <T extends Module> T getModule(Class<T> clazz) {
        Module module = clazz.cast(this.moduleMap.get(clazz.getSimpleName()));
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return (T) module;
    }

    public List<Module> getModules() {
        return this.moduleSnapshot;
    }

    public List<Module> getModulesByCategory(Category category) {
        return this.moduleMap.values().stream()
                .filter(module -> module.getCategory().equals(category))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (mc.screen == null) {
            for (Module module : this.moduleMap.values()) {
                if (module.getKey() != 0 && module.getKey() == event.getKeyCode() && event.isPressed()) {
                    module.toggle();
                }
            }
        }
    }
}
