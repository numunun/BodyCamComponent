package numunun.bodycamcomponent.item;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import numunun.bodycamcomponent.game.Selections;

public class RegionWandItem extends Item {
    public RegionWandItem() {
        super(new Item.Properties().stacksTo(1));
    }

    // 좌클릭으로 블록이 부서지지 않게
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    // 우클릭: 지점 2
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!ctx.getLevel().isClientSide && ctx.getPlayer() instanceof ServerPlayer p) {
            Selections.setPos(p, 1, ctx.getClickedPos());
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }
}