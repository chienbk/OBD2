/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.androbd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.fr3ts0n.prot.StreamHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.logging.Level;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for incoming
 * connections, a thread for connecting with a device, and a thread for
 * performing data transmissions when connected.
 */
@SuppressLint("NewApi")
public class BtCommService extends CommService
{

	// Member fields
	private BluetoothAdapter mAdapter;
	private BtConnectThread mConnectThread;
	private BtWorkerThread mConnectedThread;
	private AcceptThread mAcceptThread;
	private BluetoothDevice myDevice;
	private String mSocketType;
	/** communication stream handler */
	public StreamHandler ser = new StreamHandler();

	// Unique UUID for this application
	private static final UUID MY_UUID = UUID
			.fromString("0001101-0000-1000-8000-00805F9B34FB");


	/**
	 * Constructor. Prepares a new Communication session.
	 */
	public BtCommService()
	{
		super();
	}

	/**
	 * Constructor. Prepares a new Bluetooth Communication session.
	 *
	 * @param context The UI Activity Context
	 * @param handler A Handler to send messages back to the UI Activity
	 */
	public BtCommService(Context context, Handler handler) {
		super(context, handler);
		// set up protocol handlers
		mAdapter = BluetoothAdapter.getDefaultAdapter();
		mHandler = handler;
		mState = STATE.NONE;
		elm.addTelegramWriter(ser);
		ser.setMessageHandler(elm);
	}

