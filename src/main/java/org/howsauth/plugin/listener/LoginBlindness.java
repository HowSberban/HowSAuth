package org.howsauth.plugin.listener;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;

/**
 * 登录前失明效果的施加与移除。
 * <p>
 * 开关开启时施加无限时长失明（无颗粒无图标，重复挂起幂等）；关闭时移除。
 * 登录/注册成功、reload、加入残留清理、未登录退出共用同一出口。
 */
final class LoginBlindness {

    private final HowSAuth plugin;

    LoginBlindness(HowSAuth plugin) {
        this.plugin = plugin;
    }

    void apply(Player player) {
        if (plugin.config().protectionMisc().blindnessEnabled()) {
            if (Debug.on()) {
                Debug.log("flow", "apply login blindness for %s", player.getName());
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            clear(player);
        }
    }

    void clear(Player player) {
        if (Debug.on()) {
            Debug.log("flow", "clear login blindness for %s", player.getName());
        }
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }
}
