package srm;

import java.io.IOException;
import java.net.DatagramPacket;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A container of request/repair back-off timers.
 */
public class RequestRepairPool
{
	private final ReliableMulticastSocket socket;

	/** A size-adaptive thread pool */
	private final ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newCachedThreadPool();

	protected final Map<String, SimpleEntry<RequestTask, Future<?>>> requests = new HashMap<>();
	protected final Map<String, SimpleEntry<RepairTask, Future<?>>> repairs = new HashMap<>();

	public RequestRepairPool(ReliableMulticastSocket socket) {
		this.socket = socket;
	}

	private double C1 = 2;
	private double C2 = 2;
	private Double ave_dup_req = null;
	private Double ave_req_delay = null;

	protected class RequestTask implements Runnable
	{
		/** Turned on before stopping the thread via interrupt */
		boolean doneFlag = false;
		boolean startedFlag = false;
		LocalTime round_start;
		long expire;   // in milliseconds
		long i = 0;
		final DatagramPacket p;
		final String whose;

		static final double AveDups = 1;
		static final long AveDelay = 1;
		static final double alpha = 0.25;
		static final double epsilon = 0.1;

		LocalTime task_start;
		int req_dup = -1;
		long min_dist;
		int n_send = 0;

		StateTable.State current_state;
		Long my_dist;
		public RequestTask(DatagramPacket p, String whose) {
			this.p = p;
			this.whose = whose;
		}

		@Override
		public void run()
		{
			startedFlag = true;
			task_start = LocalTime.now();
			while (true) {
				round_start = LocalTime.now();
				min_dist = Long.MAX_VALUE;
				try {
					StateTable.State s = socket.states.get(whose);
					if (s != null && s.dist() != null) {
						expire = (long) (Math.pow(2, i++) * (C1 + Math.random() * C2) * s.dist());
					}
					else expire = 1000;
					if (expire < 1000) expire = 1000;
					//noinspection BusyWait
					Thread.sleep(expire);
					socket._send(p);
					ReliableMulticastSocket.logger.info("Multicasting REQUEST.");

					n_send ++;
					req_dup ++;
					// Update ave_dup_req
					if (ave_dup_req == null) ave_dup_req = (double) req_dup;
					else ave_dup_req = (1 - alpha) * ave_dup_req + alpha * req_dup;
					req_dup = -1;

					// After sending a request
					C1 -= 0.1;
					// Before each new request timer is set
					current_state = socket.states.get(whose);
					my_dist = current_state != null ? current_state.dist() : null;
					if (my_dist != null && my_dist < min_dist) {
						C2 -= 0.1;
					} else if (ave_dup_req >= AveDups) {
						C1 += 0.1;
						C2 += 0.5;
					} else if (ave_dup_req < AveDups - epsilon) {
						if (ave_req_delay > AveDelay) {
							C2 -= 0.1;
						}
						if (ave_dup_req < 0.25) {
							C1 -= 0.05;
						}
					} else {
						C1 += 0.05;
					}
				}
				catch (IOException e) {
					e.printStackTrace();
				}
				catch (InterruptedException e) {
					if (doneFlag) {
						if (n_send > 0) {
							double req_delay = (double) ChronoUnit.MILLIS.between(task_start, LocalTime.now()) / n_send;
							// Update ave_req_delay
							if (ave_req_delay == null) {
								ave_req_delay = req_delay;
							} else {
								ave_req_delay = (1 - alpha) * ave_req_delay + alpha * req_delay;
							}
							ReliableMulticastSocket.logger.info("ave_req_delay gets updated to "+ave_req_delay+".");
							ReliableMulticastSocket.logger.info("ave_dup_req gets updated to "+ave_dup_req+".");
						}
						break;
					}
				}
			}
		}
	}

	private class RepairTask implements Runnable
	{
		boolean startedFlag = false;
		final DatagramPacket p;
		final String whose_seq;

		double D1 = Math.log(socket.states.getViewingPage().size());
		double D2 = D1;

		public RepairTask(DatagramPacket p, String whose_seq) {
			this.p = p;
			this.whose_seq = whose_seq;
		}

		@Override
		public void run()
		{
			startedFlag = true;
			long expire;   // in milliseconds
			try {
				String whose = whose_seq.split("-")[0];
				StateTable.State s = socket.states.get(whose);
				if (s != null && s.dist() != null) expire = (long) ((D1 + Math.random() * D2) * s.dist());
				else expire = 1000;
				Thread.sleep(expire);
				socket._send(p);
				ReliableMulticastSocket.logger.info("Multicasting REPAIR.");
				repairs.remove(whose_seq);
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			catch (InterruptedException ignored) {
			}
			// Record repairing time
			SimpleEntry<byte[], LocalTime> pair = socket.cache.get(whose_seq);
			if (pair != null) pair.setValue(LocalTime.now());
		}
	}

