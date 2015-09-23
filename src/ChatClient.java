import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Iterator;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;


public class ChatClient extends Thread {
	private static final int	BUFFER_SIZE			= 255;
	private static final long	CNNL_WR_SLEEP	= 10L;
	private static int				PORT				= 27015;

	private final ByteBuffer			writeBuffer;
	private final ByteBuffer			readBuffer;
	private static boolean			running;
	private SocketChannel			channel;
	private String					host;
	private Selector			readSelector;
	private final CharsetDecoder		asciiDecoder;
	private static JTextArea		textArea;
	static JTextField				text;
	private String					name;

	public static void main(String args[]) {

		final ChatClient cc = new ChatClient(
				JOptionPane.showInputDialog("Digite o host para se conectar: ip:porta"));
		cc.start();

		text = new JTextField();
		text.setPreferredSize(new Dimension(160, 25));

		textArea = new JTextArea();
		textArea.setBackground(Color.black);
		textArea.setForeground(Color.green);
		textArea.setLineWrap(true);
		JScrollPane scroll = new JScrollPane(textArea);
		scroll.setPreferredSize(new Dimension(210, 180));
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

		final JFrame frame = new JFrame();
		frame.setVisible(true);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(250, 320);
		frame.setLayout(new FlowLayout());

		JButton send = new JButton("Enviar");
		send.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				boolean isCommand = false;

				// Name
				if (text.getText().startsWith("!nome")) {
					isCommand = true;
					String command[] = text.getText().split(" ");
					if (command.length > 1) {
						cc.name = command[1];
					} else {
						print("\nCommand format> \"!nome: <seu nome>\"");
					}

				}

				// Connect command
				if (text.getText().startsWith("!conectar")) {
					isCommand = true;
					String command[] = text.getText().split(" ");
					if(command.length > 1) {
						String address[] = command[1].split(":");
						if (address.length > 1) {
							cc.host = address[0];
							PORT = Integer.parseInt(address[1]);
						} else {
							cc.host = address[0];
							PORT = 27015;
						}
						cc.connect();
					}else {
						print("\nCommand format> \"!conectar: <ip:porta>\"");
					}
				}

				if (!isCommand) {
					print("\nEu: " + text.getText());
					if (cc.name != null && cc.name.length() > 0)
						cc.sendMessage(cc.name + ":" + text.getText());
					else
						cc.sendMessage(text.getText());
				}

				if (text.getText().equals("sair")) {
					cc.shutdown();
					frame.dispose();
				}

				text.setText("");
			}
		});

		frame.add(scroll);
		frame.add(new JLabel("Enviar texto:"));
		frame.add(text);
		frame.add(send);
	}

	private static void print(String txt) {
		textArea.append(txt);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				textArea.setCaretPosition(textArea.getText().length());
			}
		});
	}

	public ChatClient(String host) {
		String address[] = host.split(":");
		if (address.length > 1) {
			this.host = address[0];
			PORT = Integer.parseInt(address[1]);
		} else {
			this.host = address[0];
			PORT = 27015;
		}
		writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		asciiDecoder = Charset.forName("US-ASCII").newDecoder();

	}

	@Override
	public void run() {
		connect();

		running = true;
		while (running) {
			readIncomingMessages();

			try {
				Thread.sleep(100);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
		}
		shutdown();
	}

	private void connect() {
		try {
			readSelector = Selector.open();
			InetAddress addr = InetAddress.getByName(host);
			channel = SocketChannel.open(new InetSocketAddress(addr, PORT));
			channel.configureBlocking(false);
			channel.register(readSelector, SelectionKey.OP_READ, new StringBuffer());
		} catch (UnknownHostException uhe) {
			print("\nHost desconhecido!");
			print("\nUse \"!conectar: ip:porta\" para se reconectar!");
			uhe.printStackTrace();
		} catch (ConnectException ce) {
			print("\nTempo de conexão perdido!");
			print("\nUse \"!conectar: ip:porta\" para se reconectar!");
			ce.printStackTrace();
		} catch (Exception e) {
			print("\nErro crítico:");
			print("\n" + e.getLocalizedMessage());
			e.printStackTrace();
		}
	}

	private void readIncomingMessages() {
		// check for incoming mesgs
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
				SocketChannel channel = (SocketChannel) key.channel();
				readBuffer.clear();

				// read from the channel into our buffer
				long nbytes = channel.read(readBuffer);

				// check for end-of-stream
				if (nbytes == -1) {
					System.out.println("Desconectado.");
					channel.close();
					shutdown();
				} else {
					// grab the StringBuffer we stored as the attachment
					StringBuffer sb = (StringBuffer) key.attachment();

					// use a CharsetDecoder to turn those bytes into a string
					// and append to our StringBuffer
					readBuffer.flip();
					String str = asciiDecoder.decode(readBuffer).toString();
					sb.append(str);
					readBuffer.clear();

					// check for a full line and write to STDOUT
					String line = sb.toString();
					if ((line.indexOf("\n") != -1) || (line.indexOf("\r") != -1)) {
						sb.delete(0, sb.length());
						print("\n>" + line);
					}
				}
			}
		} catch (IOException ioe) {
			ioe.printStackTrace();
			print("\n>Perdeu a conexão com o server.");
			try {
				channel.close();
			} catch (IOException e) {
				print("\n>Erro de canal com o server.");
				channel = null;
				e.printStackTrace();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Envia mensagem string para o server pelo canal
	 * 
	 * @param mesg
	 */
	private void sendMessage(String mesg) {
		prepareWriteBuffer(mesg);
		channelWrite(channel, writeBuffer);
	}

	private void prepareWriteBuffer(String mesg) {
		// fills the buffer with the message
		// and prepares it for a channel write
		writeBuffer.clear();
		// Paste the message in the buffer
		writeBuffer.put(mesg.getBytes());
		writeBuffer.putChar('\n');
		// Flip the buffer to send (limit to the current pos, pos to 0)
		writeBuffer.flip();
	}

	private void channelWrite(SocketChannel channel, ByteBuffer writeBuffer) {
		long nbytes = 0;
		// How much there is between the curr pos and end?
		long toWrite = writeBuffer.remaining();

		// loop on the channel.write() call since it will not necessarily
		// write all bytes in one shot
		try {
			// System.out.print("\nSending:\n");
			while (nbytes != toWrite) {
				nbytes += channel.write(writeBuffer);
				// System.out.println((nbytes / toWrite) + "% ");
				try {
					Thread.sleep(CNNL_WR_SLEEP);
				} catch (InterruptedException e) {
				}
			}
			// System.out.print("\nMessage sent!");
		} catch (ClosedChannelException cce) {
			cce.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

		// get ready for another write if needed
		writeBuffer.rewind();
	}

	public void shutdown() {
		running = false;
		interrupt();
	}

}