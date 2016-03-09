import java.net.DatagramPacket;
import java.util.Arrays;

/**
 * Created by jmuia on 2016-03-08.
 */
public class StopAndWaitUtils {
    public static final int EOT_INDEX = 6;
    public static final int MAX_PACKET_SIZE = 128;

    public static byte checksum(byte[] data) {
        byte checksum = 0;
        for (byte b: data) {
            checksum += (0xFF & b);
        }
        return (byte) ~checksum;
    }

    public static byte checksumFromPacketData(byte[] packet) {
        byte headerChecksum = packet[1];
        packet[1] = 0;

        byte calculatedChecksum = checksum(packet);
        packet[1] = headerChecksum;

        return calculatedChecksum;
    }

    public static boolean isPacketCorrupt(DatagramPacket packet) {
        int dataLength = packet.getLength();

        if (dataLength < 2) {
            System.out.println("Dropping corrupt packet: missing header, checksum, or both.");
            return true;
        }

        byte[] data = Arrays.copyOfRange(packet.getData(), 0, dataLength);
        byte checksum = data[1];

        if (checksum != StopAndWaitUtils.checksumFromPacketData(data)) {
            System.out.println("Dropping corrupt packet: checksum not same.");
            return true;
        }

        return false;
    }
}
