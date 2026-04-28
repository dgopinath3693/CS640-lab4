package edu.wisc.cs.sdn.vnet.rt;
import net.floodlightcontroller.packet.IPv4;
import java.nio.ByteBuffer;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.UDP;

import net.floodlightcontroller.packet.Ethernet;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;

	/** RIPv2 protocol handler */
	private RipHandler ripHandler;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Starts RIP dynamic routing -- called from Main when no static route table is provided.
	 */
	public void startRIP() {
		this.ripHandler = new RipHandler(this); // null otherwise
		this.ripHandler.start();
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		// drop packet if not ipv4
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) { return; }

		/******************** RIP PACKET CHECK ********************/

		IPv4 ripCheck = (IPv4) etherPacket.getPayload();

		// check protocol to confirm RIP request/response
		if (ripCheck.getProtocol() == IPv4.PROTOCOL_UDP) {
			UDP udpCheck = (UDP) ripCheck.getPayload();

			// check destination port to confirm RIP request/response
			if (udpCheck.getDestinationPort() == UDP.RIP_PORT) {
				RIPv2 rip = (RIPv2) udpCheck.getPayload();

				// provide what we know about destination and sender, protocol handles the rest
				ripHandler.handleRipPacket(rip, inIface,
					ripCheck.getSourceAddress(),
					etherPacket.getSourceMACAddress());
				return; 
			}
		}
		
		/**********************************************************/

		IPv4 header = (IPv4) etherPacket.getPayload();

		short checksum = header.getChecksum();
		header.resetChecksum();
		header.serialize();
		short calc_checksum = header.getChecksum();

		//invalid checksum so drop packet
		if (calc_checksum != checksum) {
			return;
		}
		
		// assign3 fix: if ttl is 1 or 0, drop packet
		if (header.getTtl() <= 1) {
			return;
		}

		header.setTtl((byte)(header.getTtl() - 1));
		header.resetChecksum();
		
		//if destination is for router, drop packet
		int dest_addr = header.getDestinationAddress();
		for (Iface router_interface : this.interfaces.values()) {
    			if (dest_addr == router_interface.getIpAddress()) {
        			return;
   			 }
		}

		RouteEntry longest_prefix_route = this.routeTable.lookup(dest_addr);
		//if longest prefix cannot be found, drop packet
		if(longest_prefix_route == null) {
			return;
		}

		// assign3 fix: never route a packet back out the same interface it arrived on.
		// template: if (this.routeTable.lookup(dstIp).getInterface() == inIface)
		if (longest_prefix_route.getInterface().equals(inIface))
		{ return; }

		int next_ip = longest_prefix_route.getGatewayAddress();
		if(next_ip == 0) {
			next_ip = dest_addr;
		}

		//this is the ethernet destination mac addr
		ArpEntry arp_cache_mac_addr = this.arpCache.lookup(next_ip);
		//if no mac addr found in cache, drop packet
		if (arp_cache_mac_addr == null) {
			return;
		}

		// assign3 fix: replaced guessing loop with call to prefix route to get outgoing port,
		// replaced objects with arp_cache_mac_addr and outIface 
		
		Iface outIface = longest_prefix_route.getInterface();

		//new source is the router's current interface mac addr
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		
		//bew dest is next router hop's mac addr from arp cache
		etherPacket.setDestinationMACAddress(arp_cache_mac_addr.getMac().toBytes());

		this.sendPacket(etherPacket, outIface);
	}
}
