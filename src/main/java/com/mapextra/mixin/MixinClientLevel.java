package com.mapextra.mixin;

import com.mapextra.client.ClientPosCache;
import net.minecraft.client.multiplayer.ClientLevel; // 注意包名
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.mapextra.client.ClientPosCache.*;

// 拦截客户端世界的碰撞获取
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements CollisionGetter {

    @Override
    public Iterable<VoxelShape> getBlockCollisions(@Nullable Entity entity, AABB aabb) {
        // 1. 获取原版碰撞（地形、方块）
        BlockCollisions originalCollisions = new BlockCollisions(this, entity, aabb, false, (pos, shape) -> shape);

        List<VoxelShape> combined = new ArrayList<>();
        while (originalCollisions.hasNext()) {
            combined.add((VoxelShape) originalCollisions.next());
        }

        // 2. 加入我们的自定义墙
        // 这里调用 ClientPosCache，使用刚才收到的网络数据进行预测
        if (ACTIVE_ENTRY != null) {
            ACTIVE_ENTRY.addBorderCollisions(aabb, combined);
        }
        return combined;
    }
}
