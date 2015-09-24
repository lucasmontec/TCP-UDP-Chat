import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
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
	private static final int	BUFFER_SIZE		= 2000;
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
				JOptionPane.showInputDialog("Digite o host para se conectar: ip"));
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

				// Definicao do nome
				if (text.getText().startsWith("!nome")) {
					isCommand = true;
					String command[] = text.getText().split(" ");
					if (command.length > 1) {
						cc.name = command[1];
					} else {
						print("\nCommand format> \"!nome: <seu nome>\"");
					}

				}

				// Comando connect
				if (text.getText().startsWith("!conectar")) {
					isCommand = true;
					String command[] = text.getText().split(" ");
					if (command.length > 1) {
						cc.host = command[1];
						PORT = Shared.PORTTCP;
						cc.connect();
					} else {
						print("\nCommand format> \"!conectar: ip\"");
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

		JButton sendUDP = new JButton("EnviarUDP");
		sendUDP.addActionListener(evt -> {
			boolean isCommand = false;

			// Definicao do nome
			if (text.getText().startsWith("!nome")) {
				isCommand = true;
				String command[] = text.getText().split(" ");
				if (command.length > 1) {
					cc.name = command[1];
				} else {
					print("\nCommand format> \"!nome: <seu nome>\"");
				}

			}

			// Comando connect
			if (text.getText().startsWith("!conectar")) {
				isCommand = true;
				String command[] = text.getText().split(" ");
				if (command.length > 1) {
					cc.host = command[1];
					PORT = Shared.PORTTCP;
					cc.connect();
				} else {
					print("\nCommand format> \"!conectar: ip\"");
				}
			}

			if (!isCommand) {
				print("\nEu: " + text.getText());
				if (cc.name != null && cc.name.length() > 0)
					cc.sendMessageUDP(cc.name + ":" + text.getText());
				else
					cc.sendMessageUDP(cc.host + ":" + text.getText());
				;
			}

			if (text.getText().equals("sair")) {
				cc.shutdown();
				frame.dispose();
			}

			text.setText("");
		});

		frame.add(scroll);
		frame.add(new JLabel("Enviar texto:"));
		frame.add(text);
		frame.add(send);
		frame.add(sendUDP);
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
		this.host = host;
		PORT = Shared.PORTTCP;

		writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		asciiDecoder = Charset.forName("ISO-8859-1").newDecoder();
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
			InetAddress addr = InetAddress.getByName(host.replace("/", "").replace("\\", ""));
			print("Tentando conexão em: " + addr + ":" + PORT);
			channel = SocketChannel.open(new InetSocketAddress(addr, PORT));
			channel.configureBlocking(false);
			channel.register(readSelector, SelectionKey.OP_READ, new StringBuffer());
		} catch (UnknownHostException uhe) {
			print("\nHost desconhecido!");
			print("\nUse \"!conectar: ip\" para se reconectar!");
			uhe.printStackTrace();
		} catch (ConnectException ce) {
			print("\nTempo de conexão perdido!");
			print("\nUse \"!conectar: ip\" para se reconectar!");
			ce.printStackTrace();
		} catch (Exception e) {
			print("\nErro crítico:");
			print("\n" + e.getLocalizedMessage());
			e.printStackTrace();
		}
	}

	private void readIncomingMessages() {
		// Checagem para a chegada de mensagens
		try {
			// Select não bloqueante, retorna imediatamente independente de quantas keys estão prontas
			readSelector.selectNow();

			// Busca as keys
			Set<?> readyKeys = readSelector.selectedKeys();

			// Percorre as keys e processa
			Iterator<?> i = readyKeys.iterator();
			while (i.hasNext()) {
				SelectionKey key = (SelectionKey) i.next();
				i.remove();
				SocketChannel channel = (SocketChannel) key.channel();
				readBuffer.clear();

				// Lê do canal para o buffer
				long nbytes = channel.read(readBuffer);

				// Checa por end-of-stream
				if (nbytes == -1) {
					System.out.println("Desconectado.");
					channel.close();
					shutdown();
				} else {
					// Apanha a StringBuffer que foi armazenada como attachment
					StringBuffer sb = (StringBuffer) key.attachment();

					// Usa um CharsetDecoder para transformar os bytes em uma string
					// e acrescenta a nossa StringBuffer
					readBuffer.flip();
					String str = asciiDecoder.decode(readBuffer).toString();
					sb.append(str);
					readBuffer.clear();

					// Checa por uma linha cheia e escreve em STDOUT
					String line = sb.toString();
					if ((line.indexOf("\n") != -1) || (line.indexOf("\r") != -1)) {
						sb.delete(0, sb.length());
						print("\n>" + line.trim());
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
	 * Envia mensagem string para o server pelo canal TCP
	 * 
	 * @param mesg
	 */
	private void sendMessage(String mesg) {
		prepareWriteBuffer(mesg);
		channelWrite(channel, writeBuffer);
	}

	/**
	 * Envia mensagem string para o server via UDP
	 * 
	 * @param mesg
	 */
	private void sendMessageUDP(String mesg) {
		InetAddress addr = null;
		try {
			addr = InetAddress.getByName(host);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		if (addr != null) {
			byte[] toSend = mesg.getBytes();
			DatagramPacket packet = new DatagramPacket(toSend, toSend.length, addr, Shared.PORTUDP);

			// udp
			DatagramSocket udpSocket = null;
			try {
				udpSocket = new DatagramSocket();
			} catch (SocketException e1) {
				e1.printStackTrace();
			}

			if (udpSocket != null) {
				try {
					udpSocket.send(packet);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else {
				print("Error! UDP socket creation failed.");
			}
		} else {
			print("Error! UDP address failed.");
		}
	}

	private void prepareWriteBuffer(String mesg) {
		// preenche o buffer com a mensagem
		// e a prepara para escrita no canal
		writeBuffer.clear();
		// Coloca a mensagem no buffer
		writeBuffer.put(mesg.getBytes());
		writeBuffer.putChar('\n');
		// Faz um flip no buffer para envio (limit to the current pos, pos to 0)
		writeBuffer.flip();
	}

	private void channelWrite(SocketChannel channel, ByteBuffer writeBuffer) {
		long nbytes = 0;
		// Quanto ha entre a posicao corrente e o fim?
		long toWrite = writeBuffer.remaining();

		// Realiza loop em channel.write() ja que nao ira necessariamente
		// escrever todos os bytes de uma so vez
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

		// Preparacao para outra escrita se necessario
		writeBuffer.rewind();
	}

	public void shutdown() {
		running = false;
		interrupt();
	}

}