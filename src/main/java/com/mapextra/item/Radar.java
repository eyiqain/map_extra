package com.mapextra.item;

import com.mapextra.init.ModSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;

import java.util.List;

public class Radar extends Item {
    //默认50米
    public static int SEARCH_RANGE = 50;
    //默认冷却3秒
    public static int COOLDOWN_TICKS = 60;

    public Radar(Properties properties){
        super(properties);//把设置传给父类帮我们处理
    }
    //重写覆盖父类方法右键使用行为，用我们自己的
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand UsedHand){
        //该内容只在服务器使用
        if (!level.isClientSide){
            //定义搜索区域，用碰撞盒（轴对齐包围盒）AABB,inflate获得100x100x100的立方体搜索范围
            AABB searchArea = player.getBoundingBox().inflate((double)SEARCH_RANGE);

            //获取实体列表，用List<Player>泛型集合类型存储玩家，防止存储其他实体,searchArea写进去防止搜索到区域外,p -> p != player防止把自己放进集合里
            List<Player> players = level.getEntitiesOfClass(Player.class,searchArea,p -> p != player);

            //先定义最近的人和距离变量为空
            Player nearestTarget = null;
            double minDistance = Double.MAX_VALUE;// 初始设为无限大
            //寻找最近的人,不能一下子全获得，抓捕者会看乱，所以只留最近的就行
            for (Player target : players){
                //我们只是比大小，所以不需要开根号，根号性能消耗大，直接比平方就好
                double distance = player.distanceToSqr(target);
                //判断距离最小的那个人是谁，把他设为目标
                if(distance < minDistance){
                    //让最小的距离成为当前的距离
                    minDistance = distance;
                    //让最近的人成为新目标
                    nearestTarget = target;
                }
            }
            //得出最后的结果
            //如果搜索距离最近的人存在
            if (nearestTarget != null){
                //这里才开根号显示具体多少米，因为要显示给玩家，这个性能损耗是必须的
                double actualDistance = Math.sqrt(minDistance);
                // 播放“锁定”音效
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        ModSounds.RADAR.get(), SoundSource.PLAYERS, 1.0F, 1.0F);

                //发送消息给玩家
                player.displayClientMessage(Component.literal("§e🔍发现目标: §f" + nearestTarget.getName().getString() +
                        " §7(距离: " + String.format("%.1f", actualDistance) + "m)"), true);
                //发光3
                nearestTarget.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0));
                //添加冷却
                player.getCooldowns().addCooldown(this,COOLDOWN_TICKS);

            }else {
                //声音更低沉
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        ModSounds.RADAR.get(), SoundSource.PLAYERS, 1.0F, 0.5F);
                //再增加另一个条件，没有搜索到也发送消息
                player.displayClientMessage(Component.literal("§c❌范围内没有其他玩家"), true);
            }
        }
        //告诉游戏这个物品被使用了，然后手臂挥动
        return InteractionResultHolder.success(player.getItemInHand(UsedHand));
    }
}
