package numunun.bodycamcomponent.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import numunun.bodycamcomponent.BodyCamComponent;

import java.util.Optional;

public final class BodyCamNetwork {
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BodyCamComponent.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private BodyCamNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, DownedSyncPacket.class,
                DownedSyncPacket::encode, DownedSyncPacket::decode, DownedSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, DominationSyncPacket.class,
                DominationSyncPacket::encode, DominationSyncPacket::decode, DominationSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, PingRequestPacket.class,
                PingRequestPacket::encode, PingRequestPacket::decode, PingRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, PingPacket.class,
                PingPacket::encode, PingPacket::decode, PingPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4, IntroPacket.class,
                IntroPacket::encode, IntroPacket::decode, IntroPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(5, OsdPacket.class,
                OsdPacket::encode, OsdPacket::decode, OsdPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, AbilitySyncPacket.class,
                AbilitySyncPacket::encode, AbilitySyncPacket::decode, AbilitySyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}