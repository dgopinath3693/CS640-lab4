package edu.wisc.cs.sdn.vnet.rt;

import net.floodlightcontroller.packet.IPv4;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * An entry in a route table.
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class RouteEntry 
{
	/** Destination IP address */
	private int destinationAddress;
	
	/** Gateway IP address */
	private int gatewayAddress;
	
	/** Subnet mask */
	private int maskAddress;
	
	/** Router interface out which packets should be sent to reach the destination or gateway */
	private Iface iface;

	/** Long timestamp of the last time this route entry was updated. Used for timing out old entries.*/
	private long lastUpdatedTime;

	/** int distance to the destination, used for optimizing RIP updates */
	private int distance;
	
	/**
	 * Create a new route table entry.
	 * @param destinationAddress destination IP address
	 * @param gatewayAddress gateway IP address
	 * @param maskAddress subnet mask
	 * @param iface the router interface out which packets should 
	 *        be sent to reach the destination or gateway
	 */
	public RouteEntry(int destinationAddress, int gatewayAddress, 
			int maskAddress, Iface iface)
	{
		this.destinationAddress = destinationAddress;
		this.gatewayAddress = gatewayAddress;
		this.maskAddress = maskAddress;
		this.iface = iface;
		this.distance = 1;
		this.lastUpdatedTime = 0; // 0 = directly connected
	}

	/**
	 * Overloaded constructor with specified update time and distance
	 * Used for route construction
	 * @param destinationAddress destination IP address
	 * @param gatewayAddress gateway IP address
	 * @param maskAddress subnet mask
	 * @param iface the router interface out which packets should 
	 *        be sent to reach the destination or gateway
	 * @param lastUpdatedTime the timestamp of the last update to this route entry
	 */
	public RouteEntry(int destinationAddress, int gatewayAddress, 
			int maskAddress, Iface iface, int distance, long lastUpdatedTime)
	{
		this.destinationAddress = destinationAddress;
		this.gatewayAddress = gatewayAddress;
		this.maskAddress = maskAddress;
		this.iface = iface;
		this.distance = distance;
		this.lastUpdatedTime = lastUpdatedTime;
	}
	
	/****** GETTERS ******/
	/**
	 * @return destination IP address
	 */
	public int getDestinationAddress()
	{ return this.destinationAddress; }
	
	/**
	 * @return gateway IP address
	 */
	public int getGatewayAddress()
	{ return this.gatewayAddress; }

	/**
	 * @return distance to destination
	 * Naming inherited from RIPv2 class
	 */
	public int getMetric()
	{ return this.distance; }
	
	/**
	 * @return subnet mask 
	 */
	public int getMaskAddress()
	{ return this.maskAddress; }
	
	/**
	 * @return the router interface out which packets should be sent to 
	 *         reach the destination or gateway
	 */
	public Iface getInterface()
	{ return this.iface; }

	/****** SETTERS ******/
	public void setInterface(Iface iface)
	{ this.iface = iface; }

	public void setGatewayAddress(int gatewayAddress)
	{ this.gatewayAddress = gatewayAddress; }

	public void setTimestamp()
    { this.lastUpdatedTime = System.currentTimeMillis(); }

	public void setDistance(int distance)
	{ this.distance = distance; }
	

    public boolean isExpired()
    {
        if (this.lastUpdatedTime == 0) return false; 
        return (System.currentTimeMillis() - this.lastUpdatedTime) > 30000;
    }

	public String toString()
	{
		return String.format("%s \t%s \t%s \t%s \t%d",
				IPv4.fromIPv4Address(this.destinationAddress),
				IPv4.fromIPv4Address(this.gatewayAddress),
				IPv4.fromIPv4Address(this.maskAddress),
				this.iface.getName(),
				this.distance);
	}

}
