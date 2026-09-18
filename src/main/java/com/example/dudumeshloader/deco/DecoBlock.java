package com.example.dudumeshloader.deco;

import com.tacz.guns.api.item.IBlock;
import com.tacz.guns.api.item.builder.BlockItemBuilder;
import com.tacz.guns.api.item.nbt.BlockItemDataAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 纯装饰方块 {@code dudumeshloader:block_deco}。
 *
 * <p>取消了 TACZ 工作台的开 GUI 逻辑，仅保留基于 poly_mesh / 基岩立方体模型的渲染。
 * 通过方块状态属性 {@code collision} 在四种碰撞之间切换：
 * <ul>
 *   <li>{@code a}  → 1×1×1 单块（workbench_a）</li>
 *   <li>{@code b}  → 2×1×1 横向双块（workbench_b），用 {@link BedPart}</li>
 *   <li>{@code c}  → 1×2×1 纵向双块（workbench_c），用 {@link DoubleBlockHalf}</li>
 *   <li>{@code slab} → 底部半砖碰撞</li>
 * </ul>
 *
 * <p>碰撞档位由放置时手中物品的 {@code BlockId}（index id）反查
 * {@link DecoCollisionRegistry} 得到，并写入方块状态，因此无需读取方块实体即可判定形状与根块。</p>
 */
public class DecoBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DecoCollisionType> COLLISION = EnumProperty.create("collision", DecoCollisionType.class);
    public static final EnumProperty<BedPart> PART = BlockStateProperties.BED_PART;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    private static final VoxelShape SLAB_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);

    public DecoBlock() {
        super(Properties.of().sound(SoundType.WOOD).strength(2.0F, 3.0F).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(COLLISION, DecoCollisionType.SINGLE_A)
                .setValue(PART, BedPart.FOOT)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    private DecoCollisionType collisionFromItem(ItemStack stack) {
        if (stack.getItem() instanceof IBlock ib) {
            return DecoCollisionRegistry.get(ib.getBlockId(stack));
        }
        return DecoCollisionType.SINGLE_A;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getHorizontalDirection().getOpposite();
        DecoCollisionType c = collisionFromItem(context.getItemInHand());
        BlockState state = this.defaultBlockState().setValue(FACING, direction).setValue(COLLISION, c);
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (c == DecoCollisionType.DOUBLE_B) {
            // 碰撞箱（HEAD 块延伸方向）相对玩家朝向逆时针转 90°：原为 ClockWise(playerFacing)，
            // 改为直接取玩家朝向（ClockWise 再逆时针即回到原朝向，即相对当前逆 90°）。
            Direction facing = context.getHorizontalDirection();
            BlockPos rel = pos.relative(facing);
            if (!level.getBlockState(rel).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(rel)) {
                return null;
            }
            state = state.setValue(FACING, facing);
        } else if (c == DecoCollisionType.DOUBLE_C) {
            BlockPos above = pos.above();
            if (!level.getBlockState(above).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(above)) {
                return null;
            }
        }
        return state;
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            DecoCollisionType c = state.getValue(COLLISION);
            if (c == DecoCollisionType.DOUBLE_B) {
                BlockPos rel = pos.relative(state.getValue(FACING));
                world.setBlock(rel, state.setValue(PART, BedPart.HEAD), Block.UPDATE_ALL);
                world.blockUpdated(pos, Blocks.AIR);
                state.updateNeighbourShapes(world, pos, Block.UPDATE_ALL);
            } else if (c == DecoCollisionType.DOUBLE_C) {
                BlockPos above = pos.above();
                world.setBlock(above, state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
                world.blockUpdated(pos, Blocks.AIR);
                state.updateNeighbourShapes(world, pos, Block.UPDATE_ALL);
            }
            if (stack.getItem() instanceof BlockItemDataAccessor accessor) {
                ResourceLocation id = accessor.getBlockId(stack);
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof DecoBlockEntity e) {
                    e.setId(id);
                }
            }
        }
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (!level.isClientSide && player.isCreative()) {
            if (c == DecoCollisionType.DOUBLE_B && state.getValue(PART) == BedPart.FOOT) {
                BlockPos rel = pos.relative(state.getValue(FACING));
                BlockState relState = level.getBlockState(rel);
                if (relState.is(this) && relState.getValue(PART) == BedPart.HEAD) {
                    level.setBlock(rel, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                    level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, rel, Block.getId(relState));
                }
            } else if (c == DecoCollisionType.DOUBLE_C && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
                BlockPos below = pos.below();
                BlockState belowState = level.getBlockState(below);
                if (belowState.is(this) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER) {
                    level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                    level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, below, Block.getId(belowState));
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState facingState, LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.DOUBLE_B) {
            Direction facing = state.getValue(FACING);
            Direction neighbour = state.getValue(PART) == BedPart.FOOT ? facing : facing.getOpposite();
            if (direction == neighbour) {
                return facingState.is(this) && facingState.getValue(PART) != state.getValue(PART) ? state : Blocks.AIR.defaultBlockState();
            }
        } else if (c == DecoCollisionType.DOUBLE_C) {
            if (direction.getAxis() == Direction.Axis.Y) {
                DoubleBlockHalf half = state.getValue(HALF);
                if ((half == DoubleBlockHalf.LOWER && direction == Direction.UP) || (half == DoubleBlockHalf.UPPER && direction == Direction.DOWN)) {
                    if (!facingState.is(this)) {
                        return Blocks.AIR.defaultBlockState();
                    }
                }
            }
        }
        return super.updateShape(state, direction, facingState, level, currentPos, facingPos);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // 纯装饰：吃掉交互，不打开任何 GUI。
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COLLISION, PART, HALF);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DecoBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.DESTROY;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.SLAB) {
            return SLAB_SHAPE;
        }
        if (c == DecoCollisionType.NONE) {
            return Shapes.empty();
        }
        return Shapes.block();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    public boolean isRoot(BlockState state) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.DOUBLE_B) {
            return state.getValue(PART) == BedPart.FOOT;
        }
        if (c == DecoCollisionType.DOUBLE_C) {
            return state.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return true;
    }

    public BlockPos getRootPos(BlockPos pos, BlockState state) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.DOUBLE_B) {
            return state.getValue(PART) == BedPart.FOOT ? pos : pos.relative(state.getValue(FACING).getOpposite());
        }
        if (c == DecoCollisionType.DOUBLE_C) {
            return state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        }
        return pos;
    }

    public float getRotation(BlockState state) {
        DecoCollisionType c = state.getValue(COLLISION);
        Direction facing = state.getValue(FACING);
        if (c == DecoCollisionType.DOUBLE_B) {
            // 碰撞箱（HEAD 块延伸方向）已相对玩家朝向逆时针转 90°（FACING 取玩家原始朝向），
            // 但模型朝向需保持原状（原 FACING 为 ClockWise(playerFacing)），故用 ClockWise(FACING)
            // 还原原本的模型旋转，避免模型被一起拧过去。
            return 90 - 90 * facing.getClockWise().get2DDataValue();
        }
        return 90.0F * (3 - facing.get2DDataValue()) - 90;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        BlockPos root = getRootPos(pos, state);
        BlockEntity be = level.getBlockEntity(root);
        if (be instanceof DecoBlockEntity e && e.getId() != null) {
            return BlockItemBuilder.create(this).setId(e.getId()).build();
        }
        return new ItemStack(this);
    }
}
