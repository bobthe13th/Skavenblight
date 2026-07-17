package org.ratden.skavenblight.debug.mode.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public interface IClientDebugMode {
    void updateData();
    void render(Matrix4f pose, BufferBuilder buffer, Player player, Vec3 camPos);
}