	/**
	 * Schedule a request timer.
	 */
	protected void request(String whose_seq)
	{
		String whose = whose_seq.split("-")[0];
		StateTable.State s = socket.states.get(whose);
		Long distToSrc = s != null ? s.dist() : null;
		Message request = new Message(socket.sequencer, socket.getFrom(), Type.REQUEST,
				ReliableMulticastSocket.gson.toJson(new Message.RequestBody(
						whose_seq, distToSrc)).getBytes());
		byte[] out = ReliableMulticastSocket.gson.toJson(request).getBytes();
		DatagramPacket p = new DatagramPacket(out, out.length, socket.getGroup(), socket.getLocalPort());

		RequestTask task = new RequestTask(p, whose);
		Future<?> f = pool.submit(task);
		requests.put(whose_seq, new SimpleEntry<>(task, f));
		ReliableMulticastSocket.logger.info("Request timer <"+whose_seq+"> is up.");
	}

	/**
	 * Schedule a repair timer if data payload is found in cache.
	 * Ignore requests for D within 3 * d_S,B time after sending repair or just receiving payload,
	 * where S is the original source of data D, and B is this node itself.
	 */
	protected void repair(String whose_seq)
	{
		String whose = whose_seq.split("-")[0];
		StateTable.State s = socket.states.get(whose);
		SimpleEntry<byte[], LocalTime> pair = socket.cache.get(whose_seq);
		if (pair == null) return;
		else if (s != null && s.dist() != null &&
				ChronoUnit.MILLIS.between(pair.getValue(), LocalTime.now()) < 3 * s.dist()) return;

		Message repair = new Message(socket.sequencer, socket.getFrom(), Type.REPAIR,
				ReliableMulticastSocket.gson.toJson(new Message.RepairBody(
						whose_seq, pair.getKey())).getBytes());
		byte[] out = ReliableMulticastSocket.gson.toJson(repair).getBytes();
		DatagramPacket p = new DatagramPacket(out, out.length, socket.getGroup(), socket.getLocalPort());

		RepairTask task = new RepairTask(p, whose_seq);
		Future<?> f = pool.submit(task);
		repairs.put(whose_seq, new SimpleEntry<>(task, f));
		ReliableMulticastSocket.logger.info("Repair timer <"+whose_seq+"> is up.");
	}

	/**
	 * Postpone a request timer if it is present.
	 * Do not postpone for requests that belong to the same iteration of loss recovery,
	 * where we set this ignore-backoff time to halfway task expiration time.
	 */
	protected void postponeRequest(String whose_seq)
	{
		SimpleEntry<RequestTask, Future<?>> pair = requests.get(whose_seq);
		if (pair == null) return;
		RequestTask task = pair.getKey();
		Future<?> f = requests.get(whose_seq).getValue();
		if (task != null && f != null &&
				ChronoUnit.MILLIS.between(task.round_start, LocalTime.now()) > task.expire / 2) {
			f.cancel(true);   // with done set false
			ReliableMulticastSocket.logger.info("Request timer <"+whose_seq+"> is postponed.");
		}
	}

	/**
	 * Cancel a request timer if it is present.
	 */
	protected void cancelRequest(String whose_seq)
	{
		SimpleEntry<RequestTask, Future<?>> pair = requests.get(whose_seq);
		if (pair == null) return;
		RequestTask task = pair.getKey();
		Future<?> f = requests.get(whose_seq).getValue();
		if (task != null && f != null) {
			task.doneFlag = true;
			while (!task.startedFlag) Thread.onSpinWait();
			f.cancel(true);
			requests.remove(whose_seq);
			ReliableMulticastSocket.logger.info("Request timer <"+whose_seq+"> is cancelled.");
		}
	}

	/**
	 * Cancel a repair timer if it is present.
	 */
	protected void cancelRepair(String whose_seq)
	{
		SimpleEntry<RepairTask, Future<?>> pair = repairs.get(whose_seq);
		if (pair ==  null) return;
		RepairTask task = pair.getKey();
		Future<?> f = repairs.get(whose_seq).getValue();
		if (task != null && f != null) {
			while (!task.startedFlag) Thread.onSpinWait();
			f.cancel(true);
			repairs.remove(whose_seq);
			ReliableMulticastSocket.logger.info("Repair timer <"+whose_seq+"> is cancelled.");
		}
	}

	/**
	 * Initiates an orderly shutdown in which previously submitted tasks are executed,
	 * but no new tasks will be accepted.
	 */
	protected void close() {
		pool.shutdown();
	}

}
