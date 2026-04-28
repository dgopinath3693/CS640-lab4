package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.Map;
import java.util.HashMap;
import net.floodlightcontroller.packet.MACAddress;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
	}

	/**
	 * Creates a MacEntry for use within the MacTable map. 
	 * @param iface the iface for this entry's received packet
	 * @param timestamp the time the last packet was received for this entry
	 */
	public static class MacEntry {
		public Iface iface;
		public long timestamp;

		public MacEntry(Iface iface, long timestamp) {
			this.iface = iface;
			this.timestamp = timestamp;
		}
	}

	// private instance variable for this switch's macTable
	private Map<MACAddress, MacEntry> macTable = new HashMap<>();

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		
		// 1. Identify the packet origin and start the timer
		MACAddress sourceAddr = etherPacket.getSourceMAC();
		this.macTable.put(sourceAddr, new MacEntry(inIface, System.currentTimeMillis()));

		// 2. Look up destination and determine whether to Forward or Flood
		MACAddress destinationAddr = etherPacket.getDestinationMAC();
		MacEntry dest = this.macTable.get(destinationAddr);
		if(dest != null && System.currentTimeMillis() - dest.timestamp < 15000) {
			// 3a. FORWARD
			System.out.println("Forwarding to " + destinationAddr);
			this.sendPacket(etherPacket, dest.iface);
		}
		else {
			// 3b. FLOOD
			System.out.println("Broadcasting to " + destinationAddr);
			for (Iface outIface : this.interfaces.values()) {
				if(!outIface.equals(inIface)) {
					this.sendPacket(etherPacket, outIface);
				}
			}
		}

		/********************************************************************/
	}
}
