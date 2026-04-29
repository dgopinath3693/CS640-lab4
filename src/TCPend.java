import java.net.*;
// import TCPReceiver;
// import TCPSender;

public class TCPend {
    public static void main(String[] args) {
        String fileName = null; // the file to be sent or the path where the incoming file should be written
        String receiverIP = null; // sender: the IP address of the remote peer (i.e. receiver)
        Integer receiverPort = null; // sender: the port at which the remote receiver is running
        Integer mtu = null; //  maximum transmission unit in bytes
        Integer sws = null; // sliding window size in number of segments
        Integer port = null; // port number at which the client will run
        boolean sender = false;

        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (arg.equals("-p")) {
                    port = Integer.parseInt(args[++i]); } 
                else if (arg.equals("-s")) {
                    receiverIP = args[++i];
                    sender = true;
                } else if (arg.equals("-a")) {
                    receiverPort = Integer.parseInt(args[++i]);
                } else if (arg.equals("-f")) {
                    fileName = args[++i];
                } else if (arg.equals("-m")) {
                    mtu = Integer.parseInt(args[++i]);
                } else if (arg.equals("-c")) {
                    sws = Integer.parseInt(args[++i]);
                }
            }
        } catch (Exception e) {
            usage();
            return;
        }

        if (fileName == null || mtu == null || sws == null) {
            usage();
            return;
        }

        if (sender && (receiverIP == null || receiverPort == null)) {
            usage();
            return;
        }

        System.out.println("\n====================================================================");
        System.out.println("Mode: " + (sender ? "Sender" : "Receiver"));
        if (sender) {
            System.out.println("Receiver IP: " + receiverIP);
            System.out.println("Receiver Port: " + receiverPort);
        }
        System.out.println("File: " + fileName);
        System.out.println("MTU: " + mtu);
        System.out.println("SWS: " + sws + "\n");
        //System.out.println("====================================================================");
        
        // hand off to Sender / Receiver for handling the file transfer
        if (sender) {
            TCPSender tcpSender = new TCPSender(port, receiverIP, receiverPort, fileName, mtu, sws);
            tcpSender.send(); 
        } else {
            TCPReceiver tcpReceiver = new TCPReceiver(port, fileName, mtu, sws);
            tcpReceiver.receive();
        }

    } // End of main

    private static void usage() {
        System.out.println("\n====================================================================");
        System.out.println("TCP Endpoint Usage:");
        System.out.println("Sender:   TCPend -p <port> -s <receiver IP> -a <receiver port> -f <file name> -m <mtu> -c <sws>");
        System.out.println("Receiver: TCPend -p <port> -f <file name> -m <mtu> -c <sws>");
        System.out.println("====================================================================");
    }
}