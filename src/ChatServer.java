import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

public class ChatServer implements Runnable {
	// Client list, 1 socket for 1 client
	LinkedList<SocketChannel>	clients	= new LinkedList<SocketChannel>();
	ByteBuffer					writeBuffer	= ByteBuffer.allocateDirect(3000);
	ByteBuffer					readBuffer	= ByteBuffer.allocateDirect(3000);

	private ServerSocketChannel		serverSocketTCP;
	private DatagramSocket			serverSocketUDP;
	private final byte[]			udpData	= new byte[2000];
	private DatagramPacket receivePacket;
	Selector					readSelector;
	private static CharsetDecoder	asciiDecoder;
	private boolean				running;
	static JTextArea			logArea;

	private static final long	CHANNEL_WRITE_SLEEP	= 10;

	public static void main(String... args) {
		logArea = new JTextArea();
		logArea.setBackground(Color.black);
		logArea.setForeground(Color.green);
		logArea.setEditable(false);
		JScrollPane scrollArea = new JScrollPane(logArea);
		scrollArea.setPreferredSize(new Dimension(380, 380));
		scrollArea.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

		asciiDecoder = Charset.forName("ISO-8859-1").newDecoder();

		JFrame svFrame = new JFrame();
		svFrame.setSize(430, 430);
		svFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		// svFrame.pack();
		svFrame.setVisible(true);
		svFrame.setLayout(new FlowLayout());
		svFrame.add(scrollArea);

		log("\nGUI initiated.");

		ChatServer sv = new ChatServer();
		log("\nServer created.");
		sv.running = true;
		sv.run();// Blocking
		log("\nServer terminated.");
	}

