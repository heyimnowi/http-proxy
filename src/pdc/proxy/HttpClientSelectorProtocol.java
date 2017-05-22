package pdc.proxy;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import nio.TCPProtocol;

public class HttpClientSelectorProtocol implements TCPProtocol {
    private ConcurrentHashMap<SocketChannel, ProxyConnection> clientToProxyChannelMap = new ConcurrentHashMap<SocketChannel, ProxyConnection>();
    private ConcurrentHashMap<SocketChannel, ProxyConnection> proxyToClientChannelMap = new ConcurrentHashMap<SocketChannel, ProxyConnection>();
	private Selector selector;
    private int bufferSize;

    private InetSocketAddress listenAddress;
    private ServerSocketChannel channel;

    private String host;
    private int port;

	public HttpClientSelectorProtocol(String host, int port, Selector selector, int bufferSize) throws IOException {
    	this.selector = selector;
		this.bufferSize = bufferSize;
        this.port = port;
        this.host = host;
		listenAddress = new InetSocketAddress(host, port);
        channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
        channel.socket().bind(listenAddress);
        channel.register(selector, SelectionKey.OP_ACCEPT);
	}
	
	public ServerSocketChannel getChannel() {
		return channel;
	}

	public void setChannel(ServerSocketChannel channel) {
		this.channel = channel;
	}
	
	/**
     * Accept connections to HTTP Proxy
     * @param key
     * @throws IOException
     */
    public void handleAccept(SelectionKey key) throws IOException {
        ProxyConnection connection = new ProxyConnection(key.selector());

        ServerSocketChannel keyChannel = (ServerSocketChannel) key.channel();
        SocketChannel newChannel = keyChannel.accept();
        Socket socket = newChannel.socket();

        newChannel.configureBlocking(false);

        connection.setClientChannel(newChannel);
        connection.setClientKey(key);

        if (HttpServerSelector.isVerbose()) {
            SocketAddress remoteAddr = socket.getRemoteSocketAddress();
            SocketAddress localAddr = socket.getLocalSocketAddress();
            System.out.println("Accepted new client connection from " + localAddr + " to " + remoteAddr);
        }

        SelectionKey clientKey = newChannel.register(key.selector(), SelectionKey.OP_READ | SelectionKey.OP_WRITE, connection);
        clientKey.attach(connection);

        // TODO - remove this
        //clientToProxyChannelMap.put(newChannel, new ProxyConnection(newChannel));
    }

	/**
     * Read from the socket channel
     * @param key
     * @throws IOException
     */
	public void handleRead(SelectionKey key) throws IOException {
	    ProxyConnection connection = (ProxyConnection) key.attachment();
		SocketChannel keyChannel = (SocketChannel) key.channel();

        int bytesRead = -1;
        connection.buffer = ByteBuffer.allocate(bufferSize);
        bytesRead = keyChannel.read(connection.buffer);

        String side = channelIsServerSide(keyChannel) ? "server" : "client";
        
        if (bytesRead == -1) {
        	// TODO -- Add logger here
            keyChannel.close();
            key.cancel();
            return;
        } else if( bytesRead > 0) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            // TODO -- Add Metrics of bytes read here

            byte[] data = new byte[bytesRead];
            System.arraycopy((connection.buffer).array(), 0, data, 0, bytesRead);
            String stringRead = new String(data, "UTF-8");

            connection.request = new Request(stringRead);
            connection.buffer = ByteBuffer.wrap(stringRead.getBytes());
            System.out.println("Message read --- " + stringRead);
        } else {
            // TODO -- Close Connection
        }

