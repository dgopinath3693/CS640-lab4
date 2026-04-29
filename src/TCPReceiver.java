import java.net.*;
import java.io.FileOutputStream;

public class TCPReceiver {
    private int port;
    private String fileName;
    private int mtu;
    private int sws;
    
    private SocketAddress senderAddress;

    public TCPReceiver(int port, String fileName, int mtu, int sws) {
        this.port = port;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
    }

    public void receive() {
        System.out.println("TCPReceiver initialized. Listening on port " + port + " to write to " + fileName);

        try(DatagramSocket socket = new DatagramSocket(port); 
            FileOutputStream o = new FileOutputStream(fileName)) 
            {
                byte[] buffer = new byte[mtu + TCPPacket.HEADER_SIZE]; // buffer for receiving packets (header + data)
                boolean listening = true;
                int nextExpectedSeq = 0;

                int bytesReceived = 0;
                int segmentsReceived = 0;
                int segmentsDiscarded = 0; 
                int outOfOrderPackets = 0; // for summary stats

                while (listening) {
                    DatagramPacket rawPacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(rawPacket);
                    TCPPacket packet = TCPPacket.deserialize(rawPacket.getData());

                    if(packet.computeChecksum(rawPacket.getData()).equals(new byte[]{(byte) 0xFF, (byte) 0xFF})) {
                        System.out.println("this packet is legit");
                        segmentsReceived++;

                        if(packet.isSynFlag()) {
                            senderAddress = rawPacket.getSocketAddress();
                            packet.printSummary("rcv");
                            sendAck(packet, socket); // SYN-ACK

                        } else if (packet.isFinFlag()) {
                            sendAck(packet, socket); // ACK for FIN
                            packet.printSummary("rcv");
                            listening = false;
                            System.out.println("Received FIN. Terminating connection.");
                        } else {
                               if (packet.getSeq() == nextExpectedSeq) {
                                    o.write(packet.getData());
                                    bytesReceived += packet.getData().length;
                                    nextExpectedSeq += packet.getData().length;
                                    packet.printSummary("rcv");
                                    sendAck(packet, socket);
                                } else {
                                    outOfOrderPackets++;
                                    sendOops(packet.getTimestamp(), nextExpectedSeq, socket);
                                }
                        }
                    } 
                    else {
                        segmentsDiscarded++;
                        sendOops(packet.getTimestamp(), nextExpectedSeq, socket);
                    }

                }

                System.out.printf("%.0fMb %d %d %d %d %d\n",
                    bytesReceived / 1e6, segmentsReceived, outOfOrderPackets, segmentsDiscarded, 0, 0);

            } catch (Exception e) {
                e.printStackTrace();
            }
    }

    // SYN false, ACK true, FIN false
    private void sendAck(TCPPacket packet, DatagramSocket socket) {
        int ackNum = packet.getSeq() + packet.getData().length; // ACK the next expected byte
        if (packet.isSynFlag() || packet.isFinFlag()) ackNum += 1; // empty data still increments
        TCPPacket ackPacket = new TCPPacket(0, ackNum, packet.getTimestamp(), false, true, false, new byte[0]);
        
        byte[] ackData = ackPacket.serialize(); // pack it up
        DatagramPacket ackDatagram = new DatagramPacket(ackData, ackData.length, senderAddress);
        try {
            socket.send(ackDatagram);
            ackPacket.printSummary("snd");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    // re-ACK the last in-order byte for corrupted or misordered packets
    private void sendOops(long timestamp, int nextExpectedSeq, DatagramSocket socket) {
        // blank ACK with current timestamp and next expected sequence number
        TCPPacket ackPacket = new TCPPacket(0, nextExpectedSeq, timestamp, false, true, false, new byte[0]);
        byte[] ackData = ackPacket.serialize();
        DatagramPacket ackDatagram = new DatagramPacket(ackData, ackData.length, senderAddress);
        try {
            socket.send(ackDatagram);
            ackPacket.printSummary("snd");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}