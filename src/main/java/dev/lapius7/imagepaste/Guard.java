package dev.lapius7.imagepaste;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 貼り付けた額縁を、コマンド以外の方法で壊されないよう守り、失われたら記録を整える。 */
final class Guard implements Listener {
    private final ImagePastePlugin pl;

    Guard(ImagePastePlugin pl) { this.pl = pl; }

    private boolean enabled() { return pl.getConfig().getBoolean("protection.enabled", true); }

    private boolean managed(Entity e) {
        return e instanceof ItemFrame && (e.getPersistentDataContainer().has(pl.pasteKey, PersistentDataType.STRING)
                || pl.store.byFrame(e.getUniqueId()) != null);
    }

    private boolean mayBreak(Player p) {
        return pl.getConfig().getBoolean("protection.allow-break-permission", true) && p.hasPermission("imagepaste.break");
    }

    /** 許可された人が壊した: ドロップなしで消し、記録を整える */
    private void breakQuietly(ItemFrame f) {
        UUID id = f.getUniqueId();
        f.setFixed(false);
        f.setItem(null, false);
        f.remove();
        pl.frameLost(id);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!enabled() || !managed(e.getEntity())) return;
        if (e.getCause() == HangingBreakEvent.RemoveCause.DEFAULT && !(e instanceof HangingBreakByEntityEvent)) return;
        if (e instanceof HangingBreakByEntityEvent be && be.getRemover() instanceof Player p) {
            e.setCancelled(true);
            if (mayBreak(p)) breakQuietly((ItemFrame) e.getEntity());
            else pl.lang.send(p, "protected");
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!enabled() || !managed(e.getEntity())) return;
        e.setCancelled(true);
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity s) d = s;
            if (d instanceof Player p) {
                if (mayBreak(p)) breakQuietly((ItemFrame) e.getEntity());
                else pl.lang.send(p, "protected");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (enabled() && managed(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (enabled() && managed(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameChange(PlayerItemFrameChangeEvent e) {
        if (enabled() && managed(e.getItemFrame())) e.setCancelled(true);
    }

    /** 保護をすり抜けた消失 (/kill、他プラグインなど)。プラグイン自身の削除・アンロード・退出は除く */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof ItemFrame)) return;
        switch (e.getCause()) {
            case DEATH, EXPLODE, DESPAWN, HIT, OUT_OF_WORLD, DROP, PICKUP, ENTER_BLOCK, TRANSFORMATION, MERGE -> {}
            default -> { return; }
        }
        if (pl.store.byFrame(e.getEntity().getUniqueId()) != null) pl.frameLost(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        verify(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ(), e.getEntities());
    }

    /** チャンクの読み込み時に、記録と実体を突き合わせる */
    private void verify(World w, int cx, int cz, java.util.List<Entity> ents) {
        Set<UUID> present = new HashSet<>();
        for (Entity en : ents) {
            if (!(en instanceof ItemFrame)) continue;
            if (!en.getPersistentDataContainer().has(pl.pasteKey, PersistentDataType.STRING)) continue;
            if (pl.store.byFrame(en.getUniqueId()) == null) {
                Entity o = en;
                Bukkit.getScheduler().runTask(pl, () -> { if (o instanceof ItemFrame f) { f.setFixed(false); f.setItem(null, false); f.remove(); } });
            } else present.add(en.getUniqueId());
        }
        for (Store.Paste p : pl.store.all()) {
            if (!p.world.equals(w.getName())) continue;
            for (Store.Frame f : p.frames)
                if ((f.x() >> 4) == cx && (f.z() >> 4) == cz && !present.contains(f.uuid())) pl.frameLost(f.uuid());
        }
    }

    /** 起動時: すでに読み込まれているチャンクを検査する */
    void sweepLoaded() {
        for (World w : Bukkit.getWorlds())
            for (Chunk c : w.getLoadedChunks()) verify(w, c.getX(), c.getZ(), java.util.Arrays.asList(c.getEntities()));
    }
}
