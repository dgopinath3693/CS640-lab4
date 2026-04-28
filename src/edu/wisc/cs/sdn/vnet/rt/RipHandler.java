package edu.wisc.cs.sdn.vnet.rt;

import net.floodlightcontroller.packet.MACAddress;

import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import net.floodlightcontroller.packet.*;

import edu.wisc.cs.sdn.vnet.Iface;

public class RipHandler {
    // max allowed route length
    public static final int INFINITY = 16;

    private static final int responseScheduleTime = 10000; // 10 seconds
    private static final int expireTime = 30000; // 30 seconds

    // Multicast IP Address per spec
    private static final int RIP_MULTICAST_IP = IPv4.toIPv4Address("224.0.0.9");

    // Broadcast MAC address per spec
    private static final byte[] BROADCAST_MAC = MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toBytes();

    // router using this protocol handler
    private Router router;

    // handles all protocol timing (periodic updates, entry expiry)
    private Timer timer;

    public RipHandler(Router router) {
        this.router = router;
        this.timer = new Timer();
    }

    public void start() {
        System.out.println("Starting RIPv2 Protocol...");

        // directly connected subnets
        for (Iface iface : router.getInterfaces().values()) {
            int subnet = iface.getIpAddress() & iface.getSubnetMask();
            router.getRouteTable().insert(subnet, 0, iface.getSubnetMask(), iface);
        }

        // start the conversation immediately
        sendRipRequest();

        // broadcast our full route table to all neighbors every ten seconds
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() { broadcastRipResponse(); }
        }, responseScheduleTime, responseScheduleTime);

        // check for expiring entries every second
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() { routeTimeout(); }
        }, 1000, 1000);
    }

    /**
     * Requests routes from all neighbors by broadcasting a RIP request.
     */
    private void sendRipRequest() {
        RIPv2 ripPacket = new RIPv2();
        ripPacket.setCommand(RIPv2.COMMAND_REQUEST);

        // see 3.9.1 Request Messages of RFC 2453 - full table request specified
        RIPv2Entry single = new RIPv2Entry(0, 0, INFINITY);
        ripPacket.addEntry(single);

        for (Iface iface : router.getInterfaces().values()) {
            sendRipPacket(ripPacket, iface, RIP_MULTICAST_IP, BROADCAST_MAC);
        }
    }

    /**
     * Broadcasts a RIP response out to all neighbors
     */
    private void broadcastRipResponse() {
        RIPv2 ripPacket = tablePacket();
        for (Iface iface : router.getInterfaces().values())
        {
            sendRipPacket(ripPacket, iface, RIP_MULTICAST_IP, BROADCAST_MAC);
        }
    }

    /**
     * Sends a RIP response directly to a requestor
     *
     * @param destIp   the IP address of requestor
     * @param destMac  the MAC address of requestor
     * @param outIface our sender address (receiving interface)
     */
    public void sendRipResponse(int destIp, byte[] destMac, Iface outIface) {
        RIPv2 ripPacket = tablePacket();
        sendRipPacket(ripPacket, outIface, destIp, destMac);
    }

    /**
     * encapsulates full route table in a RIPv2 packet
     */
    private RIPv2 tablePacket() {
        RIPv2 ripPacket = new RIPv2();
        ripPacket.setCommand(RIPv2.COMMAND_RESPONSE);

        for (RouteEntry entry : router.getRouteTable().getEntries())
        {
            RIPv2Entry ripEntry = new RIPv2Entry(
                entry.getDestinationAddress(),
                entry.getMaskAddress(),
                entry.getMetric()
            );
            ripPacket.addEntry(ripEntry);
        }
        return ripPacket;
    }

    /**
     * Fully encapsulates a RIPv2 packet
     *
     * @param ripPacket the RIPv2 payload to send
     * @param outIface our outgoing interface
     * @param destIp   destination IP address
     * @param destMac  destination MAC address
     */
    private void sendRipPacket(RIPv2 ripPacket, Iface outIface, int destIp, byte[] destMac) {
        if (outIface.getMacAddress() == null || outIface.getIpAddress() == 0) return; 

        // layer objects
        UDP udp = new UDP();
        IPv4 ip = new IPv4();
        Ethernet ether = new Ethernet();

        // UDP layer
        udp.setSourcePort(UDP.RIP_PORT); // port 520
        udp.setDestinationPort(UDP.RIP_PORT);  
        udp.setPayload(ripPacket);

        // IPv4 layer
        ip.setVersion((byte) 4);
        ip.setTtl((byte) 64);
        ip.setProtocol(IPv4.PROTOCOL_UDP);
        ip.setSourceAddress(outIface.getIpAddress());
        ip.setDestinationAddress(destIp);
        ip.setPayload(udp);

        // Ethernet layer
        ether.setEtherType(Ethernet.TYPE_IPv4);
        ether.setSourceMACAddress(outIface.getMacAddress().toBytes());
        ether.setDestinationMACAddress(destMac);
        ether.setPayload(ip);

        // manually build out and serialization idk
        udp.setPayload(ripPacket);
        ip.setPayload(udp);
        ether.setPayload(ip);
        ether.serialize();

        // send the encapsulated packet out the specified interface
        router.sendPacket(ether, outIface);
    }

    /**
     * process all incoming RIP packets
     *
     * @param ripPacket received payload at RIP layer
     * @param inIface the arriving interface
     * @param srcIp arriving ip
     * @param srcMac arriving mac
     */
    public void handleRipPacket(RIPv2 ripPacket, Iface inIface, int srcIp, byte[] srcMac) {
        // neighbor asking for routes
        if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST)
            sendRipResponse(srcIp, srcMac, inIface);
        
        // neighbor providing routes
        else if (ripPacket.getCommand() == RIPv2.COMMAND_RESPONSE)
            processRipResponse(ripPacket, inIface, srcIp);
    }

    /**
     * process a RIP response from a neighbor.
     * vector routing implementation, updates best paths and
     * last communication timestamps for neighbors
     *
     * @param ripPacket received payload at RIP layer
     * @param inIface  the arriving interface
     * @param srcIp    arriving ip (gateway ip)
     */
    private void processRipResponse(RIPv2 rip, Iface inIface, int srcIp) {
        // update timestamp regardless folllowing successful communication from this neighbor

        for (RIPv2Entry ripEntry : rip.getEntries()) {
            int destIp = ripEntry.getAddress();
            int mask = ripEntry.getSubnetMask();
            int newCost = ripEntry.getMetric() + 1; 

            if (newCost >= INFINITY) continue; // considered unreachable

            // get existing route to this destination (if we have one)
            RouteEntry existingRoute = router.getRouteTable().lookup(destIp);

            // new path > no path
            if (existingRoute == null) {
                router.getRouteTable().insert(destIp, srcIp, mask, inIface, newCost);
            }

            // new path > old path
            else if (newCost < existingRoute.getMetric()) { 
                router.getRouteTable().update(destIp, mask, srcIp, inIface, newCost);
            }

            // old path > new path
            else if (existingRoute.getGatewayAddress() == srcIp) {
                existingRoute.setTimestamp(); 
            }
        }
    }

    /**
     * Called every second by the timer, removes any route table entry that 
     * hasn't been updated in over 30 seconds.
     */
    private void routeTimeout() {
        for (RouteEntry entry : router.getRouteTable().getEntries()) {
            if (entry.isExpired()) {
                System.out.println("Expiring route to " + 
                IPv4.fromIPv4Address(entry.getDestinationAddress()));
                router.getRouteTable().remove(
                    entry.getDestinationAddress(),
                    entry.getMaskAddress()
                );
            }
        }
    }

}