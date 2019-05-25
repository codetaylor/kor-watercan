package com.sudoplay.mc.watercan.modules.watercan.item;

import com.sudoplay.mc.watercan.ModWatercanConfig;
import com.sudoplay.mc.watercan.modules.watercan.ModuleWatercan;
import com.sudoplay.mc.watercan.modules.watercan.client.IWaterCanParticleSpawner;
import com.sudoplay.mc.watercan.modules.watercan.client.WaterCanParticleSpawner;
import com.sudoplay.mc.watercan.modules.watercan.network.SCPacketDispenseWatercan;
import com.sudoplay.mc.watercan.util.Util;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.dispenser.IBehaviorDispenseItem;
import net.minecraft.dispenser.IBlockSource;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public abstract class ItemWaterCanBase
    extends Item {

  public static final int MILLI_BUCKETS_PER_USE = 10;

  private static final int BLOCK_FARMLAND_MAX_MOISTURE = 7;
  private static final float FLOWER_CHANCE_SCALAR = 0.0005f;

  private final IWaterCanParticleSpawner particleSpawner;

  public ItemWaterCanBase() {

    MinecraftForge.EVENT_BUS.register(this);

    this.setMaxDamage(this.getCapacity());
    this.setMaxStackSize(1);

    if (ModWatercanConfig.CLIENT.SPAWN_WATER_PARTICLES) {
      this.particleSpawner = WaterCanParticleSpawner.INSTANCE;

    } else {
      this.particleSpawner = IWaterCanParticleSpawner.NO_OP;
    }
  }

  protected abstract int getCapacity();

  protected abstract boolean canExtinguishFire();

  protected abstract boolean canMoisturizeFarmland();

  protected abstract boolean canSpawnFlowers();

  protected abstract boolean canSpreadGrass();

  protected abstract boolean canSpreadMycelium();

  protected abstract boolean canGrowCrops();

  protected abstract boolean canGrowSaplings();

  protected abstract int getDelayModifier();

  protected abstract boolean consumeWaterSource();

  protected abstract int getRange();

  protected abstract int getFlowerChance();

  protected abstract boolean isDispensable();

  @Override
  @SideOnly(Side.CLIENT)
  public void addInformation(
      ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn
  ) {

    super.addInformation(stack, worldIn, tooltip, flagIn);

    tooltip.add(I18n.format(
        "tooltip.watercan.capacity",
        stack.getMaxDamage() - stack.getItemDamage(),
        stack.getMaxDamage()
    ));
  }

  @Override
  public boolean isEnchantable(ItemStack stack) {

    return false;
  }

  @Nonnull
  @Override
  public EnumActionResult onItemUse(
      EntityPlayer player,
      World world,
      BlockPos pos,
      EnumHand hand,
      EnumFacing facing,
      float hitX,
      float hitY,
      float hitZ
  ) {

    ItemStack itemStack = player.getHeldItem(hand);
    this.activate(itemStack, world, player, pos);
    return EnumActionResult.SUCCESS;
  }

  public void activate(ItemStack itemStack, World world, @Nullable EntityPlayer player, BlockPos pos) {

    if (this.getMaxDamage() > 0) {

      // water check
      EnumActionResult waterCheckResult = null;

      // player will be null when the watercan is activated from a dispenser
      // we only want to do a want to do a water check when the can is being
      // activated by a player (or fake player)
      if (player != null) {
        waterCheckResult = this.checkRefill(itemStack, world, player).getType();
      }

      if (waterCheckResult != null) {

        if (waterCheckResult == EnumActionResult.SUCCESS) {
          return; // refill success

        } else if (waterCheckResult == EnumActionResult.FAIL) {
          return; // trying to get water, but can is full
        }
      }

      // capacity check
      if (this.getMaxDamage() - itemStack.getItemDamage() < MILLI_BUCKETS_PER_USE) {
        return; // nope
      }

      int itemDamage = itemStack.getItemDamage() + MILLI_BUCKETS_PER_USE;
      itemDamage = Math.min(itemDamage, this.getMaxDamage());
      itemStack.setItemDamage(itemDamage);
    }

    this.waterBlockRange(
        world,
        pos.getX() + 0.5,
        pos.getY() + 0.5,
        pos.getZ() + 0.5
    );
  }

  @Nonnull
  @Override
  public ActionResult<ItemStack> onItemRightClick(
      World world,
      EntityPlayer player,
      EnumHand handIn
  ) {

    return this.checkRefill(player.getHeldItem(handIn), world, player);
  }

  @Nonnull
  private ActionResult<ItemStack> checkRefill(
      @Nonnull ItemStack itemStack,
      World world,
      EntityPlayer player
  ) {

    RayTraceResult rayTraceResult = this.rayTrace(world, player, true);

    // rayTraceResult can be null
    //noinspection ConstantConditions
    if (rayTraceResult != null
        && rayTraceResult.typeOfHit == RayTraceResult.Type.BLOCK) {
      BlockPos blockPos = rayTraceResult.getBlockPos();
      IBlockState blockState = world.getBlockState(blockPos);

      if (blockState.getBlock().getMaterial(blockState) == Material.WATER) {

        if (itemStack.getItemDamage() == 0) {
          return ActionResult.newResult(EnumActionResult.FAIL, itemStack);
        }

        if (!world.isRemote) {
          int damage = itemStack.getItemDamage() - 1000;
          damage = Math.max(0, damage);
          itemStack.setItemDamage(damage);
          world.playSound(
              player,
              player.posX,
              player.posY,
              player.posZ,
              SoundEvents.ITEM_BUCKET_FILL,
              SoundCategory.PLAYERS,
              1,
              1
          );

          if (this.consumeWaterSource()) {
            world.setBlockToAir(blockPos);
          }
        }
        return ActionResult.newResult(EnumActionResult.SUCCESS, itemStack);
      }
    }

    return ActionResult.newResult(EnumActionResult.PASS, itemStack);
  }

  private void waterBlockRange(
      World world,
      double x,
      double y,
      double z
  ) {

    if (world.isRemote) {
      // particles on the client
      this.spawnParticles(world, x, y, z);

    } else {
      // effect on the server
      int range = this.getRange();
      int flowerChance = this.getFlowerChance();
      this._waterBlockRange(world, x, y, z, range, flowerChance * FLOWER_CHANCE_SCALAR);
    }
  }

  public void spawnParticles(World world, double x, double y, double z) {

    this.particleSpawner.spawnParticles(world, x, y, z, this.getRange());
  }

  private void _waterBlockRange(
      World world,
      double posX,
      double posY,
      double posZ,
      int range,
      float flowerChance
  ) {

    BlockPos pos;

    int blockX = (int) Math.floor(posX);
    int blockY = (int) Math.floor(posY);
    int blockZ = (int) Math.floor(posZ);

    for (int x = blockX - range; x <= blockX + range; x++) {

      for (int y = blockY - range; y <= blockY + range; y++) {

        for (int z = blockZ - range; z <= blockZ + range; z++) {
          pos = new BlockPos(x, y, z);
          IBlockState blockState = world.getBlockState(pos);
          Block block = blockState.getBlock();

          // skip air blocks
          if (!world.isAirBlock(pos)) {
            this.waterBlock(world, flowerChance, pos, blockState, block);
          }
        }
      }
    }
  }

  private void waterBlock(
      World world,
      float flowerChance,
      BlockPos pos,
      IBlockState blockState,
      Block block
  ) {

    int blockUpdateDelay;

    // put out fire
    if (this.canExtinguishFire()
        && block == Blocks.FIRE) {
      world.setBlockToAir(pos);
    }

    // moisturize farmland
    if (this.canMoisturizeFarmland()
        && block == Blocks.FARMLAND
        && blockState.getValue(BlockFarmland.MOISTURE) < BLOCK_FARMLAND_MAX_MOISTURE) {
      world.setBlockState(
          pos,
          block.getDefaultState().withProperty(BlockFarmland.MOISTURE, BLOCK_FARMLAND_MAX_MOISTURE),
          3
      );
    }

    if (this.canSpawnFlowers()
        && block == Blocks.GRASS) {
      // chance to spawn flowers on a grass block with air above it
      BlockPos up = pos.up();

      if (world.isAirBlock(up)
          && Util.RANDOM.nextFloat() < flowerChance) {
        world.getBiomeForCoordsBody(pos).plantFlower(world, Util.RANDOM, up);
      }
    }

    blockUpdateDelay = getBlockUpdateDelay(blockState, block);

    if (blockUpdateDelay > 0 && block.getTickRandomly()) {
      world.scheduleBlockUpdate(pos, block, Util.RANDOM.nextInt(blockUpdateDelay), 0);
    }
  }

  private int getBlockUpdateDelay(
      IBlockState blockState,
      Block block
  ) {

    int delay = -1;
    int delayModifier = this.getDelayModifier();

    if (this.canSpreadGrass()
        && block == Blocks.GRASS) {
      delay = delayModifier;

    } else if (this.canSpreadMycelium()
        && block == Blocks.MYCELIUM) {
      delay = delayModifier;

    } else if (this.canGrowCrops()
        && block == Blocks.WHEAT) {
      delay = (int) (2.0f * delayModifier);

    } else if (this.canGrowSaplings()
        && block instanceof BlockSapling) {
      delay = (int) (2.5f + delayModifier);

    } else if (this.canGrowCrops()
        && block instanceof IPlantable
        || block instanceof IGrowable) {
      delay = (int) (2.0f + delayModifier);

    } else if (this.canSpreadGrass()
        && block.getMaterial(blockState) == Material.GRASS) {
      delay = delayModifier;
    }
    return delay;
  }

  @Override
  public int getRGBDurabilityForDisplay(ItemStack stack) {

    return 0x4466FF;
  }

  public static class DispenserBehavior
      implements IBehaviorDispenseItem {

    @Nonnull
    @Override
    public ItemStack dispense(@Nonnull IBlockSource source, @Nonnull ItemStack stack) {

      Item item = stack.getItem();

      if (item instanceof ItemWaterCanBase
          && ((ItemWaterCanBase) item).isDispensable()) {

        World world = source.getWorld();
        BlockPos pos = source.getBlockPos();
        IBlockState blockState = world.getBlockState(pos);

        if (!world.isRemote
            && blockState.getBlock() instanceof BlockDispenser) {

          EnumFacing facing = blockState.getValue(BlockDispenser.FACING);
          BlockPos offset = pos.offset(facing, ((ItemWaterCanBase) item).getRange() + 1);
          ItemStack copy = stack.copy();
          ((ItemWaterCanBase) item).activate(stack, world, null, offset);

          // client packet
          int dimension = world.provider.getDimension();
          SCPacketDispenseWatercan packet = new SCPacketDispenseWatercan(copy, offset);
          ModuleWatercan.PACKET_SERVICE.sendToAllAround(packet, dimension, offset);
        }
      }

      return stack;
    }
  }
}
