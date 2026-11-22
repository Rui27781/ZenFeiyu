package shit.zen.modules.impl.render;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.opengl.GL11;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.RenderEntityEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.AntiBots;
import shit.zen.settings.impl.BooleanSetting;

/**
 * Player chams ported from NeoRaven's {@code ChamsModule}.
 *
 * <p>NeoRaven hooked Forge's {@code RenderPlayerEvent.Pre/Post}. Here the equivalent hooks are the
 * two {@link RenderEntityEvent} subclasses fired by {@code LivingEntityRendererPatch}: the
 * cancellable {@code Post} event is emitted at the head of the render call and the {@code Pre}
 * event at its tail (the names are inverted in that patch). This module only acts on players, like
 * the upstream version, and brackets their model draw with the same polygon-offset values.
 * "Hide players" only skips the cham effect (as upstream did); it does not cancel entity
 * rendering.</p>
 *
 * <p>Upstream's {@code BotCheck} is replaced by {@link AntiBots#isBot(Entity)}.</p>
 */
public class Chams extends Module {
    private final BooleanSetting ignoreBotsSetting = new BooleanSetting("Ignore bots", true);
    private final BooleanSetting hidePlayersSetting = new BooleanSetting("Hide players", false);
    private final BooleanSetting renderSelfSetting = new BooleanSetting("Render self", false);

    private final Set<Entity> rendered = new HashSet<Entity>();

    public Chams() {
        super("Chams", Category.RENDER);
    }

    @Override
    protected void onDisable() {
        this.rendered.clear();
        GL11.glPolygonOffset(1.0f, 3800000.0f);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    @EventTarget
    public void onBeforeRender(RenderEntityEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        if (this.hidePlayersSetting.getValue()) {
            return;
        }
        if (!this.renderSelfSetting.getValue() && entity == mc.player) {
            return;
        }
        if (this.ignoreBotsSetting.getValue()) {
            if (AntiBots.isBot(entity)) {
                return;
            }
            this.rendered.add(entity);
        }
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1.0f, -3800000.0f);
    }

    @EventTarget
    public void onAfterRender(RenderEntityEvent.Pre event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player) || this.hidePlayersSetting.getValue()) {
            return;
        }
        if (!this.renderSelfSetting.getValue() && entity == mc.player) {
            return;
        }
        if (this.ignoreBotsSetting.getValue() && !this.rendered.remove(entity)) {
            return;
        }
        GL11.glPolygonOffset(1.0f, 3800000.0f);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }
}
