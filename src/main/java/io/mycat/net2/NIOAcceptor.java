package io.mycat.net2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author wuzh
 */
public final class NIOAcceptor extends Thread {
	private static final Logger LOGGER = LoggerFactory.getLogger(NIOAcceptor.class);
	private final int port;
	private final Selector selector;
	private final ServerSocketChannel serverChannel;
	private final ConnectionFactory factory;
	private long acceptCount;
	private final NIOReactorPool reactorPool;

	public NIOAcceptor(String name, String bindIp, int port,
			ConnectionFactory factory, NIOReactorPool reactorPool)
			throws IOException {
		super.setName(name);
		this.port = port;
		this.selector = Selector.open();
		this.serverChannel = ServerSocketChannel.open();
		this.serverChannel.configureBlocking(false);
		/** 设置TCP属性 */
		serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		//linux kernel 里面的tcp实现中接收到的数据缓冲区的buffer大小,跟应用层无关
		//根据并发设置,过大应用层处理不过来,过小并发大时客户端连接不上
		serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 16 * 2);  
		// backlog=100
		serverChannel.bind(new InetSocketAddress(bindIp, port), 100);
		this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
		this.factory = factory;
		this.reactorPool = reactorPool;
	}

	public int getPort() {
		return port;
	}

	public long getAcceptCount() {
		return acceptCount;
	}

	@Override
	public void run() {
		final Selector selector = this.selector;
		for (;;) {
			//单线程,循环进行select
			++acceptCount;
			try {
				//阻塞的方法，当有事件到来或者超时时会触发
				//返回一个int，代表几个channel被select到了
				selector.select(1000L);
				Set<SelectionKey> keys = selector.selectedKeys();
				try {
					//key里面可以获取到channel
					//key要cancel或clear掉，不然下次还会被select到
					for (SelectionKey key : keys) {
						if (key.isValid() && key.isAcceptable()) {
							accept();   //selector只被accept事件注册过，所以只需要处理accept事件
						} else {
							key.cancel(); 
						}
					}
				} finally {
					keys.clear();
				}
			} catch (Throwable e) {
				LOGGER.warn(getName(), e);
			}
		}
	}

	/**
	 * 接受新连接
	 */
	private void accept() {
		SocketChannel channel = null;
		try {
			//accept到来之后，因为只有一个channel，因此可以直接取
			//返回SocketChannel,代表客户端channel
			channel = serverChannel.accept();
			channel.configureBlocking(false);
			Connection c = factory.make(channel);
			c.setDirection(Connection.Direction.in);
			c.setId(ConnectIdGenerator.getINSTNCE().getId());
			InetSocketAddress remoteAddr = (InetSocketAddress) channel
					.getRemoteAddress();
			c.setHost(remoteAddr.getHostString());
			c.setPort(remoteAddr.getPort());
			// 派发此连接到某个Reactor处理
			NIOReactor reactor = reactorPool.getNextReactor();
			reactor.postRegister(c);

		} catch (Throwable e) {
			closeChannel(channel);
			LOGGER.warn(getName(), e);
		}
	}

	private static void closeChannel(SocketChannel channel) {
		if (channel == null) {
			return;
		}
		Socket socket = channel.socket();
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
			}
		}
		try {
			channel.close();
		} catch (IOException e) {
		}
	}

}