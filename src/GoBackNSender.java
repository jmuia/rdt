import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by jmuia on 2016-03-09.
 */

public class GoBackNSender {
    private static final long TIMEOUT = 100;
    private static final int PACKET_DATA_SIZE = 124;
    private static final int MAX_WINDOW_SIZE = 128;

    private Timer timer = new Timer();
    private TimerTask timerTask;

    private InetAddress receiverAddress;
    private int receiverPort;
    private int senderPort;
    private int windowSize;
    private int sendBase;
    private int nextSequenceNumber;

    private ArrayList<DatagramPacket> windowPackets;

    private DatagramSocket socket;

    public GoBackNSender(InetAddress receiverAddress, int receiverPort, int senderPort, int windowSize) {
        this.receiverAddress = receiverAddress;
        this.receiverPort = receiverPort;
        this.senderPort = senderPort;
        this.windowSize = windowSize;
    }

    public void sendFile(String fileName) throws IOException {

        File f = new File(fileName);
        long fileSize = f.length();

        FileInputStream fis = new FileInputStream(fileName);

        byte[] rcvBuffer = new byte[StopAndWaitUtils.MAX_PACKET_SIZE];

        int bytesRead;
        boolean endOfFile = false;
        boolean firstPacket = true;

        long startTime = System.nanoTime();

        nextSequenceNumber = 0;
        sendBase = 0;
        windowPackets = new ArrayList<>();
        socket = new DatagramSocket(senderPort);

        while (!endOfFile) {

            while (isInWindow(nextSequenceNumber) && !endOfFile) {
                byte[] fileBuffer = new byte[PACKET_DATA_SIZE];
                // read bytes
                bytesRead = fis.read(fileBuffer);

                // check for EOF
                if (bytesRead == -1 || bytesRead < fileBuffer.length) {
                    bytesRead = Math.max(0, bytesRead);
                    endOfFile = true;
                }

                // make packet
                DatagramPacket packet = makePacket(nextSequenceNumber, fileBuffer, bytesRead, endOfFile);
                windowPackets.add(packet);

                // send packet
                socket.send(packet);

                if (firstPacket) {
                    // start timer
                    startTimer();
                    firstPacket = false;
                }

                nextSequenceNumber = (nextSequenceNumber + 1) % MAX_WINDOW_SIZE;
            }

            // wait for ACK
            boolean gotAck = false;
            do {
                DatagramPacket receivePacket = new DatagramPacket(rcvBuffer, rcvBuffer.length);
                socket.receive(receivePacket);

                // validate ack
                if (StopAndWaitUtils.isPacketCorrupt(receivePacket)) {
                    continue;
                }

                byte[] data = receivePacket.getData();
                byte header = data[0];
                int ackNum = (int) header;

                if (isInWindow(ackNum)) {
                    timerTask.cancel();

                    synchronized (windowPackets) {
                        while (sendBase != ackNum) {
                            windowPackets.remove(0);
                            sendBase = (sendBase + 1) % MAX_WINDOW_SIZE;
                        }
                        windowPackets.remove(0);
                    }

                    sendBase = (sendBase + 1) % MAX_WINDOW_SIZE;
                    startTimer();
                    gotAck = true;

                }
            } while(!gotAck);
        }

        fis.close();
        socket.close();

        long endTime = System.nanoTime();
        long duration = (endTime - startTime);

        System.out.println();
        System.out.println("~~File Transfer Completed~~");
        System.out.println("File Name: " + fileName);
        System.out.println("File Size: " + fileSize + " bytes");
        System.out.println("Transfer Time: " + Long.toString(duration) + " nanoseconds");
        System.out.println("Timeout Length: " + TIMEOUT + " milliseconds");
        System.out.println();
    }

    private boolean isInWindow(int value) {
        if (sendBase + windowSize < MAX_WINDOW_SIZE) {
            return (sendBase <= value) && (value <= sendBase + windowSize);
        } else {
            return (sendBase <= value && value < MAX_WINDOW_SIZE) || (0 <= value && value <= (sendBase + windowSize) % MAX_WINDOW_SIZE);
        }
    }

    private DatagramPacket makePacket(int packetNumber, byte[] buffer, int numberOfBytes, boolean endOfFile) {
        byte header = (byte) packetNumber;

        if (endOfFile) {
            header |= (1 << StopAndWaitUtils.EOT_INDEX);
        }

        byte[] data = new byte[numberOfBytes+2];
        data[0] = header;
        data[1] = 0;
        System.arraycopy(buffer, 0, data, 2, numberOfBytes);

        byte checksum = StopAndWaitUtils.checksum(data);
        data[1] = checksum;

        return new DatagramPacket(data, data.length, receiverAddress, receiverPort);
    }

    private void startTimer() {
        // set timer
        timerTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    // resend packet
                    synchronized (windowPackets) {
                        for (DatagramPacket p: windowPackets) {
                            socket.send(p);
                        }
                    }

                } catch (IOException e) {
                    // handle error
                    System.out.println(e.getMessage());
                }
                // reset timer
                startTimer();
            }
        };
        timer.schedule(timerTask, TIMEOUT);
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length != 5) {
            System.out.println("Usage:");
            System.out.println("java StopAndWaitSender <0> <1> <2> <3> <4>");
            System.out.println("0: host address of the receiver");
            System.out.println("1: UDP port number used by the receiver to receive data from the sender");
            System.out.println("2: UDP port number used by the sender to receive ACKs from the receiver");
            System.out.println("3: Name of the file to be transferred");
            System.out.println("4: Window size");
            System.exit(1);
        }

        int recPort = Integer.parseInt(argv[1]);
        int senderPort = Integer.parseInt(argv[2]);
        int windowSize = Integer.parseInt(argv[4]);

        GoBackNSender gbnSender = new GoBackNSender(InetAddress.getByName(argv[0]), recPort, senderPort, windowSize);

        gbnSender.sendFile(argv[3]);
        System.exit(0);
    }
}