	private void initServerSockets() {
		try {
			serverSocketTCP = ServerSocketChannel.open();
			// Non blocking channels
			serverSocketTCP.configureBlocking(false);

			String addr = InetAddress.getLocalHost().getHostAddress();
			// Bind the server socket channel
			serverSocketTCP.socket().bind(new InetSocketAddress(addr, Shared.PORTTCP));

			readSelector = Selector.open();
			log("\nServidor TCP iniciado em: " + addr + ":" + Shared.PORTTCP);

			serverSocketUDP = new DatagramSocket(Shared.PORTUDP, InetAddress.getLocalHost());
			readUDP();
			log("\nServidor UDP iniciado em: " + addr + ":" + Shared.PORTUDP);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void log(String txt) {
		logArea.append(txt);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				logArea.setCaretPosition(logArea.getText().length());
			}
		});
	}

	private void acceptNewConections() {
		try {
			SocketChannel clientChannel;
			while ((clientChannel = serverSocketTCP.accept()) != null) {
				addNewClient(clientChannel);

				sendBroadcastMessage("Login from: " + clientChannel.socket().getInetAddress(), clientChannel);
				log("\nLogin: " + clientChannel.socket().getInetAddress());

				sendMessage(clientChannel, "\n Welcome to zombie box! \n>There are " + clients.size()
				+ " users here.");
			}
		} catch (IOException ioe) {
			// Error on accept
			ioe.printStackTrace();
		} catch (Exception e) {
			// Other errors
			e.printStackTrace();
		}
	}

	private void addNewClient(SocketChannel channel) {
		clients.add(channel);
		try {
			channel.configureBlocking(false);
			channel.register(readSelector, SelectionKey.OP_READ, new StringBuffer());
		}catch(ClosedChannelException cce) {
			cce.printStackTrace();
		}catch(IOException ioe) {
			ioe.printStackTrace();
		}
	}

	private void sendMessage(SocketChannel channel, String msg) {
		prepareWriteBuffer(msg);
		channelWrite(channel,writeBuffer);
	}

	private void sendBroadcastMessage(String msg, SocketChannel from) {
		prepareWriteBuffer(msg);

		for(SocketChannel channel : clients) {
			if(channel != from)
				channelWrite(channel, writeBuffer);
		}
	}

	private void prepareWriteBuffer(String msg) {
		writeBuffer.clear();
		writeBuffer.put(msg.getBytes());
		writeBuffer.putChar('\n');
		writeBuffer.flip();
	}

	private void channelWrite(SocketChannel channel, ByteBuffer wBuffer) {
		long nBytes = 0;
		long toWrite = writeBuffer.remaining();

		try {
			while (nBytes != toWrite) {
				nBytes += channel.write(wBuffer);

				try {
					Thread.sleep(CHANNEL_WRITE_SLEEP);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		} catch (ClosedChannelException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

		wBuffer.rewind();
	}

	private void readIncomingMessages() {
		// Store the socket
		SocketChannel channel = null;

		try {
			// non-blocking select, returns immediately regardless of how many keys are ready
			readSelector.selectNow();

			// fetch the keys
			Set<?> readyKeys = readSelector.selectedKeys();

			// run through the keys and process
			Iterator<?> i = readyKeys.iterator();
			while (i.hasNext()) {
				SelectionKey key = (SelectionKey) i.next();
				i.remove();
				channel = (SocketChannel) key.channel();
				readBuffer.clear();

				// read from the channel into our buffer
				long nbytes = channel.read(readBuffer);

				// check for end-of-stream
				if (nbytes == -1 || readBuffer == null) {
					channel.close();
					clients.remove(channel);
					sendBroadcastMessage("Saiu: " + channel.socket().getInetAddress(), channel);
					log("\nSaiu: " + channel.socket().getInetAddress());
				} else {
					log("\nMensagem nova:");
					// grab the StringBuffer we stored as the attachment
					StringBuffer sb = (StringBuffer) key.attachment();

					// use a CharsetDecoder to turn those bytes into a string
					// and append to our StringBuffer
					readBuffer.flip();
					String str = asciiDecoder.decode(readBuffer).toString();
					readBuffer.clear();
					sb.append(str);

					// check for a full line
					String line = sb.toString();
					if ((line.indexOf("\n") != -1) || (line.indexOf("\r") != -1)) {
						line = line.trim();
						if (line.startsWith("quit")) {
							// client is quitting, close their channel, remove them from the list and notify all other clients
							channel.close();
							clients.remove(channel);
							sendBroadcastMessage("logout: " + channel.socket().getInetAddress(), channel);
							log("\nlogout: " + channel.socket().getInetAddress());
						}
						// got one, send it to all clients
						if (line.split(":").length > 1) {
							sendBroadcastMessage(line, channel);
							log("\nMensagem (" + channel.socket().getInetAddress() + "): " + line);
						} else {
							sendBroadcastMessage(channel.socket().getInetAddress() + ": " + line, channel);
							log("\nMensagem (" + channel.socket().getInetAddress() + "): " + line);
						}
						sb.delete(0, sb.length());
					}
				}

			}
		} catch (IOException ioe) {

			try {
				System.out.println("Desconectado.");
				log("\nCliente perdeu a conexão: " + channel.socket().getInetAddress());
				if (channel != null) {
					channel.close();
					System.out.println("Desconectado do servidor.");
					log("\nCliente desconectado: " + channel.socket().getInetAddress()
							+ " > end-of-stream");
					clients.remove(channel);
					channel = null;
				}
			} catch (IOException e) {
				System.out.println("Erro crítico na remoção do cliente!");
				e.printStackTrace();
			}
		} catch (Exception e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(null, "Excessão:" + e.getMessage());
		}

	}

	public static int getPort() {
		return Shared.PORTTCP;
	}

	private SocketChannel getChannelByAddress(InetAddress addr) {
		return clients.stream().filter(chnl -> chnl.socket().getInetAddress().equals(addr)).findFirst()
				.orElseGet(null);
	}

	public void readUDP() {
		Thread udpReader = new Thread(() -> {
			// block while we wait for a client to connect
			while (running) {
				// UDP messages
				receivePacket = new DatagramPacket(udpData, udpData.length);
				try {
					serverSocketUDP.receive(receivePacket);
				} catch (IOException e) {
					e.printStackTrace();
				}
				String temp = new String(receivePacket.getData());
				log("\nUDP message: " + temp.trim());
				SocketChannel from = getChannelByAddress(receivePacket.getAddress());
				if (from != null)
					sendBroadcastMessage(temp.trim(), from);
			}
		});
		udpReader.start();
	}

	@Override
	public void run() {
		initServerSockets();

		// block while we wait for a client to connect
		while (running) {
			// check for new client connections
			acceptNewConections();

			// check for incoming mesgs
			readIncomingMessages();

			// sleep a bit
			try {
				Thread.sleep(100);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
		}
	}

}
