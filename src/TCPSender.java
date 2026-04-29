import java.net.*;
import java.io.*;

public class TCPSender {
    private int port;
    private String receiverIP;
    private int receiverPort;
    private String fileName;
    private int mtu;
    private int sws;

    private DatagramSocket socket;
    private InetAddress receiverAddress;

    private int bytesSent = 0;
    private int packetsSent = 0;
    private int retransmissions = 0;
    private int dupAcks = 0;
    private int seqNum = 0;
    private int windowStart = 1; // oldest unACKed seq number

    private TCPPacket[] window; // sent but unACKed
    private long[] sendTimes;   // when each was sent (for timeout)

    // timeout computation (2.2)
    private double ertt = -1;  // -1 means not yet initialized
    private double edev = 0;
    private double timeout = 5000000000L; // 5 seconds (nano)

    public TCPSender(int port, String receiverIP, int receiverPort, String fileName, int mtu, int sws) {
        this.port = port;
        this.receiverIP = receiverIP;
        this.receiverPort = receiverPort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
    }

    public void send() {
        window = new TCPPacket[sws];
        sendTimes = new long[sws];

        try {
            socket = new DatagramSocket(port);
            receiverAddress = InetAddress.getByName(receiverIP);

            establishConnection();
            manageData();
            terminateConnection();

            System.out.printf("%.0fMb %d %d %d %d %d\n",
                bytesSent / 1e6, packetsSent, 0, 0, retransmissions, dupAcks);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    private void establishConnection() {
        try {
            // send a (blank) SYN packet to initiate handshake
            TCPPacket syn = new TCPPacket(0, 0, System.nanoTime(),
                true, false, false, new byte[0]);
            sendPacket(syn);

            // wait for (blank) SYN-ACK response
            TCPPacket synAck = receivePacket();
            if (synAck == null || !synAck.isSynFlag() || !synAck.isAckFlag()) {
                System.out.println("Failed to establish connection (SYN-ACK not received)");
                return;
            }
            synAck.printSummary("rcv");

            // send (blank) ACK to complete 3-way handshake
            TCPPacket ack = new TCPPacket(1, synAck.getAck(), synAck.getTimestamp(),
                false, true, false, new byte[0]);
            sendPacket(ack);
            seqNum++;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void manageData() {
        try (FileInputStream in = new FileInputStream(fileName)) {
            byte[] chunk = new byte[mtu];
            int bytesRead;
            boolean done = false;
            int numInFlight = 0;

            while (!done || numInFlight > 0) {

                while (!done && numInFlight < sws) {
                    bytesRead = in.read(chunk);
                    if (bytesRead == -1) {
                        done = true;
                        break;
                    }

                    byte[] data = new byte[bytesRead];
                    System.arraycopy(chunk, 0, data, 0, bytesRead);

                    TCPPacket packet = new TCPPacket(seqNum, 0, System.nanoTime(),
                        false, true, false, data);
                    sendPacket(packet);

                    int slot = (seqNum / mtu) % sws;
                    window[slot] = packet;
                    sendTimes[slot] = System.nanoTime();

                    seqNum += bytesRead;
                    bytesSent += bytesRead;
                    numInFlight++;
                }

                socket.setSoTimeout((int)(timeout / 1000000));
                
                TCPPacket ack = receivePacket();
                if (ack == null) {
                    System.out.println("Timeout - retransmitting window");
                    retransmitWindow(numInFlight);
                    continue;
                }
                
                ack.printSummary("rcv");

                if (ack.getAck() > windowStart) {
                    updateTimeout(ack.getTimestamp());
                    numInFlight -= (ack.getAck() - windowStart) / mtu;
                    windowStart = ack.getAck();
                    dupAcks = 0;
                } else if (ack.getAck() == windowStart) {
                    dupAcks++;
                    if (dupAcks >= 3) {
                        System.out.println("3 duplicate ACKs - fast retransmit");
                        retransmitWindow(numInFlight);
                        dupAcks = 0;
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // mirrors establishConnection()
    private void terminateConnection() {
        try {
            // send a FIN packet to initiate ending handshake
            TCPPacket fin = new TCPPacket(seqNum, 0, System.nanoTime(), false, false, true, new byte[0]);
            sendPacket(fin);

            // wait for FIN-ACK
            TCPPacket finAck = receivePacket();
            if (finAck == null || !finAck.isFinFlag() || !finAck.isAckFlag()) {
                System.out.println("Expected FIN-ACK, termination failed.");
                return;
            }
            finAck.printSummary("rcv");

            // send final ACK
            TCPPacket ack = new TCPPacket(finAck.getAck(), finAck.getSeq() + 1, finAck.getTimestamp(),
                false, true, false, new byte[0]);
            sendPacket(ack);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendPacket(TCPPacket packet) {
        try {
            byte[] data = packet.serialize();
            DatagramPacket dp = new DatagramPacket(data, data.length, receiverAddress, receiverPort);
            socket.send(dp);
            packetsSent++;
            packet.printSummary("snd");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void retransmitWindow(int numInFlight) {
        retransmissions += numInFlight;
        for (int i = 0; i < numInFlight; i++) {
            int slot = ((windowStart / mtu) + i) % sws;
            if (window[slot] != null) {
                sendPacket(window[slot]);
            }
        }
    }

    private void updateTimeout(long timestamp) {
        double srtt = System.nanoTime() - timestamp;
        if (ertt < 0) {
            // first ACK
            ertt = srtt;
            edev = 0;
            timeout = 2 * ertt;
        } else {
            double sdev = Math.abs(srtt - ertt);
            ertt = 0.875 * ertt + 0.125 * srtt;
            edev = 0.75  * edev + 0.25  * sdev;
            timeout = ertt + 4 * edev;
        }
    }

    private TCPPacket receivePacket() {
        try {
            byte[] buffer = new byte[mtu]; // ACK only
            DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
            
            socket.receive(dp);
            return TCPPacket.deserialize(dp.getData());
            
        } catch (SocketTimeoutException e) {
            System.out.println("Receive timed out.");
            return null; 
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}