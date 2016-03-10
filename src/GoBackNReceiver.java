import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Created by jmuia on 2016-03-09.
 */
public class GoBackNReceiver {
    private InetAddress senderAddress;
    private int senderPort;
    private int receiverPort;

    private static final int MAX_WINDOW_SIZE = 128;

    private DatagramSocket socket;

    public GoBackNReceiver(InetAddress senderAddress, int senderPort, int receiverPort) {
        this.senderAddress = senderAddress;
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
    }

    public void receiveFile(String fileName, int reliabilityNumber) throws IOException {
        FileOutputStream fos = new FileOutputStream(fileName);

        int lastAck = -1;
        boolean endOfFile = false;
        byte[] rcvBuffer = new byte[StopAndWaitUtils.MAX_PACKET_SIZE];

        int currentNumber = reliabilityNumber;

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
            int seqNum = header & 0xFF;

            byte[] body = Arrays.copyOfRange(data, 2, data.length);

            if (reliabilityNumber != 0 && --currentNumber == 0) {
                currentNumber = reliabilityNumber;
                System.out.println("Dropping packet: reliability number.");

            } else {
                int ackNum;
                if (seqNum == (lastAck + 1) % MAX_WINDOW_SIZE) {
                    fos.write(body);
                    ackNum = seqNum;
                    lastAck = (lastAck + 1) % MAX_WINDOW_SIZE;
                } else {
                    ackNum = lastAck;
                }
                // make and send ACK
                socket.send(makePacket(ackNum));
            }
        }

        fos.close();
        socket.close();
        System.out.println("File transfer completed");
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

        GoBackNReceiver gbnReceiver = new GoBackNReceiver(InetAddress.getByName(argv[0]), senderPort, recPort);

        gbnReceiver.receiveFile(argv[4], rn);
        System.exit(0);
    }
}
