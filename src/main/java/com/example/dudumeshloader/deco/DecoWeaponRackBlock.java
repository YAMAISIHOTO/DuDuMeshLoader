package com.example.dudumeshloader.deco;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IBlock;
import com.tacz.guns.api.item.IGun;
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
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 武器架方块 {@code dudumeshloader:weapon_rack}。
 *
 * <p>原理同 TACZ 的 {@code StatueBlock} + 原版物品展示框：右键手持 TACZ 枪或配件放入，
 * 空手右键取出。外观来自其方块 index 模型（复用 {@code TaczPolyMeshBlockModel} + 现有
 * poly_mesh 注入）；枪落在 index 的 {@code weapon_mount} 系骨骼、配件落在独立的
 * {@code attachment_mount} 系骨骼上（由对应 index 选项指定）。</p>
 *
 * <p>碰撞档位与 {@link DecoBlock} 共用同一套 {@link DecoCollisionType} 枚举与
 * {@link DecoCollisionRegistry}：放置时按手中物品的 {@code BlockId}（index id）反查
 * 注册表得到（该值由 {@code BlockIndexCollisionMixin} 在加载 index JSON 的
 * {@code collision} 选项时写入），并存入方块状态，无需读取方块实体即可判定形状与根块。
 * 因此枪包作者只需在武器架的 index JSON 写 {@code "collision":"a"|"b"|"c"|"slab"|"null"} 即可。</p>
 */
public class DecoWeaponRackBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DecoCollisionType> COLLISION = EnumProperty.create("collision", DecoCollisionType.class);
    public static final EnumProperty<BedPart> PART = BlockStateProperties.BED_PART;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    private static final VoxelShape SLAB_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);

    public DecoWeaponRackBlock() {
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
        // 模型正面「面朝玩家」：FACING = 玩家朝向的相反方向（指向玩家）。
        // 碰撞箱（仅 DOUBLE_B 双块）占「本体 + 玩家视角右侧一格」：
        //   第二格方向 = 玩家朝向的顺时针 90° = FACING 逆时针 90°（因 FACING 指向玩家）。
        Direction playerFacing = context.getHorizontalDirection();
        Direction direction = playerFacing.getOpposite(); // 指向玩家
        DecoCollisionType c = collisionFromItem(context.getItemInHand());
        BlockState state = this.defaultBlockState().setValue(FACING, direction).setValue(COLLISION, c);
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (c == DecoCollisionType.DOUBLE_B) {
            // 第二格（HEAD）在玩家视角右侧：FACING 逆时针 90°
            Direction right = direction.getCounterClockWise();
            BlockPos rel = pos.relative(right);
            if (!level.getBlockState(rel).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(rel)) {
                return null;
            }
            // FACING 保持指向玩家（模型正面面朝玩家），不改为延伸方向
            state = state.setValue(FACING, direction);
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
                // HEAD 块在玩家视角右侧：FACING 逆时针 90°
                BlockPos rel = pos.relative(state.getValue(FACING).getCounterClockWise());
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
                // 根块写入 id（持有武器/配件）；配对块也写 id 以便取回物品时定位根块
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof DecoWeaponRackEntity e) {
                    e.setId(id);
                }
                BlockPos paired = getPairedPos(pos, state);
                if (paired != null) {
                    BlockEntity pbe = world.getBlockEntity(paired);
                    if (pbe instanceof DecoWeaponRackEntity pe) {
                        pe.setId(id);
                    }
                }
            }
        }
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (!level.isClientSide && player.isCreative()) {
            if (c == DecoCollisionType.DOUBLE_B && state.getValue(PART) == BedPart.FOOT) {
                // HEAD 块在玩家视角右侧：FACING 逆时针 90°
                BlockPos rel = pos.relative(state.getValue(FACING).getCounterClockWise());
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
            // 连接方向 = 玩家视角右侧 = FACING 逆时针 90°；FOOT 朝该方向接 HEAD，HEAD 反之
            Direction connect = state.getValue(FACING).getCounterClockWise();
            Direction neighbour = state.getValue(PART) == BedPart.FOOT ? connect : connect.getOpposite();
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
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        // 双块的非根部分：交互路由到根块处理（武器/配件只在根块实体上）
        if (!isRoot(state)) {
            BlockPos root = getRootPos(pos, state);
            BlockState rootState = level.getBlockState(root);
            BlockEntity rootBe = level.getBlockEntity(root);
            if (rootBe instanceof DecoWeaponRackEntity) {
                return useInternal(rootState, level, root, player, hand, hit);
            }
            return InteractionResult.SUCCESS;
        }
        return useInternal(state, level, pos, player, hand, hit);
    }

    private InteractionResult useInternal(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof DecoWeaponRackEntity rack) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty() && (held.getItem() instanceof IGun || held.getItem() instanceof IAttachment)) {
                // 放入第一空槽；满架则不做任何操作
                rack.insertItem(held);
                return InteractionResult.SUCCESS;
            }
            if (held.isEmpty()) {
                if (player.isShiftKeyDown()) {
                    // 潜行 + 空手右键：切换开合状态（驱动 open/close 动画）
                    rack.setOpen(!rack.isOpen());
                } else {
                    // 空手右键：取第一把非空（先武器槽位，后配件槽位）
                    ItemStack taken = rack.extractFirst();
                    if (!taken.isEmpty()) {
                        Direction facing = state.getValue(FACING);
                        if (!player.getInventory().add(taken)) {
                            Block.popResource(level, pos.relative(facing).above(), taken);
                        }
                    }
                }
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pMovedByPiston) {
        if (!pState.is(pNewState.getBlock())) {
            BlockEntity be = pLevel.getBlockEntity(pPos);
            if (be instanceof DecoWeaponRackEntity rack) {
                rack.dropAll();
            }
            super.onRemove(pState, pLevel, pPos, pNewState, pMovedByPiston);
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COLLISION, PART, HALF);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DecoWeaponRackEntity(pos, state);
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

    public static boolean isRoot(BlockState state) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.DOUBLE_B) {
            return state.getValue(PART) == BedPart.FOOT;
        }
        if (c == DecoCollisionType.DOUBLE_C) {
            return state.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return true;
    }

    @Nullable
    private BlockPos getPairedPos(BlockPos pos, BlockState state) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.DOUBLE_B) {
            // 配对（HEAD）块在玩家视角右侧：FACING 逆时针 90°
            return pos.relative(state.getValue(FACING).getCounterClockWise());
        }
        if (c == DecoCollisionType.DOUBLE_C) {
            return pos.above();
        }
        return null;
    }

    public static BlockPos getRootPos(BlockPos pos, BlockState state) {
        DecoCollisionType c = state.getValue(COLLISION);
        if (c == DecoCollisionType.DOUBLE_B) {
            // HEAD 块在玩家视角右侧（FACING 逆时针 90°），故由 HEAD 回根需反向
            return state.getValue(PART) == BedPart.FOOT ? pos : pos.relative(state.getValue(FACING).getCounterClockWise().getOpposite());
        }
        if (c == DecoCollisionType.DOUBLE_C) {
            return state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        }
        return pos;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        BlockPos root = getRootPos(pos, state);
        BlockEntity be = level.getBlockEntity(root);
        if (be instanceof DecoWeaponRackEntity e && e.getId() != null) {
            return BlockItemBuilder.create(this).setId(e.getId()).build();
        }
        return new ItemStack(this);
    }
}
