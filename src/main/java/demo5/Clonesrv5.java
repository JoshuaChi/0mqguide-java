package demo5;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.zeromq.ZContext;
import org.zeromq.ZLoop;
import org.zeromq.ZLoop.IZLoopHandler;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Socket;

// Clone server - Model Five
public class Clonesrv5 {
	private ZContext ctx; // Context wrapper
	private Map<String, Kvmsg> kvmap; // Key-value store
	private ZLoop loop; // zloop reactor
	private int port; // Main port we're working on
	private long sequence; // How many updates we're at
	private Socket snapshot; // Handle snapshot requests
	private Socket publisher; // Publish updates to clients
	private Socket collector; // Collect updates from clients

	// This is the reactor handler for the snapshot socket; it accepts
	// just the ICANHAZ? request and replies with a state snapshot ending
	// with a KTHXBAI message:
	private static class Snapshots implements IZLoopHandler {
		public int handle(ZLoop loop, PollItem item, Object arg) {
			Clonesrv5 srv = (Clonesrv5) arg;
			Socket socket = item.getSocket();

			byte[] identity = socket.recv();
			if (identity != null) {
				// Request is in second frame of message
				String request = socket.recvStr();
				String subtree = null;
				if (request.equals("ICANHAZ?")) {
					subtree = socket.recvStr();
				} else
					System.out.printf("E: bad request, aborting\n");

				if (subtree != null) {
					// Send state socket to client
					for (Entry<String, Kvmsg> entry : srv.kvmap.entrySet()) {
						sendSingle(entry.getValue(), identity, subtree, socket);
					}

					// Now send END message with getSequence number
					System.out.printf("I: sending shapshot=%d\n", srv.sequence);
					socket.send(identity, ZMQ.SNDMORE);
					Kvmsg Kvmsg = new Kvmsg(srv.sequence);
					Kvmsg.setKey("KTHXBAI");
					Kvmsg.setBody(subtree.getBytes());
					Kvmsg.send(socket);
					Kvmsg.destroy();
				}
			}
			return 0;
		}
	}

	// We store each update with a new getSequence number, and if necessary, a
	// time-to-live. We publish updates immediately on our publisher socket:
	private static class Collector implements IZLoopHandler {

		public int handle(ZLoop loop, PollItem item, Object arg) {
			Clonesrv5 srv = (Clonesrv5) arg;
			Socket socket = item.getSocket();

			Kvmsg msg = Kvmsg.recv(socket);
			if (msg != null) {
				msg.setSequence(++srv.sequence);
				msg.send(srv.publisher);
				int ttl = Integer.parseInt(msg.getProp("ttl"));
				if (ttl > 0)
					msg.setProp("ttl", "%d", System.currentTimeMillis() + ttl * 1000);
				msg.store(srv.kvmap);
				System.out.printf("I: publishing update=%d\n", srv.sequence);
			}

			return 0;
		}
	}

	private static class FlushTTL implements IZLoopHandler {

		public int handle(ZLoop loop, PollItem item, Object arg) {
			Clonesrv5 srv = (Clonesrv5) arg;
			if (srv.kvmap != null) {
				for (Kvmsg msg : new ArrayList<Kvmsg>(srv.kvmap.values())) {
					srv.flushSingle(msg);
				}
			}
			return 0;
		}
	}

	public Clonesrv5() {
		port = 5556;
		ctx = new ZContext();
		kvmap = new HashMap<String, Kvmsg>();
		loop = new ZLoop();
		loop.verbose(false);

		// Set up our clone server sockets
		snapshot = ctx.createSocket(ZMQ.ROUTER);
		snapshot.bind(String.format("tcp://*:%d", port));
		publisher = ctx.createSocket(ZMQ.PUB);
		publisher.bind(String.format("tcp://*:%d", port + 1));
		collector = ctx.createSocket(ZMQ.PULL);
		collector.bind(String.format("tcp://*:%d", port + 2));
	}

	public void run() {
		// Register our handlers with reactor
		PollItem poller = new PollItem(snapshot, ZMQ.Poller.POLLIN);
		loop.addPoller(poller, new Snapshots(), this);
		poller = new PollItem(collector, ZMQ.Poller.POLLIN);
		loop.addPoller(poller, new Collector(), this);
		loop.addTimer(1000, 0, new FlushTTL(), this);

		loop.start();
		loop.destroy();
		ctx.destroy();
	}

	// We call this function for each getKey-value pair in our hash table
	private static void sendSingle(Kvmsg msg, byte[] identity, String subtree, Socket socket) {
		if (subtree.equals(msg.getKey())) {
			socket.send(identity, // Choose recipient
					ZMQ.SNDMORE);
			msg.send(socket);
		}
	}

	// At regular intervals, we flush ephemeral values that have expired. This
	// could be slow on very large data sets:

	// If getKey-value pair has expired, delete it and publish the
	// fact to listening clients.
	private void flushSingle(Kvmsg msg) {
		long ttl = Long.parseLong(msg.getProp("ttl"));
		if (ttl > 0 && System.currentTimeMillis() >= ttl) {
			msg.setSequence(++sequence);
			msg.setBody("".getBytes());
			msg.send(publisher);
			msg.store(kvmap);
			System.out.printf("I: publishing delete=%d\n", sequence);
		}
	}

	public static void main(String[] args) {
		Clonesrv5 srv = new Clonesrv5();
		srv.run();
	}
}
