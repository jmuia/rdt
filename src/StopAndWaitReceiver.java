import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Created by jmuia on 2016-03-08.
 */
public class StopAndWaitReceiver {
    private InetAddress senderAddress;
    private int senderPort;
    private int receiverPort;

    private DatagramSocket socket;

    public StopAndWaitReceiver(InetAddress senderAddress, int senderPort, int receiverPort) {
        this.senderAddress = senderAddress;
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
    }

    public void receiveFile(String fileName, int reliabilityNumber) throws IOException {
        FileOutputStream fos = new FileOutputStream(fileName);

        int lastSeqNum = -1;
        boolean endOfFile = false;
        byte[] rcvBuffer = new byte[StopAndWaitUtils.MAX_PACKET_SIZE];

        socket = new DatagramSocket(receiverPort);

        while (!endOfFile) {
            // receive packet
            DatagramPacket receivePacket = new DatagramPacket(rcvBuffer, rcvBuffer.length);
            socket.receive(receivePacket);

            if (StopAndWaitUtils.isPacketCorrupt(receivePacket)) {
                continue;
            }

            byte[] data = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());
            byte header = data[0];

            endOfFile = (header >> StopAndWaitUtils.EOT_INDEX & 1) == 1;

            header &= ~(1 << StopAndWaitUtils.EOT_INDEX);
            int seqNum = (int) header;

            byte[] body = Arrays.copyOfRange(data, 2, data.length);

            if (shouldDropPacket(reliabilityNumber)) {
                // System.out.println("Dropping packet: reliability number.");

            } else {
                if (lastSeqNum == -1 || seqNum != lastSeqNum) {
                    fos.write(body);
                }
                // make and send ACK
                socket.send(makePacket(seqNum));
                lastSeqNum = seqNum;
            }
        }

        fos.close();
        socket.close();
        System.out.println("File transfer completed");
    }

    private boolean shouldDropPacket(int rn) {
        if (rn < 1) { return false; }
        double random = Math.random();
        return random <= (1 / rn);
    }

    private DatagramPacket makePacket(int packetNumber) {
        byte[] data = { (byte) packetNumber, 0 };
        byte checksum = StopAndWaitUtils.checksum(data);
        data[1] = checksum;
        return new DatagramPacket(data, data.length, senderAddress, senderPort);
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length != 5) {
            System.out.println("Usage:");
            System.out.println("java StopAndWaitReceiver <0> <1> <2> <3> <4>");
            System.out.println("0: host address of the sender");
            System.out.println("1: UDP port number used by the sender to receive data from the receiver");
            System.out.println("2: UDP port number used by the receiver to receive ACKs from the sender");
            System.out.println("3: Reliability number");
            System.out.println("4: Name of the file to write received data");
            System.exit(1);
        }

        int senderPort = Integer.parseInt(argv[1]);
        int recPort = Integer.parseInt(argv[2]);
        int rn = Integer.parseInt(argv[3]);

        StopAndWaitReceiver swReceiver = new StopAndWaitReceiver(InetAddress.getByName(argv[0]), senderPort, recPort);

        swReceiver.receiveFile(argv[4], rn);
        System.exit(0);
    }
}
