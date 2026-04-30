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
            byte[] buffer = new byte[mtu + TCPPacket.HEADER_SIZE];
            boolean listening = true;
            int nextExpectedSeq = 0;

            int bytesReceived = 0;
            int segmentsReceived = 0;
            int segmentsDiscarded = 0; 
            int outOfOrderPackets = 0;

            while (listening) {
                DatagramPacket rawPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(rawPacket);
		byte[] received = new byte[rawPacket.getLength()];
		System.arraycopy(rawPacket.getData(), 0, received, 0, rawPacket.getLength());
		
		byte origHi = received[22];
    		byte origLo = received[23];

		TCPPacket packet = TCPPacket.deserialize(received);
		byte[] verification = packet.computeChecksum(received);

                // valid checksum if all 1s
		boolean valid = (verification[0] == origHi && verification[1] == origLo);

                if (valid) {
                    segmentsReceived++;

                    if (packet.isSynFlag()) {
                        senderAddress = rawPacket.getSocketAddress();
                        packet.printSummary("rcv");
			nextExpectedSeq = packet.getSeq() + 1;
                        sendAck(packet, socket); // SYN-ACK

                    } else if (packet.isFinFlag()) {
                        packet.printSummary("rcv");
                        sendFinAck(packet, socket); // FIN-ACK
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
                            if (senderAddress != null) {
                                sendOops(packet.getTimestamp(), nextExpectedSeq, socket);
                            }
                        }
                    }
                } else {
                    segmentsDiscarded++;
                    if (senderAddress != null) {
                        sendOops(packet.getTimestamp(), nextExpectedSeq, socket);
                    }
                }
            }

            System.out.printf("Receiver: %.0fMb %d %d %d %d %d\n",
                bytesReceived / 1e6, segmentsReceived, outOfOrderPackets, segmentsDiscarded, 0, 0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ACK
    private void sendAck(TCPPacket packet, DatagramSocket socket) {
        int ackNum = packet.getSeq() + packet.getData().length;
        if (packet.isSynFlag() || packet.isFinFlag()) ackNum += 1;
        TCPPacket ackPacket = new TCPPacket(0, ackNum, packet.getTimestamp(),
            packet.isSynFlag(), true, false, new byte[0]);
        byte[] ackData = ackPacket.serialize();
        DatagramPacket ackDatagram = new DatagramPacket(ackData, ackData.length, senderAddress);
        try {
            socket.send(ackDatagram);
            ackPacket.printSummary("snd");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // FIN-ACK
    private void sendFinAck(TCPPacket packet, DatagramSocket socket) {
        int ackNum = packet.getSeq() + 1;
        TCPPacket finAck = new TCPPacket(0, ackNum, packet.getTimestamp(),
            false, true, true, new byte[0]); // ackFlag=true, finFlag=true
        byte[] finAckData = finAck.serialize();
        DatagramPacket finAckDatagram = new DatagramPacket(finAckData, finAckData.length, senderAddress);
        try {
            socket.send(finAckDatagram);
            finAck.printSummary("snd");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendOops(long timestamp, int nextExpectedSeq, DatagramSocket socket) {
        TCPPacket ackPacket = new TCPPacket(0, nextExpectedSeq, timestamp,
            false, true, false, new byte[0]);
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
