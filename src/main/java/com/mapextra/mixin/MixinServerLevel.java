
package com.mapextra.mixin;

import com.mapextra.world.BorderData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel implements CollisionGetter {

    @Override
    public Iterable<VoxelShape> getBlockCollisions(@Nullable Entity entity, AABB aabb) {
        // 1. 获取原版碰撞（地形、方块）
        BlockCollisions originalCollisions = new BlockCollisions(this, entity, aabb, false, (pos, shape) -> shape);

        // 2. 准备列表
        List<VoxelShape> combined = new ArrayList<>();
        while (originalCollisions.hasNext()) {
            combined.add((VoxelShape) originalCollisions.next());
        }
        // 3. 【关键】加入我们的自定义空气墙
        // 只有当这是 ServerLevel 时才生效 (Mixin 目标本身就是 ServerLevel，所以肯定是)
        ServerLevel self = (ServerLevel) (Object) this;
        BorderData.BorderEntry entry = BorderData.get(self).getActiveEntry();

        // 传入列表，让 Data 根据 Grid 逻辑往里面加墙
        if (entry != null) {
            entry.addBorderCollisions(aabb, combined);
        }


        return combined;
    }
}
