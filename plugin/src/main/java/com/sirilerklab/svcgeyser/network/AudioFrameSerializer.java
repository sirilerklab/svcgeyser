package com.sirilerklab.svcgeyser.network;

import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Serializes a SoundPacket to the binary downlink wire format defined in docs/DOCUMENT.md §5:
 *   [u8 type=0x02][16B senderUuid][u8 flags][f32 x,y,z,distance]?[opus payload]
 *
 * flags: 0x01 = whisper, 0x02 = static (group), 0x04 = has_spatial (x/y/z/distance follow)
 */
public class AudioFrameSerializer {

    private static final byte DOWNLINK  = 0x02;
    private static final byte F_WHISPER = 0x01;
    private static final byte F_STATIC  = 0x02;
    private static final byte F_SPATIAL = 0x04;

    public static byte[] serialize(SoundPacket packet) {
        UUID sender = packet.getSender();
        byte[] opus  = packet.getOpusEncodedData();

        byte    flags      = 0;
        boolean hasSpatial = false;
        float   x = 0, y = 0, z = 0, distance = 0;

        if (packet instanceof LocationalSoundPacket lsp) {
            flags      |= F_SPATIAL;
            hasSpatial  = true;
            x           = (float) lsp.getPosition().getX();
            y           = (float) lsp.getPosition().getY();
            z           = (float) lsp.getPosition().getZ();
            distance    = lsp.getDistance();
        } else if (packet instanceof EntitySoundPacket esp) {
            if (esp.isWhispering()) flags |= F_WHISPER;
        } else if (packet instanceof StaticSoundPacket) {
            flags |= F_STATIC;
        }

        // 1 (type) + 16 (uuid) + 1 (flags) [+ 16 (spatial)] + opus
        int size = 18 + (hasSpatial ? 16 : 0) + opus.length;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        buf.put(DOWNLINK);
        buf.putLong(sender.getMostSignificantBits());
        buf.putLong(sender.getLeastSignificantBits());
        buf.put(flags);

        if (hasSpatial) {
            buf.putFloat(x);
            buf.putFloat(y);
            buf.putFloat(z);
            buf.putFloat(distance);
        }

        buf.put(opus);
        return buf.array();
    }
}