	/*
    	try {
    		System.out.println("==============");
            System.out.println(side + " wrote");
            System.out.println(stringRead);
            if (channelIsServerSide(keyChannel)) {
            	ProxyConnection connection = proxyToClientChannelMap.get(keyChannel);
            	sendToClient(stringRead, connection.getClientChannel());
            } else {
                ProxyConnection connection = clientToProxyChannelMap.get(keyChannel);
                sendToServer(stringRead, connection);
            }
		} catch (Exception e) {
			// TODO: handle exception
		}
	*/
	}

    public void handleWrite(SelectionKey key) throws SocketException, UnsupportedEncodingException {
        ProxyConnection connection = (ProxyConnection) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        SocketChannel clientChannel = connection.getClientChannel();
        SocketChannel serverChannel = connection.getServerChannel();

        ByteBuffer writeBuffer = (ByteBuffer) connection.buffer;

        channel.socket().setSendBufferSize(this.bufferSize);

        if (HttpServerSelector.isVerbose())
            System.out.println("socket can send " + channel.socket().getSendBufferSize() + " bytes per write operation");

        try {
            if(!channel.equals(clientChannel) && !channel.equals(serverChannel) && channel.getRemoteAddress().equals(serverChannel.getRemoteAddress())) {
                connection.setServerChannel(serverChannel);
            }

            if (HttpServerSelector.isVerbose())
                System.out.println("buffer has: " + writeBuffer.remaining() + " remaining bytes");

            if (!writeBuffer.hasRemaining()) {
                writeBuffer.clear();
                writeBuffer.flip();
                if (!writeBuffer.hasRemaining()) {
                    key.interestOps(SelectionKey.OP_READ);
                    return;
                }
            }

            handleSendMessage(key);
            key.interestOps(connection.buffer.position() > 0 ? SelectionKey.OP_READ | SelectionKey.OP_WRITE : SelectionKey.OP_READ);

            /*
                channel.write(buffer);

                if (HttpServerSelector.isVerbose())
                    System.out.println("buffer has: " + buffer.remaining() + " remaining bytes");

                if (buffer.hasRemaining()) {
                    channel.register(selector, SelectionKey.OP_WRITE);
                    SelectionKey channelKey = channel.keyFor(selector);
                    channelKey.attach(buffer);
                } else {
                    channel.register(selector, SelectionKey.OP_READ);
                    buffer.clear();
                }
            */
        } catch (IOException e) {
            // TODO: LOG ERROR
        }
    }

    private void sendToServer(SelectionKey key) {
        ProxyConnection connection = (ProxyConnection) key.attachment();
        try {
            if (connection.getServerChannel() == null) {
                getRemoteServerUrl(connection);
                InetSocketAddress hostAddress = new InetSocketAddress(connection.getServerUrl(), connection.getServerPort());
                SocketChannel serverChannel = SocketChannel.open(hostAddress);
                connection.setServerChannel(serverChannel);

                // TODO - remove this
                //proxyToClientChannelMap.put(serverChannel, connection);
            }

            (connection.getServerChannel()).configureBlocking(false);
            writeInChannel(key, connection.getServerChannel());

            SelectionKey serverKey = (connection.getServerChannel()).register(connection.getSelector(), SelectionKey.OP_READ);
            serverKey.attach(connection);
        } catch (Exception e) {
            System.out.println(e.getStackTrace());
            if( connection.request == null ) {
                System.out.println("La request tiene null, y no se porque :'(");
            } else
                System.out.println("Aca otro error -- " + connection.request.getFullRequest());
        }
    }

    /**
     * Send data to HTTP Client
     * @param key
     */
    private void sendToClient(SelectionKey key) {
        ProxyConnection connection = (ProxyConnection) key.attachment();
        if (HttpServerSelector.isVerbose()) {
            System.out.println("proxy is writing to client the string: " + connection.request.getFullRequest());
        }
        writeInChannel(key, connection.getClientChannel());
    }

    /**
     *
     * Decides whether the message should be sent to the server or to the client.
     *
     * @param key
     *
     */
    private void handleSendMessage(SelectionKey key) {
        ProxyConnection connection = (ProxyConnection) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel == connection.getServerChannel()) {
            sendToClient(key);
        } else {
            sendToServer(key);
        }
    }


    /**
     * Ask if a channel is server side
     * @param channel
     * @return
     */
    private boolean channelIsServerSide(SocketChannel channel) {
    	return proxyToClientChannelMap.get(channel) != null; 
    }

	
	/**
     * Write data to a specific channel
     */
    public void writeInChannel(SelectionKey key, SocketChannel channel) {
    	ProxyConnection connection = (ProxyConnection) key.attachment();
        String stringRead = connection.request.getFullRequest();

        connection.buffer.clear();
        connection.buffer = ByteBuffer.wrap(stringRead.getBytes());
    	try {
    	    // TODO -- Add metrics of transfered bytes
            // TODO -- Log writing to whom
            channel.write(connection.buffer);
		} catch (IOException e) {
		    // TODO -- Log error
			System.out.println(e.getStackTrace());
            System.out.println("Aca error --- " + stringRead);
		}
		connection.buffer.clear();
    }


	public void getRemoteServerUrl(ProxyConnection connection) {
        Request r = connection.request;
    	connection.setServerUrl(r.getUrl());
    	connection.setServerPort(80);
	}


}