	/**
	 * Start the chat service. Specifically start AcceptThread to begin a session
	 * in listening (server) mode. Called by the Activity onResume()
	 */
	@Override
	public synchronized void start() {
		log.fine("start");

		// Cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		setState(STATE.LISTEN);

		// Start the thread to listen on a BluetoothServerSocket
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}
	}

	/**
	 * start connection to specified device
	 *
	 * @param device The device to connect
	 * @param secure Socket Security type - Secure (true) , Insecure (false)
	 */
	@Override
	public synchronized void connect(Object device, boolean secure) {
		log.fine("connect to: " + device);
		this.myDevice = (BluetoothDevice) device;
		// Cancel any thread attempting to make a connection
		if (mState == STATE.CONNECTING)
		{
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to connect with the given device
		mConnectThread = new BtConnectThread((BluetoothDevice)device, secure);
		mConnectThread.start();

		setState(STATE.CONNECTING);
	}

	/**
	 * Start the BtWorkerThread to begin managing a Bluetooth connection
	 *
	 * @param socket The BluetoothSocket on which the connection was made
	 * @param device The BluetoothDevice that has been connected
	 */
	public synchronized void connected(BluetoothSocket socket, BluetoothDevice
			device, final String socketType) {
		log.fine("connected, Socket Type:" + socketType);

		this.myDevice = device;
		this.mSocketType = socketType;
		// Cancel the thread that completed the connection
		if (mConnectThread != null)
		{
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null)
		{
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
		// Start the thread to manage the connection and perform transmissions
		mConnectedThread = new BtWorkerThread(socket, socketType);
		mConnectedThread.start();

		// we are connected -> signal connection established
		connectionEstablished(device.getName());
		setState(STATE.CONNECTED);
	}

	/**
	 * Stop all threads
	 */
	@Override
	public synchronized void stop()
	{
		log.fine("stop");
		elm.removeTelegramWriter(ser);

		if (mConnectThread != null)
		{
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null)
		{
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		setState(STATE.OFFLINE);
	}

	/**
	 * Write to the BtWorkerThread in an unsynchronized manner
	 *
	 * @param out The bytes to write
	 * @see BtWorkerThread#write(byte[])
	 */
	@Override
	public synchronized void write(byte[] out)
	{
		// Perform the write unsynchronized
		mConnectedThread.write(out);
	}

	/**
	 * This thread runs while attempting to make an outgoing connection with a
	 * device. It runs straight through; the connection either succeeds or fails.
	 */
	private class BtConnectThread extends Thread
	{
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;
		private String mSocketType;

		public BtConnectThread(BluetoothDevice device, boolean secure)
		{
			mmDevice = device;
			BluetoothSocket tmp = null;
			mSocketType = secure ? "Secure" : "Insecure";

			// Modified to work with SPP Devices
			final UUID SPP_UUID = UUID
					.fromString("00001101-0000-1000-8000-00805F9B34FB");

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try
			{
				if (secure)
				{
					tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
				} else
				{
					tmp = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
				}
			} catch (IOException e)
			{
				log.log(Level.SEVERE, "Socket Type: " + mSocketType + "create() failed", e);
			}
			mmSocket = tmp;
		}

		public void run()
		{
			log.info("BEGIN mBtConnectThread SocketType:" + mSocketType);
			setName("BtConnectThread" + mSocketType);

			// Always cancel discovery because it will slow down a connection
			mAdapter.cancelDiscovery();

			// Make a connection to the BluetoothSocket
			try
			{
				// This is a blocking call and will only return on a
				// successful connection or an exception
				mmSocket.connect();
			} catch (IOException e)
			{
				// Close the socket
				try
				{
					mmSocket.close();
				} catch (IOException e2)
				{
					log.log(Level.SEVERE, "unable to close() " + mSocketType +
							" socket during connection failure", e2);
				}
				connectionFailed();
				return;
			}

			// Reset the BtConnectThread because we're done
			synchronized (BtCommService.this)
			{
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice, mSocketType);
		}

		public void cancel()
		{
			try
			{
				mmSocket.close();
			} catch (IOException e)
			{
				log.log(Level.SEVERE, "close() of connect " + mSocketType + " socket failed", e);
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device. It handles all
	 * incoming and outgoing transmissions.
	 */
	private class BtWorkerThread extends Thread
	{
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;

		public BtWorkerThread(BluetoothSocket socket, String socketType)
		{
			log.fine("create BtWorkerThread: " + socketType);
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the BluetoothSocket input and output streams
			try
			{
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e)
			{
				log.log(Level.SEVERE, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
			// set streams
			ser.setStreams(mmInStream, mmOutStream);
		}

		/**
		 * run the main communication loop
		 */
		public void run()
		{
			log.info("BEGIN mBtWorkerThread");
			try
			{
				// run the communication thread
				ser.run();
			} catch (Exception ex)
			{
				// Intentionally ignore
				log.log(Level.SEVERE, "Comm thread aborted", ex);
			}
			connectionLost();
		}

		/**
		 * Write to the connected OutStream.
		 *
		 * @param buffer The bytes to write
		 */
		public void write(byte[] buffer)
		{
			ser.writeTelegram(new String(buffer).toCharArray());
		}

		public void cancel()
		{
			try
			{
				mmSocket.close();
			} catch (IOException e)
			{
				log.log(Level.SEVERE, "close() of connect socket failed", e);
			}
		}

	}

	/**
	 * This thread runs while listening for incoming connections. It behaves
	 * like a server-side client. It runs until a connection is accepted (or
	 * until cancelled).
	 */
	private class AcceptThread extends Thread {
		// The local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;

			// Create a new listening server socket
			try {
				tmp = mAdapter.listenUsingRfcommWithServiceRecord(TAG, MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
			}
			mmServerSocket = tmp;
		}

		public void run() {
			if (true)
				Log.d(TAG, "Socket Type: " + mSocketType
						+ "BEGIN mAcceptThread" + this);
			setName("AcceptThread" + mSocketType);

			BluetoothSocket socket = null;

			// Listen to the server socket if we're not connected
			while (mState != STATE.CONNECTED) {
				try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, "Socket Type: " + mSocketType
							+ "accept() failed", e);
					break;
				}
			}
			if (true)
				Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType);

		}

		public void cancel() {
			if (true)
				Log.d(TAG, "Socket Type" + mSocketType + "cancel " + this);
			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "Socket Type" + mSocketType + "close() of server failed", e);
			}
		}
	}
}
