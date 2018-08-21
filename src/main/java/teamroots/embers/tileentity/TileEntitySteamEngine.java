package teamroots.embers.tileentity;

import mysticalmechanics.api.DefaultMechCapability;
import mysticalmechanics.api.MysticalMechanicsAPI;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import teamroots.embers.api.EmbersAPI;
import teamroots.embers.api.misc.ILiquidFuel;
import teamroots.embers.block.BlockSteamEngine;
import teamroots.embers.particle.ParticleUtil;
import teamroots.embers.util.Misc;

import javax.annotation.Nullable;

public class TileEntitySteamEngine extends TileEntity implements ITileEntityBase, ITickable {
    public static int NORMAL_FLUID_THRESHOLD = 10;
    public static int NORMAL_FLUID_CONSUMPTION = 4;
    public static int GAS_THRESHOLD = 40;
    public static int GAS_CONSUMPTION = 20;
    public static double MAX_POWER = 50;

    int ticksExisted = 0;
    int progress = 0;
    EnumFacing front = EnumFacing.UP;
    public FluidTank tank = new FluidTank(8000);
    public DefaultMechCapability capability = new DefaultMechCapability() {
        @Override
        public void setPower(double value, EnumFacing from) {
            if(from == null)
                super.setPower(value, null);
        }
    };
    public ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            TileEntitySteamEngine.this.markDirty();
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (TileEntityFurnace.getItemBurnTime(stack) == 0) {
                return stack;
            }
            return super.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

    };

    public TileEntitySteamEngine() {
        super();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("mech_power", capability.power);
        tag.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
        tag.setInteger("progress", progress);
        tag.setInteger("front", front.getIndex());
        tag.setTag("inventory", inventory.serializeNBT());
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("mech_power"))
            capability.power = tag.getDouble("mech_power");
        tank.readFromNBT(tag.getCompoundTag("tank"));
        if (tag.hasKey("progress")) {
            progress = tag.getInteger("progress");
        }
        inventory.deserializeNBT(tag.getCompoundTag("inventory"));
        front = EnumFacing.getFront(tag.getInteger("front"));
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(getPos(), 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == MysticalMechanicsAPI.MECH_CAPABILITY) {
            return facing == front;
        }
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return true;
        }
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == MysticalMechanicsAPI.MECH_CAPABILITY) {
            return (T) this.capability;
        }
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return (T) tank;
        }
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return (T) inventory;
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public void markDirty() {
        super.markDirty();
        Misc.syncTE(this);
    }

    @Override
    public boolean activate(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
                            EnumFacing side, float hitX, float hitY, float hitZ) {
        ItemStack heldItem = player.getHeldItem(hand);
        //TODO: Any fluid container
        if (heldItem.getItem() instanceof ItemBucket || heldItem.getItem() instanceof UniversalBucket) {
            boolean didFill = FluidUtil.interactWithFluidHandler(player, hand, tank);
            this.markDirty();
            return didFill;
        }
        return false;
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        this.invalidate();
        Misc.spawnInventoryInWorld(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, inventory);
        capability.setPower(0f, null);
        updateNearby();
        world.setTileEntity(pos, null);
    }

    public void updateNearby() {
        for (EnumFacing f : EnumFacing.values()) {
            TileEntity t = world.getTileEntity(getPos().offset(f));
            if (t != null && f == front) {
                if (t.hasCapability(MysticalMechanicsAPI.MECH_CAPABILITY, Misc.getOppositeFace(f))) {
                    t.getCapability(MysticalMechanicsAPI.MECH_CAPABILITY, Misc.getOppositeFace(f)).setPower(capability.getPower(Misc.getOppositeFace(f)), Misc.getOppositeFace(f));
                    t.markDirty();
                }
            }
        }
    }

    @Override
    public void update() {
        IBlockState state = world.getBlockState(getPos());
        if (state.getBlock() instanceof BlockSteamEngine) {
            this.front = state.getValue(BlockSteamEngine.facing);
        }
        FluidStack fluid = tank.getFluid();
        ILiquidFuel fuelHandler = EmbersAPI.getSteamEngineFuel(fluid);
        double powerGenerated = 0;
        if (fluid != null && fuelHandler != null) { //Overclocked steam power
            fluid = tank.drain(Math.min(GAS_CONSUMPTION, Math.max(fluid.amount-1,1)), false);
            if (world.isRemote) {
                spawnParticles(true);
            } else {
                powerGenerated = Misc.getDiminishedPower(fuelHandler.getPower(fluid), MAX_POWER, 1);
                tank.drain(fluid, true);
                markDirty();
            }
        } else if (progress == 0) { //Otherwise try normal power generation from water and coal
            if (!world.isRemote && !inventory.getStackInSlot(0).isEmpty() && fluid != null && fluid.getFluid() == FluidRegistry.WATER && tank.getFluidAmount() >= NORMAL_FLUID_THRESHOLD) {
                ItemStack stack = inventory.getStackInSlot(0).copy();
                stack.setCount(1);
                int fuel = TileEntityFurnace.getItemBurnTime(stack);
                if (fuel > 0) {
                    progress = fuel;
                    inventory.getStackInSlot(0).shrink(1);
                    if (inventory.getStackInSlot(0).isEmpty()) {
                        inventory.setStackInSlot(0, ItemStack.EMPTY);
                    }
                    markDirty();
                }
            }
        } else {
            progress--;
            if (tank.getFluidAmount() >= NORMAL_FLUID_CONSUMPTION) {
                if (world.isRemote) {
                    spawnParticles(false);
                } else {
                    tank.drain(NORMAL_FLUID_CONSUMPTION, true);
                    powerGenerated = 20;
                    markDirty();
                }
            } else {
                progress = 0; //Waste the rest of the fuel
            }
        }

        if (!world.isRemote && capability.getPower(null) != powerGenerated) {
            capability.setPower(powerGenerated, null);
            updateNearby();
        }
    }

    private void spawnParticles(boolean vapor) {
        for (int i = 0; i < 4; i++) {
            float offX = 0.09375f + 0.8125f * (float) Misc.random.nextInt(2);
            float offZ = 0.28125f + 0.4375f * (float) Misc.random.nextInt(2);
            if (front.getAxis() == EnumFacing.Axis.X) {
                float h = offX;
                offX = offZ;
                offZ = h;
            }

            if (vapor)
                ParticleUtil.spawnParticleVapor(world,
                        getPos().getX() + offX, getPos().getY() + 1.0f, getPos().getZ() + offZ,
                        0.025f * (Misc.random.nextFloat() - 0.5f), 0.125f * (Misc.random.nextFloat()), 0.025f * (Misc.random.nextFloat() - 0.5f),
                        72, 72, 72, 0.5f, 0.5f, 2.0f + Misc.random.nextFloat(), 24);
            else
                ParticleUtil.spawnParticleSmoke(world,
                        getPos().getX() + offX, getPos().getY() + 1.0f, getPos().getZ() + offZ,
                        0.025f * (Misc.random.nextFloat() - 0.5f), 0.125f * (Misc.random.nextFloat()), 0.025f * (Misc.random.nextFloat() - 0.5f),
                        72, 72, 72, 0.5f, 2.0f + Misc.random.nextFloat(), 24);
        }
    }
}
