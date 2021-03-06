package srm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.*;
import java.time.LocalTime;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

/**
 * A reliable multicast socket class, made transparent.
 * Implements the Scalable Reliable Multicast (SRM) Framework from the paper:
 *
   * Floyd, S., Jacobson, V., Liu, C.-G., McCanne, S., & Zhang, L. (1997).
   * A reliable multicast framework for light-weight sessions and application level framing.
   * IEEE/ACM Transactions on Networking, 5(6), 784&ndash;803.
 *
 * @author Team Snorlax @ The University of Melbourne:
 * Bingzhe Jin (1080774), Kaixun Yang (1040203), Shizhan Xu (771900), Zhen Cai (1049487)
 */
public class ReliableMulticastSocket extends MulticastSocket
{
	protected static final String LOG_PATH = "./multicast.log";
	protected static final Logger logger = Logger.getLogger(ReliableMulticastSocket.class.getName());
	protected static Gson gson = new GsonBuilder().serializeNulls().create();   // JSON converter

	/** Group IP address */
	private volatile InetAddress group = null;
	/** DATA packet sequencer */
	protected long sequencer;

	/** The dynamic rate of sending SESSION messages, in seconds, that
	 *  the bandwidth consumed is adaptive to 5% of the aggregate bandwidth. */
	private long sessionRate;
	protected static final long SESSION_RATE_MAX = 10;
	protected static final long SESSION_RATE_MIN = 1;
	private Timer sessionSender;

	/** The aggregate bandwidth in bytes (regardless of headers' overhead),
	 *  since from the last session message. */
	private final AtomicInteger aggregBW = new AtomicInteger(0);
	/** The session bandwidth. */
	private final AtomicInteger sessionBW = new AtomicInteger(0);

	/** Components */
	protected StateTable states;
	protected DataCache cache;
	protected RequestRepairPool pool;
	private ReceiverDispatcher rd;

	/**
	 * Constructs a multicast socket and
	 * binds it to the specified port on the local host machine.
	 */
	public ReliableMulticastSocket(int port) throws IOException {
		super(port);
		initLogger();
	}

	/**
	 * Constructs a multicast socket and
	 * binds it to the specified local socket address.
	 */
	public ReliableMulticastSocket(SocketAddress bindaddr) throws IOException {
		super(bindaddr);
		initLogger();
	}

	private void initLogger()
	{
		// Assigning handler to logger
		Handler handler;
		Formatter simpleFormatter;
		try {
			handler = new FileHandler(LOG_PATH);
			simpleFormatter = new SimpleFormatter();
			handler.setFormatter(simpleFormatter);
			logger.addHandler(handler);
			logger.info("Reliable multicast socket starting.");
		}
		catch (Exception e) {
			logger.log(Level.SEVERE, "Error occurs in logger.", e);
		}
	}

	/**
	 * Returns ${IpAddress}@${Port}@${Pid}.
	 */
	protected String getFrom() {
		String address = null;
		try {
			address = InetAddress.getLocalHost().getHostAddress();
		}
		catch (UnknownHostException e) {
			ReliableMulticastSocket.logger.log(Level.WARNING,
					"Cannot retrieve the address of the local host.", e);
		}
		return (address != null ? address+"@" : "") + getLocalPort() +
				"@" + ProcessHandle.current().pid();
	}

	/**
	 * Blocks until the group becomes not null.
	 *
	 * @return group address
	 */
	protected InetAddress getGroup() {
		while (group == null) Thread.onSpinWait();
		return group;
	}

	private boolean isFirstSession = true;
	/**
	 * Task to multicast SESSION messages.
	 */
	private class SessionSendTask extends TimerTask
	{
		@Override
		public void run()
		{
			Message session = new Message(sequencer, getFrom(), Type.SESSION,
					gson.toJson(new Message.SessionBody(
							LocalTime.now().toString(), states.getViewingPage())).getBytes());
			byte[] out = gson.toJson(session).getBytes();
			DatagramPacket p = new DatagramPacket(out, out.length, getGroup(), getLocalPort());
			try {
				_send(p);
				logger.info("Multicasting SESSION.");
				sessionBW.addAndGet(p.getLength());   // inc
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			if (!isFirstSession) updateSessionRate();
			isFirstSession = false;
			// Schedule next
			sessionSender.schedule(new SessionSendTask(), sessionRate * 1000L);
		}
	}

	/**
	 * Reset bandwidth counters, then adjust the session rate.
	 */
	private void updateSessionRate() {
		double ratio = ((double) sessionBW.getAndSet(0)) / aggregBW.getAndSet(0);
		long sessionRateTemp = (long) (20 * sessionRate / ratio);
		sessionRate = Math.min(Math.max(sessionRateTemp, SESSION_RATE_MIN), SESSION_RATE_MAX);
		logger.info("Session rate gets updated to per "+sessionRate+" seconds.");
	}

	@Override
	public synchronized void joinGroup(SocketAddress mcastaddr, NetworkInterface netIf) throws IOException
	{
		super.joinGroup(mcastaddr, netIf);
		sequencer = 1;
		sessionSender = new Timer();
		sessionRate = SESSION_RATE_MIN;
		aggregBW.set(0);
		sessionBW.set(0);
		states = new StateTable(1);
		cache = new DataCache(5);
		pool = new RequestRepairPool(this);
		rd = new ReceiverDispatcher(this);
		group = ((InetSocketAddress) mcastaddr).getAddress();
		// Session sending routines, starts once group is specified
		sessionSender.schedule(new SessionSendTask(), 0);
		rd.start();   // Receiving at background
	}

	@Override
	public void leaveGroup(SocketAddress mcastaddr, NetworkInterface netIf) throws IOException {
		super.leaveGroup(mcastaddr, netIf);
		group = null;
		sessionSender.cancel();
		sessionSender.purge();
		pool.close();
		cache.getUpdater().cancel();
		cache.getUpdater().purge();
	}

	// send DATA only
	@Override
	public synchronized void send(DatagramPacket p) throws IOException
	{
		Message data = new Message(sequencer, getFrom(), Type.DATA, p.getData());
		byte[] out = gson.toJson(data).getBytes();
		DatagramPacket _p = new DatagramPacket(out, out.length,
				p.getAddress(), p.getPort());
		logger.info("Multicasting DATA.");
		_send(_p);
		if (!getOption(StandardSocketOptions.IP_MULTICAST_LOOP)) {
			states.update(data.getFrom(), sequencer, null);
		}
		sequencer++;
	}

	// Receive DATA only
	@Override
	public void receive(DatagramPacket p) {
		try {
			p.setData(cache.consume());
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Delegate multicasting a datagram packet unreliably, and
	 * measures bandwidth cost at the same time.
	 */
	protected void _send(DatagramPacket p) throws IOException {
		super.send(p);
		aggregBW.addAndGet(p.getLength());
	}

	/**
	 * Delegate receiving a multicasted datagram packet unreliably, and
	 * measures bandwidth cost at the same time.
	 */
	protected void _receive(DatagramPacket p) throws IOException {
		super.receive(p);
		aggregBW.addAndGet(p.getLength());
	}

	/** Disabled. */
	@Override
	public void connect(InetAddress address, int port) {
	}

	/** Disabled. */
	@Override
	public void setSoTimeout(int timeout) {
	}

}
