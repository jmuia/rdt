import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Created by jmuia on 2016-03-09.
 */

public class GoBackNSender {
    private static final long TIMEOUT = 100;
    private static final int PACKET_DATA_SIZE = 124;
    private static final int MAX_WINDOW_SIZE = 128;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timerTask;



    private InetAddress receiverAddress;
    private int receiverPort;
    private int senderPort;
    private int windowSize;
    private int sendBase;
    private int nextSequenceNumber;

    private List<DatagramPacket> windowPackets;

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

        byte[] rcvBuffer = new byte[GoBackNUtils.MAX_PACKET_SIZE];

        int bytesRead;
        boolean endOfFile = false;
        boolean firstPacket = true;

        long startTime = System.nanoTime();

        nextSequenceNumber = 0;
        sendBase = 0;
        windowPackets = Collections.synchronizedList(new ArrayList<>());
        socket = new DatagramSocket(senderPort);


        while (!endOfFile || windowPackets.size() != 0) {
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
                if (GoBackNUtils.isPacketCorrupt(receivePacket)) {
                    continue;
                }

                byte[] data = receivePacket.getData();
                byte header = data[0];
                int ackNum = (int) header;

                if (isInWindow(ackNum)) {
                    cancelTimer();

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
        System.out.println(TIMEOUT + "," + fileSize + "," + windowSize + "," + Long.toString(duration));
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
            header |= (1 << GoBackNUtils.EOT_INDEX);
        }

        byte[] data = new byte[numberOfBytes+2];
        data[0] = header;
        data[1] = 0;
        System.arraycopy(buffer, 0, data, 2, numberOfBytes);

        byte checksum = GoBackNUtils.checksum(data);
        data[1] = checksum;

        return new DatagramPacket(data, data.length, receiverAddress, receiverPort);
    }

    private void cancelTimer() {
        synchronized (timer) {
            if (timerTask != null && (!timerTask.isDone() || !timerTask.isCancelled())) {
                timerTask.cancel(true);
            }
        }
    }

    private void startTimer() {
        synchronized (timer) {
            final Runnable runnable = new Runnable() {
                public void run() {
                    try {
                        // resend packet
                        synchronized (windowPackets) {
                            Iterator i = windowPackets.iterator();
                            while (i.hasNext())
                                socket.send((DatagramPacket) i.next());
                        }

                    } catch (IOException e) {
                        // handle error
                        System.err.println(e.getMessage());
                    }
                }
            };

            timerTask = timer.scheduleWithFixedDelay(runnable, TIMEOUT, TIMEOUT, TimeUnit.MILLISECONDS);
        }
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length != 5) {
            System.out.println("Usage:");
            System.out.println("java StopAndWaitSender <0> <1> <2> <3> <4>");
            System.out.println("0: host address of the receiver");
            System.out.println("1: UDP port number used by the receiver to receive data from the sender");
            System.out.println("2: UDP port number used by the sender to receive ACKs from the receiver");
            System.out.println("3: Name of the file to be transferred");
            System.out.println("4: Window size <= 128");
            System.exit(1);
        }

        int recPort = Integer.parseInt(argv[1]);
        int senderPort = Integer.parseInt(argv[2]);
        int windowSize = Integer.parseInt(argv[4]);

        if (windowSize > 128) {
            System.out.println("Window size must be <= 128");
            System.exit(1);
        }

        GoBackNSender gbnSender = new GoBackNSender(InetAddress.getByName(argv[0]), recPort, senderPort, windowSize);

        gbnSender.sendFile(argv[3]);
        System.exit(0);
    }
}
